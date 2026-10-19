import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "node:path";
import { claudeArgs, claudeCommand, claudeCommandText, claudePitArgs, claudePitCommand, claudePitCommandText, findClaude, registerWithClaude, sameClaudeRegistration, claudeRegistrationFeedback } from "../claudeRegistration";
import { ignoreProjectFile, projectFileOffer, projectFileText } from "../projectFile";

for (const platform of ["linux", "darwin", "win32"] as const) {
  test(`Claude registration contains only the user-scope bridge on ${platform}`, () => {
    const launcher = platform === "win32" ? "C:\\My Home\\.wpilog-mcp\\bin\\wpilog-mcp.bat" : "/my home/.wpilog-mcp/bin/wpilog-mcp";
    const args = ["mcp", "add", "--scope", "user", "wpilog-analyzer", "--",
      ...(platform === "win32" ? ["cmd", "/c", launcher] : [launcher]), "connect", "http"];
    assert.deepEqual(claudeArgs(launcher, platform), args);
    const command = claudeCommand("claude", launcher, platform);
    assert.equal(command.command, platform === "win32" ? "cmd.exe" : "claude");
    assert.equal(command.windowsVerbatimArguments, platform === "win32");
    if (platform !== "win32") assert.deepEqual(command.args, args);
    else assert.deepEqual(command.args, ["/d", "/s", "/c",
      `""claude" "mcp" "add" "--scope" "user" "wpilog-analyzer" "--" "cmd" "/c" "${launcher}" "connect" "http""`]);
    const quoted = platform === "win32" ? args.map(word => `"${word}"`) : args.map(word => `'${word}'`);
    assert.equal(claudeCommandText(launcher, platform), `claude ${quoted.join(" ")}`);
    assert.ok(!/--logdir|--config|tba|secret/.test(JSON.stringify(command)));
  });
}

test("Claude lookup finds native executables or Windows shims and reports absence", () => {
  assert.equal(findClaude("/a:/my bin", "linux", p => p === "/my bin/claude", path.posix), "/my bin/claude");
  const expected = path.win32.join("C:\\My Bin", "claude.cmd");
  assert.equal(findClaude("C:\\Empty;C:\\My Bin", "win32", p => p === expected, path.win32), expected);
  assert.equal(findClaude("/a", "linux", () => false, path.posix), undefined);
});

test("project YAML is offered once only for own directories and never replaces an existing file", () => {
  const own = { logDirectory: "logs" };
  assert.equal(projectFileOffer(own, undefined, false), true);
  assert.equal(projectFileOffer({ additionalLogDirectories: ["sim"] }, undefined, false), true);
  assert.equal(projectFileOffer(own, undefined, true), false);
  assert.equal(projectFileOffer(own, "", false), false);
  assert.equal(projectFileOffer(own, undefined, false, "tracked"), false);
  assert.equal(projectFileOffer(own, undefined, false, "ignored"), true);
  assert.equal(projectFileOffer(own, undefined, false, "untracked"), true);
  assert.equal(projectFileOffer({ teamNumber: 2363 }, undefined, false), false);
  assert.equal(projectFileOffer({ logDirectory: " ", additionalLogDirectories: [7, ""] }, undefined, false), false);
});

test("project YAML carries only directories and a valid stated team, with portable escaping", () => {
  const own = { logDirectory: 'logs/#"first"', additionalLogDirectories: ["C:\\Logs\\sim", "", 8], teamNumber: 2363,
    tbaKey: "never-written", servers: { http: { port: 99 } } };
  const text = projectFileText(own);
  assert.deepEqual(text.split("\n"), ["# Directories leased by wpilog-mcp connect from this project.",
    'logdir: ["logs/#\\"first\\"","C:\\\\Logs\\\\sim"]', "team: 2363", ""]);
  assert.ok(!projectFileText({ logDirectory: "logs" }).includes("team:"));
  for (const teamNumber of [0, -1, NaN, 1.5]) {
    assert.ok(!projectFileText({ logDirectory: "logs", teamNumber }).includes("team:"));
  }
  assert.ok(!/never-written|servers|port|tba/.test(text));
  assert.equal(ignoreProjectFile("build/\r\n"), "build/\r\n.wpilog-mcp.yaml\r\n");
  assert.equal(ignoreProjectFile("/.wpilog-mcp.yaml\n"), "/.wpilog-mcp.yaml\n");
});


