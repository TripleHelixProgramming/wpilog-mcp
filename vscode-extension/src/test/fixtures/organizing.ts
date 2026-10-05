// Recorded list_available_logs shape, using synthetic paths and manifest facts only.
import * as path from "path";
import { LogListing, ListedLog } from "../../explorer/logsTree";
export const store = path.resolve("fixture-store");
export const plain = path.resolve("fixture-plain");
export const practice = { id: "practice", name: "Practice", serial_number: null, basis: "stated" };
export const competition = { id: "SERIAL42", name: "Competition", serial_number: "SERIAL42", basis: "logged" };
const session = path.join(store, "robots", "practice", "sessions", "2026-01-10", "150000Z_TEST_Q2");
export const one: ListedLog = { path: path.join(session, "robot", "one.wpilog"), filename: "one.wpilog",
  friendly_name: "TEST Qualification 2", store, robot: practice, event: "TEST", match_type: "Qualification", match_number: 2,
  session: { id: "session-1", path: session, started_at: "2026-01-10T15:00:00Z", ended_at: "2026-01-10T15:02:00Z", start_basis: "logged_system_time" },
  revlogs: [{ path: path.join(session, "robot", "REV.revlog"), filename: "REV.revlog", size_bytes: 1024 }] };
export const two: ListedLog = { ...one, robot: competition, filename: "two.wpilog", friendly_name: "TEST Qualification 3", match_number: 3,
  path: path.join(store, "robots", "SERIAL42", "sessions", "2026-01-11", "160000Z", "robot", "two.wpilog"),
  session: { id: "session-2", path: path.join(store, "robots", "SERIAL42", "sessions", "2026-01-11", "160000Z"),
    started_at: "2026-01-11T16:00:00Z", ended_at: "2026-01-11T16:02:00Z", start_basis: "logged_system_time" }, revlogs: [] };
export const plainLog: ListedLog = { path: path.join(plain, "FRC_20260109_140000.wpilog"), filename: "FRC_20260109_140000.wpilog", friendly_name: "Shop", size_bytes: 40 };
export const listing: LogListing = { status: "ok", log_directories: [store], stores: [{ path: store, robots: [practice, competition] }],
  logs: [two, one], log_count: 2, has_more: false,
  unassigned: [{ store, path: path.join(store, "unassigned", "hash", "loose.wpilog"), kind: "wpilog" }],
  inbox: [
    { store, path: path.join(store, "inbox", "waiting.wpilog"), size: 40, state: "waiting" },
    { store, path: path.join(store, "inbox", "batch-1", "running.wpilog"), size: 80, state: "importing", stated_robot: "Practice" },
    { store, path: path.join(store, "inbox", "bad.txt"), size: 5, state: "refused", reason: "Unsupported file content" },
  ],
  unmanaged: [{ store, path: path.join(store, "stray.wpilog"), reason: "Not listed by a manifest; import this file to assign it" }] };
