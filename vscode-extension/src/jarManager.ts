import * as fs from "fs";
import * as path from "path";

/**
 * Finds the wpilog-mcp fat JAR bundled with the extension.
 */
export function findJar(extensionPath: string): string | undefined {
  const bundledJar = path.join(extensionPath, "server", "wpilog-mcp-all.jar");
  if (fs.existsSync(bundledJar)) {
    return bundledJar;
  }
  return undefined;
}