for (const platform of ["linux", "darwin", "win32"] as const) test(`pit registration is a second user-scope URL bridge on ${platform}`, () => {
  const launcher = platform === "win32" ? "C:\\Home\\wpilog-mcp.bat" : "/my home/wpilog-mcp";
  const url = "http://pit:2363/mcp";
  const args = ["mcp", "add", "--scope", "user", "wpilog-pit", "--",
    ...(platform === "win32" ? ["cmd", "/c", launcher] : [launcher]), "connect", "--url", url];
  assert.deepEqual(claudePitArgs(launcher, url, platform), args);
  const command = claudePitCommand("claude", launcher, url, platform);
  assert.equal(command.command, platform === "win32" ? "cmd.exe" : "claude");
  if (platform !== "win32") assert.deepEqual(command.args, args);
  const quoted = platform === "win32" ? args.map(word => `"${word}"`) : args.map(word => `'${word}'`);
  assert.equal(claudePitCommandText(launcher, url, platform), `claude ${quoted.join(" ")}`);
  assert.ok(!/--logdir|--config|tba|password/.test(JSON.stringify(command)));
});

for (const platform of ["linux", "darwin", "win32"] as const) {
  for (const remote of [false, true]) test(`another editor accepts the existing ${remote ? "pit" : "local"} bridge on ${platform}`, async () => {
    const launcher = platform === "win32" ? "C:\\My Home\\wpilog-mcp.bat" : "/my home/wpilog-mcp";
    const args = remote ? claudePitArgs(launcher, "http://127.0.0.1:2363/pit-mcp?origin=fixture", platform)
      : claudeArgs(launcher, platform);
    const name = remote ? "wpilog-pit" : "wpilog-analyzer";
    const bridge = platform === "win32" ? ["cmd", "/c", launcher] : [launcher];
    bridge.push(...(remote ? ["connect", "--url", "http://127.0.0.1:2363/pit-mcp?origin=fixture"] : ["connect", "http"]));
    let calls = 0;
    const result = await registerWithClaude("claude", args, platform, async command => {
      if (++calls === 1) return { ok: false, stdout: "", stderr: "" }; // No reliance on add's error wording.
      const get = platform === "win32" ? ["/d", "/s", "/c", `""claude" "mcp" "get" "${name}""`]
        : ["mcp", "get", name];
      assert.deepEqual(command.args, get);
      // CLI 2.1.296: health is independent of registration, and paths may contain spaces.
      return { ok: true, stdout: [name + ":", "  Scope: User config (available in all your projects)",
        "  Status: ✘ Failed to connect", "  Type: stdio", "  Command: " + bridge[0],
        "  Args: " + bridge.slice(1).join(" "), "  Environment:", ""].join("\r\n"), stderr: "" };
    });
    assert.equal(result.result, "existing", "An identical user registration must not warn in a second editor");
    assert.equal(calls, 2);
    const feedback = claudeRegistrationFeedback(result, name, "copyable command");
    assert.equal(feedback.remember, true);
    assert.equal(feedback.warning, undefined);
    assert.equal(feedback.information, undefined);
    assert.equal(feedback.copyCommand, undefined);
    assert.equal(feedback.output, `Claude Code: ${name} already uses this user-scope connection; remembered for this editor.`);
  });
}

test("a real CLI failure remains a failure, and a new registration uses one command", async () => {
  const args = claudeArgs("/fixture/launcher", "linux");
  let calls = 0;
  assert.deepEqual(await registerWithClaude("claude", args, "linux", async () => {
    calls++; return { ok: true, stdout: "Added", stderr: "" };
  }), { result: "added", addOutput: "Added" });
  assert.equal(calls, 1);
  assert.deepEqual(await registerWithClaude("claude", args, "linux", async () => ({ ok: false, stdout: "", stderr: "" })),
    { result: "failed", addOutput: "" });
  assert.deepEqual(await registerWithClaude("claude", args, "linux", async () => { throw new Error("CLI unavailable"); }),
    { result: "failed", addOutput: "" });
  const feedback = claudeRegistrationFeedback({ result: "failed", addOutput: "" }, "wpilog-analyzer", "copyable command");
  assert.equal(feedback.remember, false);
  assert.match(feedback.warning!, /registration failed/);
  assert.equal(feedback.copyCommand, "copyable command");
  assert.match(feedback.output!, /copyable command/);
});

