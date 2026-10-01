# Development

How to build, test, and change wpilog-mcp. [ARCHITECTURE.md](ARCHITECTURE.md) explains how the code is organized and why it is designed the way it is; read its [design principles](ARCHITECTURE.md#design-principles) before changing a tool.

## Requirements

- JDK 17 or newer; the WPILib JDK is recommended (see [STANDALONE.md](STANDALONE.md#requirements) for where WPILib puts it). `./gradlew` uses `JAVA_HOME`, or `java` on your `PATH`.
- [Node.js](https://nodejs.org/) 20, the version CI uses (`npm` on your `PATH`), only to build or test the VS Code extension.

## Building

```bash
./gradlew build          # Full build with tests
./gradlew shadowJar      # Build the self-contained JAR only
./gradlew install        # Build and install to ~/.wpilog-mcp/
```

The JAR is `build/libs/wpilog-mcp-{version}-all.jar`. `install` sets up the [standalone install](STANDALONE.md#install) from your build.

## Testing

```bash
./gradlew test
```

This runs every test that needs nothing outside the repository, in about a minute. Three rules apply to every change:

- A fix comes with a regression test that fails without the fix. Run the test against the old code and watch it fail before you trust it.
- A test of a number checks the number, against an answer worked out independently of the server. That a call succeeded proves nothing about what it returned.
- When you change a check, plant the bug it is meant to catch and see the check fail.

### What `./gradlew test` covers

Tests are in `src/test/java`, in the same packages as the code they test, plus a few test-only packages: `fixtures`, `conformance`, `golden`, `docs`, and `integration`.

- Fixture logs: about 20 small logs, one per logging convention (AdvantageKit match and practice logs, plain WPILib, swerve module states as an array and per module, vision templates, entries that only look like vision data, a team's own structs, a CANivore, alerts, a truncated log, and more). They are written at test time by a small WPILOG writer in pure Java, and their values are simple functions of time, so the right answer to any statistic is known exactly. None is committed.
- Tool tests: each tool's behavior and its edge cases (empty and single-sample entries, NaN and infinite values, duplicate timestamps, missing DriverStation data, and bad arguments), on the fixtures and on small logs that the tests build in memory.
- Conformance sweep: every tool is called with arguments built from its schema, on every fixture if it reads a log. Each result must follow the [result contract](TOOLS.md#result-contract-success-status-and-related-fields): no internal error, no NaN, no silent success (a success whose content is only zeros, `false`, and empty lists counts as silent), `inputs` on every successful result that read a log, true totals for shortened lists, a note on every result computed from a log that was not read to its end, and every output the tool's description names. The output must also be deterministic: each call on a log is repeated with the log's entries reversed and twice shuffled, and must give the same result (the REV log tools are exempt, because they depend on the synchronization done when the log was loaded). Tools whose needed parameters are optional in their schema get argument variants that reach their real analysis. `src/test/resources/conformance/known-failures.txt` is a ratchet: a violation not listed there fails the build, and so does a listed one that no longer occurs. It should stay empty. The report of every call is `build/reports/conformance/report.txt`.
- Differential check: the conformance sweep shows that the tools keep their contract, not that a number is right, so this check reads each fixture with a second WPILOG reader, written from the format specification alone and sharing no code with the server or with WPILib's reader, and compares the time range, every entry's type and sample count, the statistics of the most-sampled numeric entries and of entries holding NaN, and the enabled windows. The server may set records aside only where the second reader sees damage itself.
- Claim checks: the documentation and the tool descriptions are checked against the code. Each tool's schema is compared with the parameters its code reads, the parameters in [TOOLS.md](TOOLS.md) with the schemas, and the tool table in the README and the catalog in `get_server_guide` with the registered tools. TOOLS.md must hold every tool, under the server's category for it. TOOL_RESPONSES.md is generated from logs a contributor may not have, so it may lack a tool that was just added, but it may not misplace a tool or hold one the server does not have, and the scenarios file it is generated from must have a call for every tool. The reasoning guidance sent to agents may name only tools that exist and must fit its size limit.
- Process tests: some behavior can only be seen from outside, so the tests start the server in a fresh JVM to check its startup configuration and logging, and run the one-line installers against a stand-in for GitHub in a scratch home folder: the shell installer everywhere but Windows, and the PowerShell installer wherever PowerShell is installed, which includes the Windows CI job.
- Version checks: the extension's version must equal the project version, and no comment in the source may date a change to a release later than the current one.
- Build file check: the stress test tasks, which nothing else runs, must build the test classes first and fail the build when a test fails.

CI runs `./gradlew test shadowJar` on Linux and Windows, and compiles the extension.

### Tests on real logs

These are opt-in, because the logs are not in the repository. Each is selected by its test package and switched on by a property:

```bash
# The real-log suites, on a directory of logs
./gradlew test --tests '*.conformance.*' -PconformanceLogDir=/path/to/logs [-PconformanceMaxLogs=N] [-PconformanceTools=a,b]

# Golden values from one known log
./gradlew test --tests '*.golden.*' -PgoldenLog=/path/to/akit_26-09-30_00-10-26.wpilog
```

- Real-log conformance sweep: the fixture sweep's checks (all but the comparison of each description with its outputs) and argument variants, for every tool that reads a log, on every `.wpilog` under the directory (up to 5 levels deep), with the entries reversed for determinism. There is no ratchet, so any violation fails. The report, with the time of every call, is `build/reports/conformance/real-logs.txt`.
- Real-log differential check: the second reader against the server on every log. A file neither can read is counted, and a file only one can read is a finding. The report is `build/reports/conformance/differential.txt`.
- Claims on the live service: the server is started on its HTTP transport and called as a client would call it, and each answer is compared with a fact established independently of the tool under test. The facts are about Team 2363's sample logs; with other logs, a claim whose precondition isn't met is reported as not verifiable, never as passed. The report is `build/reports/conformance/claims.txt`.
- Golden checks: values from one practice log of Team 2363, computed separately with WPILib's Python log reader and NumPy.

With `-PconformanceLogDir`, the tests run with a 4 GB heap (the launcher's default) instead of the usual test heap; `-PconformanceHeap=8g` changes it.

Use your own team's logs. Logs that another team has deliberately published can be used for testing on your own computer, but keep them there: publishing a log is not permission to redistribute it, and no log belongs in this repository.

### Stress tests

```bash
./gradlew stressTest       # Both stress tests
./gradlew stdioStressTest  # In-process only
./gradlew httpStressTest   # Over the HTTP transport only
```

The stress tests exercise the server on real logs. The in-process test loads every log in the directory, runs the tools group by group on the first one, loads and evicts logs to stress the caches, and calls tools from several threads at once. The HTTP test drives the HTTP transport as several clients would: sessions, concurrent calls, batches, and protocol edge cases. Both hold the results of their sequential calls to the conformance checks. A failing test fails the build. A log directory that does not exist skips every test.

They take their settings from a `stresstest` server: the one in the file given with `-Pconfigpath=/path/to/config.yaml`, else in the first of `.wpilog-mcp.yaml` or `.wpilog-mcp.json` in the project root and `~/.wpilog-mcp/servers.yaml` or `servers.json` that has one (the file the installers write does). With no such server, they use `~/riologs` and team 2363. A TBA key in those settings (or in `TBA_API_KEY`) is used, so the TBA tools then call the live API. The heap is `WPILOG_MAX_HEAP`, or `4g`.

### Extension tests

```bash
cd vscode-extension
npm ci
npm test
```

These cover the extension's logic that needs no running VS Code: resolving log directories and settings, writing the Claude Code entry and configuration file, and removing the TBA key from those files.

## Changing or Adding a Tool

A tool is more than its code: agents read its description and schema, and several tests hold them to what the code does. When you add a tool or change what one takes or returns:

1. Follow the design principles. Return measurements with their sample counts, not conclusions. Find inputs through the shared signal resolver, never by a word in a name or by content alone: an entry is used when it is passed, follows a published convention, or is the only one of its type, and anything else that looks right is listed as a candidate. Say what was used, what was skipped, and what was cut short. A tool that cannot apply returns `not_applicable` or `no_match` with what it looked for, never an empty success.
2. Use the shared pieces. A tool that reads a log extends the log-reading base, which adds the `path` parameter and loads the log. Build the result with the shared response builder, take time ranges through the shared scope and window handling, and attach data quality where the result rests on statistics.
3. Write the description for an agent. Name every output, and say what the numbers do and don't show. A test fails when a description names an output that no result contains.
4. Register it and list it. Register the tool in the module with related tools, and add it to the catalog that `get_server_guide` and `suggest_tools` use.
5. Test it. Add tests for the tool's behavior and the edge cases above, on a fixture or on a log built in the test. The conformance sweep calls a new tool automatically. If the tool has a required parameter that the sweep has no argument rule for, the sweep fails and says so, and the rule has to be added. Give the tool argument variants if its real analysis needs parameters that its schema leaves optional. Add the tool to the stress tests.
6. Document it. Add a section to [TOOLS.md](TOOLS.md) (its parameter list must match the schema), an entry in the README's tool table, a call in `src/test/resources/tool-responses/scenarios.json`, and a line in [CHANGELOG.md](../CHANGELOG.md). If the change completes something in [IDEAS.md](IDEAS.md), update or remove that item.
7. Regenerate the response reference. [TOOL_RESPONSES.md](TOOL_RESPONSES.md) is generated by running the calls in the scenarios file against real logs:
   ```bash
   ./gradlew test --tests '*.docs.*' -PtoolResponsesLogDir=/path/to/logs
   ```
   The scenarios file names the three logs it reads, relative to that directory. They are Team 2363's and are not in the repository. Without them you cannot regenerate the file, and you do not have to: the checks accept a TOOL_RESPONSES.md that does not hold a new tool yet, as long as the scenarios file has a call for it. Say so in your pull request, and a maintainer will regenerate it. The capture fails when a tool has no call.

## Building the VS Code Extension

```bash
./gradlew bundleExtension    # Copy the server JAR into the extension (for local development)
./gradlew buildExtension     # Build, compile, and package the .vsix
./gradlew installExtension   # Build, package, and install into VS Code
```

`installExtension` installs into WPILib's VS Code for the current year when it is installed, and otherwise uses the `code` command. Close VS Code before running it, and restart it when done. The `.vsix` is written to `vscode-extension/wpilog-analyzer-{version}.vsix`.

The extension's version is the project version in `build.gradle`. Every extension task runs `./gradlew syncExtensionVersion`, which writes it into `vscode-extension/package.json` and `package-lock.json`. A development version such as `0.9.0-dev` installs over the previous release and is replaced by the release itself.

## Releasing

1. Set `version` in `build.gradle` (e.g. `0.9.0`) and run `./gradlew syncExtensionVersion`; a test fails until the extension's files match.
2. Regenerate [TOOL_RESPONSES.md](TOOL_RESPONSES.md), whose first lines carry the version (step 7 of [Changing or Adding a Tool](#changing-or-adding-a-tool)).
3. Move the `[Unreleased]` entries in [CHANGELOG.md](../CHANGELOG.md) under the new version.
4. Commit, then tag `v0.9.0` and push the tag. The release workflow (`.github/workflows/release.yml`) builds the server JAR and the `.vsix` under that version and attaches both to a GitHub release. It stops if the tag and `build.gradle` disagree. A tag with a suffix, such as `v0.9.0-dev`, is published as a pre-release; for one, skip steps 1 to 3 and tag the version `build.gradle` already carries.

## Contributing

1. Report bugs: open an issue with steps to reproduce, and the tool call and result if a tool gave a wrong answer.
2. Request features: open an issue describing your use case.
3. Submit pull requests: fork, make your changes, add tests, and open a PR.
