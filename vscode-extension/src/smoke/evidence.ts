/** CI runner facts, kept outside the editor API so isolation and completion are testable. */
import * as fs from "node:fs";
import * as path from "node:path";

export function checkComplete(result: { status?: string; checks?: string[] }): void {
  if (result.checks?.length !== 6 || result.status !== "ok") throw new Error("Editor exited without all six smoke checks");
}

/** Each run may append to its own log; it must never change a later plant's input. */
export function prepareFixture(source: string, scratch: string): string {
  const fixture = path.join(scratch, "fixture", path.basename(source));
  fs.mkdirSync(path.dirname(fixture), { recursive: true });
  fs.copyFileSync(source, fixture);
  fs.copyFileSync(path.join(path.dirname(source), "append.bin"), path.join(path.dirname(fixture), "append.bin"));
  return fixture;
}