test("registration identity requires all the CLI's own fields, not its health or an error string", () => {
  const args = claudeArgs("/fixture/launcher", "linux");
  const answer = "wpilog-analyzer:\n  Scope: User config (available in all your projects)\n  Type: stdio\n  Command: /fixture/launcher\n  Args: connect http\n  Environment:\n    SECRET: never-log-this\n";
  assert.equal(sameClaudeRegistration(answer, args), true);
  for (const changed of ["wpilog-analyzer", "User config", "stdio", "/fixture/launcher", "connect http"]) {
    assert.equal(sameClaudeRegistration(answer.replace(changed, "different"), args), false, changed);
  }
  assert.equal(sameClaudeRegistration(answer + "  Command: /another/launcher\n", args), false);
  assert.equal(sameClaudeRegistration("MCP server wpilog-analyzer already exists in user config", args), false);
  assert.equal(sameClaudeRegistration("", args), false);
});

for (const platform of ["linux", "darwin", "win32"] as const) {
  for (const remote of [false, true]) test(`a conflicting ${remote ? "pit" : "local"} bridge is preserved on ${platform}`, async () => {
    const launcher = platform === "win32" ? "C:\\My Home\\wpilog-mcp.bat" : "/my home/wpilog-mcp";
    const args = remote ? claudePitArgs(launcher, "http://127.0.0.1:2363/pit-mcp?origin=fixture", platform)
      : claudeArgs(launcher, platform);
    const name = remote ? "wpilog-pit" : "wpilog-analyzer";
    const text = remote ? claudePitCommandText(launcher, "http://127.0.0.1:2363/pit-mcp?origin=fixture", platform)
      : claudeCommandText(launcher, platform);
    const commands: string[][] = [];
    const result = await registerWithClaude("claude", args, platform, async command => {
      commands.push(command.args);
      return commands.length === 1 ? { ok: false, stdout: "", stderr: "" } : { ok: true, stdout: [
        `${name}:`, "  Scope: User config (available in all your projects)", "  Type: stdio",
        "  Command: another-launcher", "  Args: --credential must-not-be-echoed",
        "  Environment:", "    SECRET: must-not-be-echoed", "",
      ].join("\r\n"), stderr: "" };
    });
    assert.equal(result.result, "conflict", "A different registration needs its own warning, never replacement");
    const expected = platform === "win32" ? ["/d", "/s", "/c", `""claude" "mcp" "get" "${name}""`]
      : ["mcp", "get", name];
    assert.equal(commands.length, 2, "Only add and read-only get may run; never remove or replace");
    assert.deepEqual(commands[1], expected);
    const feedback = claudeRegistrationFeedback(result, name, text);
    assert.equal(feedback.remember, false, "A conflict must not mark this editor registered");
    assert.equal(feedback.information, undefined);
    assert.ok(feedback.warning?.includes(name));
    assert.match(feedback.warning!, /conflict/);
    assert.match(feedback.warning!, /left unchanged/);
    assert.ok(feedback.output?.includes(`claude mcp get ${name}`));
    assert.ok(feedback.output?.includes(`claude mcp remove ${name} --scope user`));
    assert.match(feedback.output!, /If you choose to replace an obsolete user entry/);
    assert.match(feedback.output!, /project or local entry/);
    assert.ok(feedback.output?.includes(text));
    assert.equal(feedback.copyCommand, text, "Copy Command copies only registration, never removal");
    assert.ok(!JSON.stringify(feedback).includes("must-not-be-echoed"));
  });
}

