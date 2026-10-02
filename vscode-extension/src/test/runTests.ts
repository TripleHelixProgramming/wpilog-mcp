/**
 * Runs the compiled tests: every `*.test.js` beside this file, in one `node --test` process.
 *
 * `npm test` cannot name the files itself in a way every supported Node and shell accepts. Node 20
 * takes a directory but no wildcard, Node 21 and later reject a directory, Windows' shell does not
 * expand a wildcard, and with no argument Node 22 also runs the TypeScript sources. Listing the
 * files here works with all of them. Finding no tests is a failure, so a broken build cannot pass.
 */
import { spawnSync } from "node:child_process";
import { readdirSync } from "node:fs";
import { join } from "node:path";

const files = readdirSync(__dirname)
  .filter((name) => name.endsWith(".test.js"))
  .sort()
  .map((name) => join(__dirname, name));

if (files.length === 0) {
  console.error(`No compiled tests (*.test.js) in ${__dirname}`);
  process.exit(1);
}

const result = spawnSync(process.execPath, ["--test", ...files], { stdio: "inherit" });
if (result.error) {
  console.error(`Could not start node --test: ${result.error.message}`);
}
process.exit(result.status ?? 1);
