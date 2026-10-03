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
- Differential check: the conformance sweep shows that the tools keep their contract, not that a number is right, so this check reads each fixture with a second WPILOG reader, written from the format specification alone and sharing no code with the server or with WPILib's reader, and compares the time range, every entry's type and sample count, the statistics of the most-sampled numeric entries and of entries holding NaN, and the enabled windows. It also recomputes domain answers from the raw records of the entries each tool says it used, by the rules [TOOLS.md](TOOLS.md) gives: the loop-time statistics of `analyze_loop_timing`, the roboRIO brownouts in `power_analysis` and `get_ds_timeline`, the per-bus figures of `analyze_can_bus`, the module speeds of `analyze_swerve`, and the mode of each enabled segment in `get_match_phases`. No expected value is stored, so the same checks run on any log. The server may set records aside only where the second reader sees damage itself.
- Claim checks: the documentation and the tool descriptions are checked against the code. Each tool's schema is compared with the parameters its code reads, the parameters in [TOOLS.md](TOOLS.md) with the schemas, and the tool table in the README and the catalog in `get_server_guide` with the registered tools. TOOLS.md must hold every tool, under the server's category for it. TOOL_RESPONSES.md is generated from logs a contributor may not have, so it may lack a tool that was just added, but it may not misplace a tool or hold one the server does not have, and the scenarios file it is generated from must have a call for every tool. The reasoning guidance sent to agents may name only tools that exist and must fit its size limit.
- Process tests: some behavior can only be seen from outside, so the tests start the server in a fresh JVM to check its startup configuration and logging, and run the one-line installers against a stand-in for GitHub in a scratch home folder: the shell installer everywhere but Windows, and the PowerShell installer wherever PowerShell is installed, which includes the Windows CI job.
- Version checks: the extension's version must equal the project version, and no comment in the source may date a change to a release later than the current one.
- Build file check: the stress test tasks, which nothing else runs, must build the test classes first and fail the build when a test fails.

CI runs `./gradlew test shadowJar` on Linux and Windows, and builds and tests the extension. On a push to `main` it also submits the Gradle dependencies to GitHub's dependency graph: GitHub does not read `build.gradle`, and without the submission Dependabot alerts cover only the extension's npm packages, not the libraries in the server JAR.

### Tests on real logs

These are opt-in, because the logs are not in the repository. Each is selected by its test package and switched on by a property:

```bash
# The real-log suites, on a directory of logs
./gradlew test --tests '*.conformance.*' -PconformanceLogDir=/path/to/logs [-PsimLogDir=/path/to/sim-logs] [-PconformanceMaxLogs=N] [-PconformanceTools=a,b]

# Golden values from known logs
./gradlew test --tests '*.golden.*' -PgoldenLog=/path/to/akit_26-09-30_00-10-26.wpilog -PgoldenMatchLog=/path/to/akit_cmptx_e4_sample.wpilog
```

- Real-log conformance sweep: the fixture sweep's checks (all but the comparison of each description with its outputs) and argument variants, for every tool that reads a log, on every `.wpilog` under the directory (up to 6 levels deep), with the entries reversed for determinism. There is no ratchet, so any violation fails. The report, with the time of every call, is `build/reports/conformance/real-logs.txt`.
- Real-log differential check: the second reader against the server on every log, with the domain answers above. A file neither can read is counted, and a file only one can read is a finding. The report is `build/reports/conformance/differential.txt`.
- Claims on the live service: the server is started on its HTTP transport and called as a client would call it, and each answer is compared with a fact established independently of the tool under test. The facts are about Team 2363's sample logs, and about simulated logs whose `MANIFEST.md` was written by a separate reader; `-PsimLogDir` names the directory holding those. With other logs, or without that directory, a claim whose precondition isn't met is reported as not verifiable, never as passed. The report is `build/reports/conformance/claims.txt`.
- Golden checks: values from a practice log of Team 2363 (`-PgoldenLog`) and from a 2026 championship elimination match that Team 4065 published under the MIT license (`-PgoldenMatchLog`, its `akit_cmptx_e4_sample.wpilog`), computed separately with WPILib's Python log reader and NumPy. Each property switches on its own set.

