import { promises as fs } from "node:fs";
import * as path from "node:path";
import { randomUUID } from "node:crypto";
import { Journal, Recovery } from "./documentController";

/** One bounded extension-host journal per window. Never used as a portable template store. */
export class RecoveryJournal implements Journal {
  private serial: Promise<unknown> = Promise.resolve();
  constructor(private directory: string, private maxBytes = 64 * 1024 * 1024, private maxDocuments = 256) {}
  private filename(id: string): string {
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) throw new Error("Invalid recovery view identity");
    return path.join(this.directory, id + ".json");
  }
  private queue<T>(action: () => Promise<T>): Promise<T> {
    const next = this.serial.then(action); this.serial = next.catch(() => {}); return next;
  }
  write(value: Recovery): Promise<void> {
    const copy = structuredClone(value);
    return this.queue(async () => {
      const target = this.filename(copy.view_id);
      await fs.mkdir(this.directory, { recursive: true });
      if ((await fs.lstat(this.directory)).isSymbolicLink()) throw new Error("Recovery directory cannot be a symbolic link");
      const bytes = Buffer.from(JSON.stringify(copy));
      let total = bytes.length, count = 1;
      for (const entry of await fs.readdir(this.directory, { withFileTypes: true })) {
        if (!entry.name.endsWith(".json") || entry.name === path.basename(target)) continue;
        if (!entry.isFile()) throw new Error("Recovery journal contains an unexpected file type");
        total += (await fs.stat(path.join(this.directory, entry.name))).size; count++;
      }
      if (total > this.maxBytes || count > this.maxDocuments) throw new Error("Recovery journal full; existing drafts retained");
      const temp = path.join(this.directory, `.pending-${randomUUID()}`);
      try {
        const handle = await fs.open(temp, "wx", 0o600);
        try { await handle.writeFile(bytes); await handle.sync(); } finally { await handle.close(); }
        await fs.rename(temp, target);
      } finally { await fs.rm(temp, { force: true }); }
    });
  }
  remove(id: string): Promise<void> { return this.queue(() => fs.rm(this.filename(id), { force: true })); }
  async read(id: string): Promise<Recovery | undefined> {
    await this.serial;
    const target = this.filename(id);
    try {
      const stat = await fs.lstat(target);
      if (!stat.isFile() || stat.isSymbolicLink() || stat.size > this.maxBytes) throw new Error("Invalid recovery record");
      const value = JSON.parse(await fs.readFile(target, "utf8")) as Recovery;
      if (value.view_id !== id || !Number.isSafeInteger(value.base_revision) || value.base_revision < 1
        || !value.configuration || typeof value.configuration !== "object") throw new Error("Invalid recovery contents");
      return value;
    } catch (error) { if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined; throw error; }
  }
}
