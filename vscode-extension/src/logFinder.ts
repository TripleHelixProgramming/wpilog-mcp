import * as fs from "fs";
import * as path from "path";
import * as os from "os";
/** The first of ~/riologs, ~/wpilib/logs, ~/Documents/FRC/logs that exists. */
export function wellKnownLogDirectory(): string | undefined {
  const home = os.homedir();
  return [
    path.join(home, "riologs"),
    path.join(home, "wpilib", "logs"),
    path.join(home, "Documents", "FRC", "logs"),
  ].find((dir) => fs.existsSync(dir));
}
