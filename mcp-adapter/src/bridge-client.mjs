export class BridgeHttpError extends Error {
  constructor(message, httpStatus) { super(message); this.name = 'BridgeHttpError'; this.httpStatus = httpStatus; }
}

/** Fixed localhost backend. No mutation retries, implicit restart, shell commands or IDE lifecycle changes. */
export class BridgeClient {
  constructor({ baseUrl = process.env.IDEA_BRIDGE_URL ?? 'http://127.0.0.1:17891', timeoutMs = 15_000, fetchImpl = fetch } = {}) {
    const url = new URL(baseUrl);
    if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || url.username || url.password ||
        url.pathname !== '/' || url.search || url.hash)
      throw new Error('IDEA_BRIDGE_URL 必须是本机 HTTP 根地址，例如 http://127.0.0.1:17891');
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new Error('timeoutMs 必须为正数');
    this.baseUrl = url.origin; this.timeoutMs = timeoutMs; this.fetch = fetchImpl;
  }
  async request(path, { body, text = false, signal } = {}) {
    const timeout = AbortSignal.timeout(this.timeoutMs);
    let response;
    try {
      response = await this.fetch(this.baseUrl + path, {
        method: body === undefined ? 'GET' : 'POST',
        ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
        signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
        redirect: 'error'
      });
      const payload = await response.text();
      if (!response.ok) {
        let error = payload;
        try { error = JSON.parse(payload).error ?? payload; } catch { }
        throw new BridgeHttpError(`IDEA HTTP ${response.status}: ${String(error).slice(0, 2000)}`, response.status);
      }
      if (text) return payload;
      const result = JSON.parse(payload);
      if (!result || typeof result !== 'object' || Array.isArray(result)) throw new Error('接口返回值不是 JSON 对象');
      return result;
    } catch (error) {
      if (error instanceof BridgeHttpError) throw error;
      const suffix = body === undefined ? '' : '。提交可能已被接收，请先查询运行状态；不要自动重复提交启动或重启';
      throw new Error(`无法读取 IDEA 接口 ${this.baseUrl}${path}：${error.message}${suffix}`, { cause: error });
    }
  }
  status(signal) { return this.request('/v1/status', { signal }); }
  task(id, signal) { return this.request(`/v1/tasks/${encodeURIComponent(id)}`, { signal }); }
  help(signal) { return this.request('/v1/skill', { text: true, signal }); }
  async submit(operation, input, signal) {
    if (operation === 'start') {
      const status = await this.status(signal);
      const match = /^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/.exec(status.version ?? '');
      if (!match || (Number(match[1]) === 0 && Number(match[2]) < 3))
        throw new Error(`启动配置需要 IDEA 插件 0.3.0 或更新版本；当前为 ${status.version ?? '未知版本'}。已有查询和更新接口仍可使用`);
      return this.request('/v1/tasks', { body: { ...input, operation, executor: input.executor ?? 'Debug', executionId: 0 }, signal });
    }
    return this.request('/v1/tasks', { body: { ...input, operation }, signal });
  }
}
