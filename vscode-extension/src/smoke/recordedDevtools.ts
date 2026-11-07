/** Isolated-editor DOM driver. It never connects to a user's running editor or browser. */
export class RecordedDevtools {
  readonly errors: unknown[] = [];
  private socket: any;
  private sequence = 0;
  private pending = new Map<number, { resolve(value: any): void; reject(error: Error): void }>();
  private contexts = new Map<string, Set<number>>();
  private targets = new Set<string>();
  private lastSession?: string;
  static async open(port: number): Promise<RecordedDevtools> {
    const version: any = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
    const driver = new RecordedDevtools();
    driver.socket = new (globalThis as any).WebSocket(version.webSocketDebuggerUrl);
    await new Promise<void>((resolve, reject) => { driver.socket.addEventListener("open", resolve, { once: true }); driver.socket.addEventListener("error", reject, { once: true }); });
    driver.socket.addEventListener("message", (event: any) => {
      const m = JSON.parse(String(event.data));
      if (m.method === "Runtime.exceptionThrown") driver.errors.push(m.params.exceptionDetails);
      if (m.id) { const request = driver.pending.get(m.id); driver.pending.delete(m.id); if (m.error) request?.reject(new Error(m.error.message)); else request?.resolve(m.result); }
      else if (m.method === "Runtime.executionContextCreated") {
        const known = driver.contexts.get(m.sessionId) ?? new Set<number>(); known.add(m.params.context.id); driver.contexts.set(m.sessionId, known);
      } else if (m.method === "Runtime.executionContextDestroyed") driver.contexts.get(m.sessionId)?.delete(m.params.executionContextId);
      else if (m.method === "Runtime.executionContextsCleared") driver.contexts.set(m.sessionId, new Set());
    });
    return driver;
  }
  call(method: string, params: unknown = {}, sessionId?: string): Promise<any> {
    const id = ++this.sequence;
    return new Promise((resolve, reject) => { this.pending.set(id, { resolve, reject }); this.socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) })); });
  }
  async evaluate(selector: string, expression: string): Promise<any> {
    const targets = await this.call("Target.getTargets");
    for (const target of targets.targetInfos) {
      if (this.targets.has(target.targetId) || !["page", "iframe", "webview"].includes(target.type)) continue;
      try {
        const { sessionId } = await this.call("Target.attachToTarget", { targetId: target.targetId, flatten: true });
        this.targets.add(target.targetId); this.contexts.set(sessionId, new Set()); await this.call("Runtime.enable", {}, sessionId);
      } catch { /* A closing target is not this fixture's page. */ }
    }
    for (const [session, contexts] of this.contexts) for (const contextId of contexts) {
      try {
        const result = await this.call("Runtime.evaluate", { contextId, expression: `(() => { if (!(${selector})) return undefined; return (${expression}); })()`, returnByValue: true, awaitPromise: true }, session);
        if (result.exceptionDetails) continue;
        if (result.result.type !== "undefined") { this.lastSession = session; return result.result.value; }
      } catch { /* Retry after a renderer is recreated. */ }
    }
    return undefined;
  }
  async enter(selector: string, element: string): Promise<void> {
    if (!await this.evaluate(selector, `(() => { const e=document.querySelector(${JSON.stringify(element)}); if (!e) return false; e.focus(); return document.activeElement===e; })()`)) throw new Error("Keyboard target is not focused");
    await this.call("Input.dispatchKeyEvent", { type: "keyDown", key: "Enter", code: "Enter", windowsVirtualKeyCode: 13, text: "\r" }, this.lastSession);
    await this.call("Input.dispatchKeyEvent", { type: "keyUp", key: "Enter", code: "Enter", windowsVirtualKeyCode: 13 }, this.lastSession);
  }
  async screenshot(): Promise<Buffer> {
    const { targetInfos } = await this.call("Target.getTargets");
    const target = targetInfos.find((t: any) => t.type === "page" && t.url.includes("workbench"));
    const { sessionId } = await this.call("Target.attachToTarget", { targetId: target.targetId, flatten: true });
    const result = await this.call("Page.captureScreenshot", { format: "png" }, sessionId);
    return Buffer.from(result.data, "base64");
  }
  close(): void { this.socket.close(); }
}