test("different arguments, scopes and transports are conflicts; unrecognized CLI replies still fail", async () => {
  const args = claudeArgs("/fixture/launcher", "linux");
  const answer = "wpilog-analyzer:\n  Scope: User config (available in all your projects)\n  Type: stdio\n  Command: /fixture/launcher\n  Args: connect http\n";
  const inspect = async (stdout: string) => {
    let calls = 0;
    return registerWithClaude("claude", args, "linux", async () => ++calls === 1
      ? { ok: false, stdout: "", stderr: "" } : { ok: true, stdout, stderr: "" });
  };
  for (const changed of [answer.replace("connect http", "connect another-server"),
    answer.replace("User config (available in all your projects)", "Project config (shared via .mcp.json)"),
    answer.replace("User config (available in all your projects)", "Local config (private to you in this project)"),
    answer.replace("  Type: stdio\n  Command: /fixture/launcher\n  Args: connect http",
      "  Type: http\n  URL: http://127.0.0.1:2363/mcp"),
    answer.replace("  Type: stdio\n  Command: /fixture/launcher\n  Args: connect http",
      "  Type: sse\n  URL: http://127.0.0.1:2363/sse")]) {
    assert.equal((await inspect(changed)).result, "conflict");
  }
  for (const malformed of ["", "MCP server wpilog-analyzer already exists in user config",
    answer.replace("  Scope:", "  Other:"), answer + "  Command: /another/launcher\n",
    answer.replace("stdio", "unknown"), answer.replace("  Args: connect http\n", "")]) {
    assert.equal((await inspect(malformed)).result, "failed", "Unknown CLI output must remain a real failure");
  }
});

for (const remote of [false, true]) {
  const name = remote ? "wpilog-pit" : "wpilog-analyzer";
  const args = remote ? claudePitArgs("/fixture/launcher", "http://127.0.0.1:2363/mcp", "linux")
    : claudeArgs("/fixture/launcher", "linux");
  for (const state of ["added", "existing", "failed", "conflict"] as const) {
    test(`${name} add diagnostics are shown for ${state} only as intended; get output stays private`, async () => {
      let calls = 0;
      const result = await registerWithClaude("claude", args, "linux", async () => {
        if (++calls === 1) return { ok: state === "added", stdout: "add stdout\n", stderr: "add stderr\n" };
        return { ok: state !== "failed", stdout: [
          `${name}:`, "  Scope: User config (available in all your projects)", "  Type: stdio",
          `  Command: ${state === "conflict" ? "/other/launcher" : "/fixture/launcher"}`,
          "  Args: " + args.slice(7).join(" "), "  Environment:", "    SECRET: private-get-stdout",
        ].join("\n"), stderr: "private-get-stderr" };
      });
      const feedback = claudeRegistrationFeedback(result, name, "registration command");
      assert.equal(calls, state === "added" ? 1 : 2);
      if (state === "existing") {
        assert.equal(feedback.output, `Claude Code: ${name} already uses this user-scope connection; remembered for this editor.`);
      } else {
        assert.ok(feedback.output?.startsWith("add stdout\nadd stderr\n")
          || feedback.output === "add stdout\nadd stderr",
        `The ${state} output must retain add stdout and stderr before instructions`);
        if (state === "failed" || state === "conflict") {
          assert.ok(feedback.output!.indexOf("add stderr") < feedback.output!.indexOf("registration command"));
        }
      }
      assert.ok(!JSON.stringify(result).includes("private-get-"), "The outcome must not retain get diagnostics");
      assert.ok(!JSON.stringify(feedback).includes("private-get-"), "No get text may reach the output channel");
    });
  }
  test(`${name} success retains its exact dev3 wording`, async () => {
    const result = await registerWithClaude("claude", args, "linux", async () => ({ ok: true, stdout: "", stderr: "" }));
    assert.equal(claudeRegistrationFeedback(result, name, "registration command").information,
      remote ? "Pit server registered as wpilog-pit at user scope. Restart existing Claude Code sessions to use it."
        : "WPILog Analyzer registered with Claude Code for your user account. Restart existing Claude Code sessions to use it.");
  });
  test(`${name} failure names its own registration and offers its command`, async () => {
    const result = await registerWithClaude("claude", args, "linux", async () => ({ ok: false, stdout: "", stderr: "" }));
    const feedback = claudeRegistrationFeedback(result, name, "registration command");
    assert.equal(feedback.warning, remote ? "Pit registration failed. Run: registration command"
      : "WPILog Analyzer: Claude Code registration failed; the output shows the command.");
    assert.equal(feedback.copyCommand, "registration command");
    assert.equal(feedback.remember, false);
  });
}
