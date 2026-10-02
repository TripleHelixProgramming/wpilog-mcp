/**
 * The TBA API key field in the settings, and what to do with a key found there. Pure functions
 * (no VS Code API) so they can be tested on their own.
 *
 * The key is kept in VS Code's secret storage, never in a settings file. The `wpilog-mcp.tbaApiKey`
 * setting is a write-only field for entering it: the extension moves a key found there into secret
 * storage and clears the setting. A key in the user's own settings is the user's entry, so it
 * replaces the stored key. A key in a workspace's settings may have come from a teammate through
 * git (versions before 0.9 kept the key in the setting, and some projects committed it), so it is
 * stored only when no key is, and the user is told to revoke it if the file was shared.
 *
 * The Settings editor refreshes a field it has focus on only once the user leaves it, so right
 * after a move the field may still show the key that was just stored. A key pasted into it then is
 * appended to that text; the part after the stored key is the new key.
 */

/** The setting that holds a key on its way into secret storage. */
export const TBA_KEY_SETTING = "wpilog-mcp.tbaApiKey";

/**
 * How long the field must go unchanged before a key in it is moved, in milliseconds. The Settings
 * editor writes a text setting each time typing pauses for a second, and a key still being typed
 * must not be stored in pieces; a pasted key is written whole.
 */
export const TBA_KEY_QUIET_MS = 3000;

/** The setting's value in the user's and the workspace's settings, and the key already stored. */
export interface TbaKeyFound {
  /** The value in the user's settings, or undefined when it is not set there. */
  userValue?: unknown;
  /** The value in the workspace's settings, or undefined when it is not set there. */
  workspaceValue?: unknown;
  /** The key in secret storage, or undefined when none is stored. */
  storedKey?: string;
  /** How to name the workspace's settings file to the user, such as `.vscode/settings.json`. */
  workspaceSettingsName: string;
}

/** What to do: store a key or not, which settings to clear, and what to tell the user. */
export interface TbaKeyMove {
  /** The key to put into secret storage, or undefined to leave secret storage as it is. */
  store?: string;
  /** Remove the setting from the user's settings. */
  clearUser: boolean;
  /** Remove the setting from the workspace's settings. */
  clearWorkspace: boolean;
  /** What to tell the user, or undefined when there is nothing to say. */
  message?: string;
  /** Offer the TBA account page, where a key that a shared file exposed can be revoked. */
  offerRevoke: boolean;
}

/**
 * A setting's value as a key: trimmed text, or empty for anything that is not text. Text that is the
 * stored key with more after it is a key appended to a field still showing the stored one, so the
 * key is what follows.
 */
function asKey(value: unknown, stored: string): string {
  const text = typeof value === "string" ? value.trim() : "";
  return stored && text.length > stored.length && text.startsWith(stored)
    ? text.slice(stored.length).trim()
    : text;
}

/**
 * Decides what to do with the keys found in the setting. Every value found is cleared from the
 * settings, including an empty one or one that is not text, so nothing is left behind.
 */
export function planTbaKeyMove(found: TbaKeyFound): TbaKeyMove {
  const stored = found.storedKey ?? "";
  const user = asKey(found.userValue, stored);
  const workspace = asKey(found.workspaceValue, stored);
  const move: TbaKeyMove = {
    clearUser: found.userValue !== undefined,
    clearWorkspace: found.workspaceValue !== undefined,
    offerRevoke: false,
  };
  const notes: string[] = [];

  if (user) {
    if (user === stored) {
      notes.push("That TBA API key was already saved; it is cleared from your settings.");
    } else {
      move.store = user;
      notes.push(
        "TBA API key saved in VS Code's secret storage and cleared from your settings. " +
          "The server restarts to use it."
      );
    }
  }

  if (workspace) {
    // The key in use once this move is done
    const inUse = move.store ?? stored;
    let fate: string;
    if (!inUse) {
      move.store = workspace;
      fate = "it is now kept in VS Code's secret storage";
    } else if (workspace === inUse) {
      fate = "the same key is in VS Code's secret storage";
    } else if (move.store !== undefined) {
      fate = "it was not saved, and the key from your user settings is used";
    } else {
      fate = "it was not saved, and the key already in VS Code's secret storage is still used";
    }
    move.offerRevoke = true;
    notes.push(
      `A TBA API key was in this workspace's settings (${found.workspaceSettingsName}) in ` +
        `plaintext, and is removed from them; ${fate}. If that file was committed or shared, ` +
        "revoke the key on your TBA account page and set a new one."
    );
  }

  if (notes.length > 0) {
    move.message = `WPILog Analyzer: ${notes.join(" ")}`;
  }
  return move;
}

/**
 * The settings whose change restarts the server and rewrites Claude Code's configuration: every
 * setting the extension declares except the TBA API key field, which only feeds secret storage (a
 * stored key restarts the server by itself). With no declared settings to go by, the whole
 * `wpilog-mcp` section, so a change is never missed.
 *
 * @param manifest the extension's package.json
 */
export function settingsThatRestartTheServer(manifest: unknown): string[] {
  const configuration = (manifest as { contributes?: { configuration?: unknown } } | undefined)
    ?.contributes?.configuration;
  const sections = Array.isArray(configuration) ? configuration : [configuration];
  const keys = sections
    .flatMap((section) => {
      const properties = (section as { properties?: unknown } | undefined)?.properties;
      return properties && typeof properties === "object" ? Object.keys(properties) : [];
    })
    .filter((key) => key !== TBA_KEY_SETTING);
  return keys.length > 0 ? keys : ["wpilog-mcp"];
}
