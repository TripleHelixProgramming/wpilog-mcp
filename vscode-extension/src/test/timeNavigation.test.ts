import { test } from "node:test";
import * as assert from "node:assert/strict";
import { historyRange } from "../visualization/timeNavigation";

test("time navigation rounds only the requested bounds to native microseconds", () => {
  assert.deepEqual(historyRange(1.1234564, 2.1234567), { mode: "fixed", start_sec: 1.123456, end_sec: 2.123457 });
  assert.deepEqual(historyRange(-2, -1, "earlier"), { mode: "fixed", start_sec: -2.5, end_sec: -1.5 });
  assert.deepEqual(historyRange(0, 0.000002, "zoom_in"), { mode: "fixed", start_sec: 0.000001, end_sec: 0.000002 });
  assert.deepEqual(historyRange(3, 3), { mode: "fixed", start_sec: 3, end_sec: 3 }, "an explicit instant remains valid");
});
test("panning cannot overflow either native clock boundary or silently widen a collapsed zoom", () => {
  for (const [start, end, action] of [[-9_007_199_254, -9_007_199_253, "earlier"], [0.0000001, 0.0000002, "zoom_in"], [0.0000004, 0.0000006, "earlier"]] as const)
    assert.throws(() => historyRange(start, end, action), /robot|history/);
});
