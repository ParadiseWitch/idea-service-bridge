import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { fileURLToPath } from 'node:url';
import { mkdir,writeFile } from 'node:fs/promises';

const baseUrl=process.env.IDEA_BRIDGE_URL ?? 'http://127.0.0.1:17891';
const transport=new StdioClientTransport({ command:process.execPath,
  args:[fileURLToPath(new URL('../src/index.mjs',import.meta.url)),'--base-url',baseUrl], stderr:'pipe' });
let diagnostics=''; transport.stderr.on('data', chunk => { diagnostics += chunk; });
const client=new Client({name:'idea-bridge-readonly-smoke',version:'1.0.0'});
try {
  await client.connect(transport);
  const { tools }=await client.listTools();
  const status=await client.callTool({name:'idea_list_configurations',arguments:{}});
  if(status.isError) throw new Error(status.content[0].text);
  const help=await client.callTool({name:'idea_get_help',arguments:{}});
  if(help.isError) throw new Error(help.content[0].text);
  const { resources }=await client.listResources();
  await client.readResource({uri:resources[0].uri});
  const report={ testedAt:new Date().toISOString(),baseUrl,backendVersion:status.structuredContent.version,
    tools:tools.map(tool=>tool.name),resources:resources.map(resource=>resource.uri),
    configurations:status.structuredContent.configurations,
    readonly:true,diagnostics };
  await mkdir(fileURLToPath(new URL('../../build/reports/',import.meta.url)),{recursive:true});
  await writeFile(fileURLToPath(new URL('../../build/reports/mcp-smoke.json',import.meta.url)),JSON.stringify(report,null,2));
  console.log(JSON.stringify(report,null,2));
} finally { await client.close(); }
