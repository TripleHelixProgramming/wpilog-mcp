# Experimental pit server documentation

> **Experimental:** the pit server has limited controlled-shop evidence. These documents are
> retained for development and controlled shop evaluation, not as a recommendation for team use.
> Automated tests and simulation do not establish reliability on a real robot.

The main documentation focuses on analyzing recorded WPILOG and REV files and organizing
local stores. The material here describes the optional recording and remote integrations
already in the code. It is preserved for maintainers preparing further controlled-shop tests;
no feature has been enabled or disabled by moving these documents.

| Document | Contents |
|---|---|
| [Operations](OPERATIONS.md) | Experimental setup, verification order, shop checklist and troubleshooting |
| [Configuration](STANDALONE.md) | Capture, pulling, providers, gateway, remote stores, mirrors and metrics |
| [Extension](EXTENSION.md) | Remote sessions, following, mirrors, uploads and credentials |
| [Tools](TOOLS.md) | Live tools, pulled system text and capture-specific result fields |
| [Architecture](ARCHITECTURE.md) | Recording, clocks, thread ownership, transfer and provider design |
| [Testing](DEVELOPMENT.md) | Fixtures, replay, harnesses and what hardware must still prove |
| [Plan and decisions](PIT_SERVER_PLAN.md) | Specification, milestones and the implementation decision record |
| [Explorer integration](EXPLORER_REMOTE_SESSIONS.md) | Viewer plans and decisions concerning remote/live data |
| [Ideas](IDEAS.md) | Related proposals and the harness record |
| [Dashboard examples](metrics/compose.yaml) | Prometheus/Grafana configuration and its starter dashboard |
| [Recorded history in Grafana](../GRAFANA_PLAN.md) | Proposed plots of stored sessions beside the live dashboard, not implemented |

The ordinary guides remain in [doc/](../OPERATIONS.md). A passing simulation or CI run
must not be described as successful testing on a roboRIO, a team network or a real coprocessor.

## Additional registered tools

These tools remain registered in the server; moving their reference does not change the API.

| Category | Tools |
|---|---|
| **Query** | `search_system_logs` |
| **Live** | `list_sessions`, `get_latest_values`, `wait_for_change` |
