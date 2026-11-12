import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { BridgeClient } from '../src/bridge-client.mjs';
import { createServer } from '../src/server.mjs';

async function fixture(t, options = {}) {
  const requests = [];
  const httpServer = http.createServer(async (req, res) => {
    let text = ''; for await (const chunk of req) text += chunk;
    requests.push({ method: req.method, path: req.url, body: text ? JSON.parse(text) : null, auth: req.headers.authorization });
    if (options.fail) { res.writeHead(409, { 'content-type': 'application/json' }); res.end('{"error":"Project has an active task"}'); return; }
    if (req.url === '/v1/skill') { res.writeHead(200, { 'content-type': 'text/markdown' }); res.end('# IDEA 使用说明\n先查询 executionId'); return; }
    res.setHeader('content-type', 'application/json');
    if (req.url === '/v1/status') res.end(JSON.stringify({ version: options.version ?? '0.3.0', busy: false, configurations: [{ project:'C:/code/dev', configuration:'portal', instances:[] }] }));
    else if (req.url === '/v1/tasks' && req.method === 'POST') res.end(JSON.stringify({ id:'11223344-5566-7788-99aa-bbccddeeff00', status:'RUNNING' }));
    else if (req.url.startsWith('/v1/tasks/')) res.end(JSON.stringify({ id:'11223344-5566-7788-99aa-bbccddeeff00', status: options.taskStatus ?? 'SUCCEEDED', ...(options.taskStatus === 'FAILED' ? { error:'Compilation failed' } : { result:{ processStarted:true, applicationHealthVerified:false } }) }));
    else { res.statusCode = 404; res.end('{"error":"not found"}'); }
  });
  httpServer.listen(0, '127.0.0.1'); await once(httpServer, 'listening');
  const baseUrl = `http://127.0.0.1:${httpServer.address().port}`;
  const server = createServer({ baseUrl });
  const client = new Client({ name:'test-client', version:'1.0.0' });
  const [a,b] = InMemoryTransport.createLinkedPair();
  await server.connect(a); await client.connect(b);
  t.after(async () => { await client.close(); await server.close(); httpServer.closeAllConnections(); await new Promise(resolve => httpServer.close(resolve)); });
  return { client, requests, baseUrl };
}

test('MCP discovers typed tools and read-only status works without token', async t => {
  const { client, requests } = await fixture(t);
  const { tools } = await client.listTools();
  assert.equal(tools.length, 7);
  const start = tools.find(x => x.name === 'idea_start_configuration');
  assert.ok(start.inputSchema.properties.executor);
  assert.equal(start.inputSchema.properties.executionId, undefined);
  assert.equal(tools.find(x => x.name === 'idea_list_configurations').annotations.readOnlyHint, true);
  const result = await client.callTool({ name:'idea_list_configurations', arguments:{} });
  assert.equal(result.structuredContent.version, '0.3.0');
  assert.equal(requests.length, 1); assert.equal(requests[0].auth, undefined);
});

test('start defaults to Debug and returns task id without waiting or claiming readiness', async t => {
  const { client, requests } = await fixture(t);
  const result = await client.callTool({ name:'idea_start_configuration', arguments:{ project:'C:/code/dev', configuration:'portal', caller:'agent-a' } });
  assert.equal(result.structuredContent.status, 'RUNNING');
  const post = requests.find(x => x.method === 'POST');
  assert.deepEqual(post.body, { project:'C:/code/dev', configuration:'portal', caller:'agent-a', operation:'start', executor:'Debug', executionId:0 });
  assert.equal(requests.filter(x => x.method === 'POST').length, 1);
});

test('invalid existing-instance requests never reach HTTP backend', async t => {
  const { client, requests } = await fixture(t);
  const result = await client.callTool({ name:'idea_restart_configuration', arguments:{ project:'C:/code/dev', configuration:'portal', caller:'agent-a', executionId:0 } });
  assert.equal(result.isError, true); assert.equal(requests.length, 0);
});

test('HTTP conflicts are tool errors and are never automatically retried', async t => {
  const { client, requests } = await fixture(t, { fail:true });
  const result = await client.callTool({ name:'idea_update_resources', arguments:{ project:'C:/code/dev', configuration:'portal', caller:'agent-a', executionId:9 } });
  assert.equal(result.isError, true); assert.equal(result.structuredContent.httpStatus, 409);
  assert.equal(requests.length, 1); assert.equal(requests[0].body.operation, 'update-resources');
});

test('task failure remains a tool error without any fallback restart', async t => {
  const { client, requests } = await fixture(t, { taskStatus:'FAILED' });
  const result = await client.callTool({ name:'idea_get_task', arguments:{ taskId:'11223344-5566-7788-99aa-bbccddeeff00' } });
  assert.equal(result.isError, true); assert.equal(result.structuredContent.error, 'Compilation failed');
  assert.equal(requests.length, 1); assert.equal(requests[0].method, 'GET');
});

test('agent help is available both as a tool and MCP resource', async t => {
  const { client } = await fixture(t);
  const result = await client.callTool({ name:'idea_get_help', arguments:{} });
  assert.match(result.content[0].text, /executionId/);
  const { resources } = await client.listResources();
  const read = await client.readResource({ uri: resources[0].uri });
  assert.match(read.contents[0].text, /IDEA/);
});

test('old plugin version cannot receive a start POST', async t => {
  const { client, requests } = await fixture(t, { version:'0.2.0' });
  const result = await client.callTool({ name:'idea_start_configuration', arguments:{ project:'C:/code/dev', configuration:'portal', caller:'agent-a' } });
  assert.equal(result.isError, true); assert.match(result.content[0].text, /0\.3\.0/);
  assert.equal(requests.filter(x => x.method === 'POST').length, 0);
});

test('backend URL is restricted to the existing local HTTP API', () => {
  for (const url of ['http://example.com:17891', 'http://user:pass@127.0.0.1:17891', 'https://127.0.0.1:17891', 'http://127.0.0.1:17891/other'])
    assert.throws(() => new BridgeClient({ baseUrl:url }));
});

test('network failure does not retry a mutation', async () => {
  let calls=0;
  const bridge=new BridgeClient({ fetchImpl:async()=> { calls++; throw new Error('connection refused'); } });
  await assert.rejects(bridge.submit('restart',{ project:'C:/code/dev',configuration:'portal',executionId:9,caller:'test' }), /不要自动重复提交/);
  assert.equal(calls,1);
});
