/** User-scope registration contains only the bridge, so every project's cwd remains its own decision. */
import * as path from "path";
import { launcherCommand, LauncherCommand } from "./standaloneServer";

export function claudeArgs(launcher: string, platform: NodeJS.Platform): string[] {
  return ["mcp", "add", "--scope", "user", "wpilog-analyzer", "--",
    ...(platform === "win32" ? ["cmd", "/c", launcher] : [launcher]), "connect", "http"];
}

/** The CLI itself can be a .cmd shim too; quote each word once for the outer shell. */
export function claudeCommand(cli: string, launcher: string, platform: NodeJS.Platform): LauncherCommand {
  return launcherCommand(cli, claudeArgs(launcher, platform), platform);
}

export function claudeCommandText(launcher: string, platform: NodeJS.Platform): string {
  const quote = (word: string) => platform === "win32" ? `"${word.replace(/"/g, '""')}"`
    : `'${word.replace(/'/g, "'\\''")}'`;
  return ["claude", ...claudeArgs(launcher, platform).map(quote)].join(" ");
}

/** No shell lookup is needed to find an executable or a Windows command shim. */
export function findClaude(searchPath: string, platform: NodeJS.Platform, executable: (file: string) => boolean,
  pathApi: path.PlatformPath = path): string | undefined {
  const names = platform === "win32" ? ["claude.exe", "claude.cmd", "claude.bat"] : ["claude"];
  return searchPath.split(pathApi.delimiter).filter(Boolean)
    .flatMap(dir => names.map(name => pathApi.join(dir, name))).find(executable);
}


/** A remote registration uses the existing stdio bridge by URL, with no local directory/key lease. */
export function claudePitArgs(launcher: string, url: string, platform: NodeJS.Platform): string[] {
  return ["mcp", "add", "--scope", "user", "wpilog-pit", "--",
    ...(platform === "win32" ? ["cmd", "/c", launcher] : [launcher]), "connect", "--url", url];
}
export function claudePitCommand(cli: string, launcher: string, url: string, platform: NodeJS.Platform): LauncherCommand {
  return launcherCommand(cli, claudePitArgs(launcher, url, platform), platform);
}
export function claudePitCommandText(launcher: string, url: string, platform: NodeJS.Platform): string {
  const quote = (word: string) => platform === "win32" ? `"${word.replace(/"/g, '""')}"`
    : `'${word.replace(/'/g, "'\\''")}'`;
  return ["claude", ...claudePitArgs(launcher, url, platform).map(quote)].join(" ");
}

/** Native HTTP registration has no Java, launcher, daemon or laptop data dependency. */
export function claudeHttpArgs(name: string, url: string): string[] {
  const parsed = new URL(url);
  if (!/^[a-z0-9_-]+$/.test(name) || !["http:", "https:"].includes(parsed.protocol) || parsed.username || parsed.password || parsed.hash || parsed.search)
    throw new Error("Invalid direct MCP registration");
  return ["mcp", "add", "--scope", "user", name, "--transport", "http", url];
}
export function claudeHttpCommandText(name: string, url: string, platform: NodeJS.Platform): string {
  const quote = (word: string) => platform === "win32" ? `"${word.replace(/"/g, '""')}"` : `'${word.replace(/'/g, "'\\''")}'`;
  return ["claude", ...claudeHttpArgs(name, url).map(quote)].join(" ");
}

export type ClaudeRegistrationResult = "added" | "existing" | "conflict" | "failed";
export interface ClaudeReply { ok: boolean; stdout: string; stderr: string }
/** Only add diagnostics leave the runner; get can print another entry's credentials. */
export interface ClaudeRegistrationAttempt { result: ClaudeRegistrationResult; addOutput: string }

