import { test } from "node:test";
import * as assert from "node:assert/strict";
import { EditorFocus, revealStableEditor } from "../smoke/visualizationFocus";

function fixture() {
  let now = 0, active = false, reveals = 0, acknowledgements = 0;
  const f = {
    onReveal: () => { active = true; },
    onAcknowledge: () => {},
    onPause: () => {},
    active: (value: boolean) => { active = value; },
    advance: (ms: number) => { now += ms; },
    state: () => ({ now, active, reveals, acknowledgements }),
    focus: {} as EditorFocus,
  };
  f.focus = {
    async reveal() { reveals++; f.onReveal(); },
    active: () => active,
    async acknowledge() { acknowledgements++; f.onAcknowledge(); },
    now: () => now,
    async pause(ms) {
      now += ms;
      // A missing deadline must fail this test rather than spin forever in microtasks.
      assert.ok(now <= 11000, "fixture focus exceeded its bounded wait");
      f.onPause();
    },
  };
  return f;
}

test("focus moving away at acknowledgement is revealed again before admission", async () => {
  const f = fixture();
  f.onAcknowledge = () => { if (f.state().acknowledgements === 1) f.active(false); };
  await revealStableEditor(f.focus);
  assert.equal(f.state().active, true);
  assert.equal(f.state().reveals, 2);
  assert.equal(f.state().acknowledgements, 2);
  assert.ok(f.state().now >= 200, "the acknowledged target must also remain active for 200 ms");
});
test("a late switch restarts the stable interval and is revealed again", async () => {
  const f = fixture(); let movedAt: number | undefined;
  f.onPause = () => {
    if (movedAt === undefined && f.state().now >= 75) { movedAt = f.state().now; f.active(false); }
  };
  await revealStableEditor(f.focus);
  assert.ok(movedAt !== undefined, "admission must wait through the delayed switch");
  assert.equal(f.state().active, true); assert.equal(f.state().reveals, 2);
  assert.ok(f.state().now >= movedAt + 200, "require a fresh uninterrupted stable interval");
});
test("a stable target requires 200 ms after its renderer acknowledgement", async () => {
  const f = fixture(); f.onAcknowledge = () => f.advance(150);
  await revealStableEditor(f.focus);
  assert.ok(f.state().now >= 350); assert.equal(f.state().reveals, 1);
});
test("focus that never becomes active fails within the single ten-second deadline", async () => {
  const f = fixture(); f.onReveal = () => f.active(false);
  await assert.rejects(revealStableEditor(f.focus), /Timed out/);
  assert.equal(f.state().now, 10000); assert.equal(f.state().acknowledgements, 0);
  assert.ok(f.state().reveals > 1, "an inactive target must be revealed again");
});
test("repeated focus loss cannot reset the overall deadline", async () => {
  const f = fixture(); f.onAcknowledge = () => f.active(false);
  await assert.rejects(revealStableEditor(f.focus), /Timed out/);
  assert.equal(f.state().now, 10000);
});
test("an acknowledgement arriving at the deadline cannot admit a command", async () => {
  const f = fixture(); f.onAcknowledge = () => f.advance(10000);
  await assert.rejects(revealStableEditor(f.focus), /Timed out/);
  assert.equal(f.state().reveals, 1);
});
