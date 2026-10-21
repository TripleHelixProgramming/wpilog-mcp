/** Where a native runner puts a run, kept outside the editor API so the rule is testable. */
import * as fs from "node:fs";
import * as path from "node:path";

/**
 * A new run folder in `base`. A runner writing under build/ keeps only its latest run: its
 * earlier `prefix` folders are removed first, since every run used to add one and nothing removed
 * them. Other entries in `base`, such as a plant's run.log, are left alone. A folder the caller
 * names is that caller's evidence (`keepEarlier`), and nothing in it is removed.
 */
export function runFolder(base: string, prefix: string, options: { keepEarlier?: boolean } = {}): string {
  fs.mkdirSync(base, { recursive: true });
  if (!options.keepEarlier) {
    for (const entry of fs.readdirSync(base, { withFileTypes: true })) {
      if (entry.isDirectory() && entry.name.startsWith(prefix)) fs.rmSync(path.join(base, entry.name), { recursive: true, force: true });
    }
  }
  return fs.mkdtempSync(path.join(base, prefix));
}