With `-PconformanceLogDir`, the tests run with a 4 GB heap (the launcher's default) instead of the usual test heap; `-PconformanceHeap=8g` changes it.

Use your own team's logs. Logs that another team has deliberately published can be used for testing on your own computer, but keep them there: publishing a log is not permission to redistribute it, and no log belongs in this repository.

### Stress tests

```bash
./gradlew stressTest       # Both stress tests
./gradlew stdioStressTest  # In-process only
./gradlew httpStressTest   # Over the HTTP transport only
```

The stress tests exercise the server on real logs. The in-process test loads every log in the directory, runs the tools group by group on the first one, loads and evicts logs to stress the caches, and calls tools from several threads at once. It runs the REV log tools on the first log that has a REV log, and there checks the disk cache: it synchronizes the log with the cache off, then twice with it on, the second time from the cache, and the REV log tools must give the same answers each time. The HTTP test drives the HTTP transport as several clients would: sessions, concurrent calls, batches, and protocol edge cases, with its REV log calls on that same log. Both hold the results of their sequential calls to the conformance checks. A failing test fails the build. A log directory that does not exist skips every test.

They take their settings from a `stresstest` server: the one in the file given with `-Pconfigpath=/path/to/config.yaml`, else in the first of `.wpilog-mcp.yaml` or `.wpilog-mcp.json` in the project root and `~/.wpilog-mcp/servers.yaml` or `servers.json` that has one (the file the installers write does). With no such server, they use `~/riologs` and team 2363. A TBA key in those settings (or in `TBA_API_KEY`) is used, so the TBA tools then call the live API. The heap is `WPILOG_MAX_HEAP`, or `4g`. The disk cache is never the one in those settings, but the tests' own (below).

### The disk cache in tests

Every test task, the stress tests included, uses its own disk cache folder, `build/test-disk-cache`, and empties it before the run. So a run starts with nothing cached, synchronizes every REV log itself, and never reads or writes your own cache. `-PtestCacheDir=/path/to/folder` uses that folder instead and keeps what is in it: a second run then starts from the results the first one saved. Pointed at a copy of a cache an older version wrote, it shows how this version treats that version's results. Use a copy, because the tests write to the folder.

### Extension tests

```bash
cd vscode-extension
npm ci
npm test
```

These cover the extension's logic that needs no running VS Code: resolving log directories and settings, writing the Claude Code entry and configuration file, removing the TBA key from those files, what happens to a key entered in the settings, and the settings' order and declarations. `npm test` compiles the extension and runs every compiled `*.test.js` through `src/test/runTests.ts`, which works with Node 20 and later on any platform; a new test file is picked up by its name.

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
4. Commit, then tag `v0.9.0` with an annotated tag (`git tag -a v0.9.0 -F notes.md`, whose first line is a title and whose body becomes the release notes, with the list of merged pull requests appended) and push the tag. The release workflow (`.github/workflows/release.yml`) builds the server JAR and the `.vsix` under that version and attaches both to a GitHub release. It stops if the tag and `build.gradle` disagree. A tag with a suffix, such as `v0.9.0-dev`, is published as a pre-release; for one, skip steps 2 and 3 and tag the version `build.gradle` carries. The repository's releases are immutable: a published release's files and tag cannot be changed, and a deleted release's tag name cannot be used again, so a release that went wrong gets a new version (`0.9.0-dev2`), never a moved tag.

## Contributing

1. Report bugs: open an issue with steps to reproduce, and the tool call and result if a tool gave a wrong answer.
2. Request features: open an issue describing your use case.
3. Submit pull requests: fork, make your changes, add tests, and open a PR.
