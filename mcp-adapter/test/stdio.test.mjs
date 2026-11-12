import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

test('real stdio child discovers and calls tools from an unrelated working directory', async t => {
  let calls = 0;
  const backend = http.createServer((req,res) => {
    calls++; res.setHeader('content-type','application/json');
    res.end(JSON.stringify({ version:'0.3.0', busy:false, configurations:[], allowedConfigurations:['portal'] }));
  });
  backend.listen(0,'127.0.0.1'); await once(backend,'listening');
  t.after(async () => { backend.closeAllConnections(); await new Promise(resolve => backend.close(resolve)); });
  const transport = new StdioClientTransport({ command:process.execPath,
    args:[fileURLToPath(new URL('../src/index.mjs',import.meta.url)), '--base-url', `http://127.0.0.1:${backend.address().port}`],
    cwd:fileURLToPath(new URL('../../',import.meta.url)), stderr:'pipe' });
  let stderr=''; transport.stderr.on('data', chunk => { stderr += chunk; });
  const client = new Client({ name:'stdio-test', version:'1.0.0' });
  t.after(async () => client.close());
  await client.connect(transport);
  assert.equal((await client.listTools()).tools.length,7);
  const result = await client.callTool({ name:'idea_list_configurations', arguments:{} });
  assert.equal(result.structuredContent.version,'0.3.0');
  assert.equal(calls,1); assert.equal(stderr,'');
  await client.close();
  assert.equal(transport.pid,null);
});
