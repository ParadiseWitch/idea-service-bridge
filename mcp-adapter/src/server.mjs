import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { BridgeClient } from './bridge-client.mjs';

const location = {
  project: z.string().min(1).max(1024).describe('IDEA 项目绝对路径，从运行状态查询获取，例如 C:/code/dev'),
  configuration: z.string().min(1).max(256).describe('运行配置准确名称，例如 portal 或 file-agent'),
  caller: z.string().min(1).max(128).describe('当前 agent 或会话标识，用于任务记录')
};
const existing = { ...location, executionId: z.number().int().positive().max(Number.MAX_SAFE_INTEGER).describe('从最新运行状态获取的实例 ID；重启后必须重新查询') };
const readOnly = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false };
const mutation = { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false };

function result(value) {
  return { content: [{ type: 'text', text: JSON.stringify(value, null, 2) }], structuredContent: value,
    ...(value.status === 'FAILED' ? { isError: true } : {}) };
}
function protect(action) {
  return async (input, extra) => {
    try { return await action(input, extra?.signal); }
    catch (error) {
      return { isError: true, content: [{ type: 'text', text: error.message }],
        structuredContent: { error: error.message, ...(error.httpStatus ? { httpStatus: error.httpStatus } : {}) } };
    }
  };
}

export function createServer(options = {}) {
  const bridge = new BridgeClient(options);
  const server = new McpServer({ name: 'idea-agent-bridge', version: '1.0.0' }, {
    instructions: '先查询运行配置。start 只启动未运行配置；更新和重启必须指定最新 executionId。同项目 409 时等待，不自动重试修改请求。提交返回任务 ID，再查询任务直到完成；processStarted 不表示业务健康就绪。'
  });
  server.registerTool('idea_list_configurations', {
    title: '查询 IDEA 运行配置', description: '只读查询项目、运行配置、实例 ID、Run/Debug 模式、端口及忙碌状态。',
    inputSchema: {}, annotations: readOnly
  }, protect(async (_, signal) => result(await bridge.status(signal))));

  server.registerTool('idea_start_configuration', {
    title: '启动 IDEA 配置', description: '启动尚未运行的配置，默认 Debug，可选 Run；要求插件 >=0.3.0。拒绝重复启动，不停止已有进程。返回任务 ID，随后使用 idea_get_task 查询。',
    inputSchema: { ...location, executor: z.enum(['Debug', 'Run']).default('Debug') }, annotations: mutation
  }, protect(async (input, signal) => result(await bridge.submit('start', input, signal))));

  for (const [name, operation, title, description] of [
    ['idea_update_resources', 'update-resources', '更新静态资源', '编译并通知指定应用服务器更新资源；任务成功后仍需访问业务页面确认生效。'],
    ['idea_update_classes_and_resources', 'update-classes-and-resources', '更新 class 和资源', '仅对指定 Debug 实例执行 HotSwap。要求本地应用服务器 exploded 部署；失败不会自动重启。'],
    ['idea_restart_configuration', 'restart', '重启指定运行实例', '明确需要重启且已协调共享服务时使用。只重启指定实例，保留 Run/Debug；返回进程启动不代表业务已经就绪。']
  ]) {
    server.registerTool(name, { title, description: description + ' 提交返回任务 ID，使用 idea_get_task 查询结果。', inputSchema: existing, annotations: mutation },
      protect(async (input, signal) => result(await bridge.submit(operation, input, signal))));
  }

  server.registerTool('idea_get_task', {
    title: '查询 IDEA 异步任务', description: '查询 RUNNING / SUCCEEDED / FAILED、编译错误及结果。RUNNING 时稍后再查，不要重新提交原操作。',
    inputSchema: { taskId: z.string().uuid().describe('提交操作返回的任务 UUID') }, annotations: readOnly
  }, protect(async ({ taskId }, signal) => result(await bridge.task(taskId, signal))));

  server.registerTool('idea_get_help', {
    title: '读取 IDEA 使用说明', description: '读取插件提供的最新使用流程、支持操作、并发限制和错误处理说明。',
    inputSchema: {}, annotations: readOnly
  }, protect(async (_, signal) => ({ content: [{ type: 'text', text: await bridge.help(signal) }] })));

  server.registerResource('idea-bridge-usage', 'idea-agent-bridge://usage', {
    title: 'IDEA Agent Bridge 使用说明', mimeType: 'text/markdown', description: '从正在运行的 IDEA 插件读取最新使用说明。'
  }, async (uri, extra) => ({ contents: [{ uri: uri.href, mimeType: 'text/markdown', text: await bridge.help(extra.signal) }] }));
  return server;
}
