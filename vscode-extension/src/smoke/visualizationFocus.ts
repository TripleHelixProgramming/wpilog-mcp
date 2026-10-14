/** Fixture-only focus ordering; native commands themselves must never be retried. */
export interface EditorFocus {
  reveal(): Promise<void>;
  active(): boolean;
  acknowledge(remainingMs: number): Promise<void>;
  now(): number;
  pause(ms: number): Promise<void>;
}

export async function revealStableEditor(focus: EditorFocus): Promise<void> {
  const deadline = focus.now() + 10000;
  while (focus.now() < deadline) {
    await focus.reveal();
    if (focus.now() >= deadline) break;
    if (focus.active()) {
      await focus.acknowledge(deadline - focus.now());
      // A renderer reply can precede a late active-group change. Require a fresh
      // quiet interval after each acknowledgement; losing focus restarts the reveal.
      const stableSince = focus.now();
      while (focus.now() < deadline && focus.active()) {
        if (focus.now() - stableSince >= 200) return;
        await focus.pause(Math.min(25, deadline - focus.now()));
      }
    }
    // Pace reveal retries and keep their total budget independent of the attempt count.
    if (focus.now() < deadline) await focus.pause(Math.min(100, deadline - focus.now()));
  }
  throw new Error("Timed out: stable active fixture view");
}