/** UI decisions are shared by both names and testable without starting an editor. */
export function claudeRegistrationFeedback(attempt: ClaudeRegistrationAttempt, name: string, command: string) {
  const { result, addOutput } = attempt;
  const pit = name === "wpilog-pit";
  const summary = result === "existing" ? `Claude Code: ${name} already uses this user-scope connection; remembered for this editor.`
    : result === "conflict" ? [
      `Claude Code registration conflict: ${name} has a different scope, command or arguments; left unchanged.`,
      `Inspect the entry: claude mcp get ${name}`,
      "Keep it if it is intentional. If you choose to replace an obsolete user entry, run:",
      `claude mcp remove ${name} --scope user`,
      `Then register this bridge: ${command}`,
      "A project or local entry must be handled in its own scope; removing a user entry does not remove it.",
      "Copy Command copies only the registration command, not the removal.",
    ].join("\n")
    : result === "failed" ? `${pit ? "Pit registration failed" : "Claude Code registration did not complete"}. Run: ${command}` : undefined;
  return {
    remember: result === "added" || result === "existing",
    // The duplicate-add refusal is expected for an identical entry, so only its one-line
    // confirmation is useful. Other outcomes retain the CLI's diagnostics before our guidance.
    output: [result === "existing" ? undefined : addOutput, summary].filter(Boolean).join("\n") || undefined,
    information: result === "added" ? (pit
      ? "Pit server registered as wpilog-pit at user scope. Restart existing Claude Code sessions to use it."
      : "WPILog Analyzer registered with Claude Code for your user account. Restart existing Claude Code sessions to use it.") : undefined,
    warning: result === "conflict" ? `WPILog Analyzer: Claude Code registration conflict for ${name}; the existing entry was left unchanged. See WPILog Analyzer output for manual instructions.`
      : result === "failed" ? (pit ? `Pit registration failed. Run: ${command}`
        : "WPILog Analyzer: Claude Code registration failed; the output shows the command.") : undefined,
    copyCommand: result === "failed" || result === "conflict" ? command : undefined,
  };
}

/** `mcp get` in CLI 2.1.296 exposes scope, transport, command and arguments, but no JSON mode.
 * Its connection-health result is independent of registration. Unknown output fails closed;
 * never infer ownership from add's error wording, or echo get's environment into the output. */
export function sameClaudeRegistration(output: string, addArgs: readonly string[]): boolean {
  return inspectClaudeRegistration(output, addArgs) === "existing";
}

function inspectClaudeRegistration(output: string, addArgs: readonly string[]): Exclude<ClaudeRegistrationResult, "added"> {
  const name = addArgs[4], bridge = addArgs.slice(addArgs.indexOf("--") + 1);
  const lines = output.split(/\r?\n/);
  if (lines.filter(line => line === `${name}:`).length !== 1) return "failed";
  const field = (label: string): string | undefined => {
    const values = lines.filter(line => line.startsWith(`  ${label}: `))
      .map(line => line.slice(label.length + 4));
    return values.length === 1 ? values[0] : undefined;
  };
  const scope = field("Scope"), type = field("Type"), command = field("Command"), args = field("Args");
  // Recognize an actual entry before offering conflict instructions. Malformed or newer CLI
  // output remains a failure, not evidence that an entry should be removed. Never echo secrets.
  if (!scope || (type === "stdio" ? !command || args === undefined
    : (type !== "http" && type !== "sse") || !field("URL"))) return "failed";
  return scope === "User config (available in all your projects)"
    && (addArgs[5] === "--transport" ? type === "http" && field("URL") === addArgs[7]
      : type === "stdio" && command === bridge[0] && args === bridge.slice(1).join(" "))
    ? "existing" : "conflict";
}

/** One CLI runner for both registrations; injected so subprocess outcomes need no editor. */
export async function registerWithClaude(cli: string, addArgs: string[], platform: NodeJS.Platform,
  run: (command: LauncherCommand) => Promise<ClaudeReply>): Promise<ClaudeRegistrationAttempt> {
  let addOutput = "";
  try {
    const added = await run(launcherCommand(cli, addArgs, platform));
    addOutput = [added.stdout.trim(), added.stderr.trim()].filter(Boolean).join("\n");
    if (added.ok) return { result: "added", addOutput };
    // add never replaces an existing entry. Inspect after failure, also covering two editors
    // racing to register the same bridge. Conflicts are explained, never replaced.
    const existing = await run(launcherCommand(cli, ["mcp", "get", addArgs[4]], platform));
    return { result: existing.ok ? inspectClaudeRegistration(existing.stdout, addArgs) : "failed", addOutput };
  } catch { return { result: "failed", addOutput }; }
}
