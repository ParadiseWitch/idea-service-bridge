import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { createServer } from './server.mjs';

const args = process.argv.slice(2);
if (args.length && (args.length !== 2 || args[0] !== '--base-url')) {
  process.stderr.write('用法：node src/index.mjs [--base-url http://127.0.0.1:17891]\n');
  process.exit(1);
}
let server;
let closing = false;
async function shutdown() {
  if (closing) return; closing = true;
  await server?.close(); process.exit(0);
}
try {
  server = createServer(args.length ? { baseUrl: args[1] } : {});
  await server.connect(new StdioServerTransport());
  process.once('SIGINT', shutdown);
  process.once('SIGTERM', shutdown);
  process.stdin.once('end', shutdown);
} catch (error) {
  process.stderr.write(`IDEA MCP 适配器启动失败：${error.message}\n`);
  process.exit(1);
}
