/** Entry descriptions are requested for their owning editor, independently of tree focus. */
export interface InfoLog<S> { path: string; spec?: S; listing?: object }
export class EntryInfo<S> {
  private readonly infos = new WeakMap<InfoLog<S>, { spec: S; path: string; listing?: object; entries: Map<string, Promise<Record<string, unknown>>> }>();
  constructor(private readonly load: (spec: S, path: string, name: string) => Promise<Record<string, unknown>>) {}
  read(log: InfoLog<S> | undefined, name: string): Promise<Record<string, unknown>> {
    if (!log?.spec) return Promise.resolve({ status: "error", error: "The log is not loaded" });
    let cached = this.infos.get(log);
    if (!cached || cached.spec !== log.spec || cached.path !== log.path || cached.listing !== log.listing) {
      cached = { spec: log.spec, path: log.path, listing: log.listing, entries: new Map() };
      this.infos.set(log, cached);
    }
    let value = cached.entries.get(name);
    if (!value) { value = this.load(log.spec, log.path, name); cached.entries.set(name, value); }
    return value;
  }
}
