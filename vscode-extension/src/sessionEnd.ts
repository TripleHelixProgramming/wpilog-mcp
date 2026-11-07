/**
 * A window's server sessions end when the window closes or reloads, and the window waits for that,
 * within a bound. An ending it does not wait for can be cut off as the editor shuts the extension
 * down; the server then keeps the window's views attached until the session expires an hour later,
 * and a reopened window cannot attach them. Each ending stops new requests before it ends its
 * session, so no request reaches the server after it.
 */

/** How long a closing window waits for its sessions to end, inside the editor's shutdown allowance. */
export const SESSION_END_MS = 3_000;

/** Waits for every ending, at most `boundMs`; true when all of them finished in time. A failed
 * ending counts as finished: the server ends what it cannot reach by its own expiry. */
export async function endWithin(endings: readonly Promise<unknown>[], boundMs: number): Promise<boolean> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const late = new Promise<boolean>(resolve => { timer = setTimeout(() => resolve(false), boundMs); });
  try {
    return await Promise.race([Promise.allSettled(endings).then(() => true), late]);
  } finally {
    clearTimeout(timer);
  }
}
