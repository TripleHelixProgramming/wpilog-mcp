import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import * as vm from "node:vm";
import { entriesViewPage } from "../explorer/entriesViewHtml";

type Handler = (event: any) => void;

/** Exercise the actual page events and DOM output, without requiring a VS Code instance. */
function fixture() {
  let activeElement: Element | undefined;
  class Element {
    textContent = "";
    className = "";
    title = "";
    value = "";
    disabled = false;
    tabIndex = -1;
    type = "";
    parent?: Element;
    children: Element[] = [];
    attributes = new Map<string, string>();
    listeners = new Map<string, Handler[]>();
    constructor(readonly tag: string) {}
    setAttribute(name: string, value: string) { this.attributes.set(name, value); }
    addEventListener(type: string, handler: Handler) {
      this.listeners.set(type, [...(this.listeners.get(type) ?? []), handler]);
    }
    append(...children: Element[]) {
      for (const child of children) { child.parent = this; this.children.push(child); }
    }
    replaceChildren(...children: Element[]) {
      for (const child of this.children) child.parent = undefined;
      this.children = [];
      this.append(...children);
    }
    contains(element?: Element): boolean {
      return element === this || this.children.some(child => child.contains(element));
    }
    focus() { activeElement = this; this.dispatch("focus"); }
    dispatch(type: string, extra: Record<string, unknown> = {}) {
      let stopped = false;
      const event = { target: this, preventDefault() {}, stopPropagation() { stopped = true; }, ...extra };
      let target: Element | undefined = this;
      while (target) {
        for (const listener of target.listeners.get(type) ?? []) listener(event);
        if (stopped || type === "focus") break;
        target = target.parent;
      }
    }
    text(): string { return this.textContent + this.children.map(child => child.text()).join(""); }
    find(predicate: (element: Element) => boolean): Element | undefined {
      return predicate(this) ? this : this.children.map(child => child.find(predicate)).find(Boolean);
    }
  }
  const elements = new Map(["filter", "grouping", "clear", "collapse-all", "status", "entries", "visualization-target"].map(id => [id, new Element(id)]));
  elements.get("grouping")!.value = "path";
  const messages: any[] = [];
  let receive: Handler = () => {};
  let nextTimer = 1;
  const timers = new Map<number, () => void>();
  const source = fs.readFileSync(path.join(__dirname, "..", "..", "media", "entries.js"), "utf8");
  vm.runInNewContext(source, {
    document: {
      getElementById: (id: string) => elements.get(id),
      createElement: (tag: string) => new Element(tag),
      get activeElement() { return activeElement; },
    },
    window: { addEventListener: (_type: string, listener: Handler) => { receive = listener; } },
    acquireVsCodeApi: () => ({ postMessage: (message: unknown) => messages.push(JSON.parse(JSON.stringify(message))) }),
    setTimeout: (fn: () => void, delay: number) => {
      assert.equal(delay, 150);
      const id = nextTimer++;
      timers.set(id, fn);
      return id;
    },
    clearTimeout: (id: number) => timers.delete(id),
  });
  const get = (id: string) => elements.get(id)!;
  const send = (message: Record<string, unknown>) => receive({ data: { grouping: "path", ...message } });
  const row = (label: string) => get("entries").find(element => element.attributes.get("role") === "treeitem" && element.attributes.get("aria-label") === label)!;
  return { get, send, row, messages, focus: () => activeElement, flush: () => {
    const pending = [...timers.values()]; timers.clear(); pending.forEach(fn => fn());
  } };
}

function entry(id: string, label = id, extra: Record<string, unknown> = {}) {
  return { id, label, kind: "entry", expandable: false, expanded: false, details: true, plotElements: false, ...extra };
}

function group(id: string, children: ReturnType<typeof entry>[], expanded = true) {
  return entry(id, id, { kind: "group", expandable: true, expanded, details: false, children });
}

test("inline filtering debounces, keeps typing focus, and never activates a signal", () => {
  const page = fixture();
  page.send({ type: "tree", generation: 1, filter: "", nodes: [entry("Voltage")] });
  page.get("filter").focus();
  page.get("filter").value = "Drive";
  page.get("filter").dispatch("input");
  page.get("filter").value = "Drive/Pose";
  page.get("filter").dispatch("input");
  assert.deepEqual(page.messages, [{ type: "ready" }]);
  assert.match(page.get("status").text(), /Filtering/);
  page.flush();
  assert.deepEqual(page.messages, [{ type: "ready" }, { type: "filter", value: "Drive/Pose", generation: 1 }]);
  // A late result must not replace the pending query or remove focus from its input.
  page.send({ type: "tree", generation: 2, filter: "Drive", nodes: [entry("wrong")] });
  assert.equal(page.get("filter").value, "Drive/Pose");
  assert.equal(page.get("entries").text().includes("wrong"), false);
  page.send({ type: "tree", generation: 3, filter: "Drive/Pose", nodes: [entry("Pose")] });
  assert.equal(page.focus(), page.get("filter"));
  assert.equal(page.get("entries").text().includes("Pose"), true);
  page.get("filter").dispatch("keydown", { key: "Enter" });
  assert.equal(page.messages.some(message => message.type === "activate"), false);
  page.get("clear").dispatch("click");
  assert.deepEqual(page.messages.at(-1), { type: "filter", value: "", generation: 3 });
  assert.equal(page.focus(), page.get("filter"));
});

test("grouping sends the pending search atomically and retains focus without plotting", () => {
  const page = fixture();
  page.send({ type: "tree", generation: 1, filter: "", nodes: [entry("Voltage")] });
  page.get("filter").value = "Drive";
  page.get("filter").dispatch("input");
  page.get("grouping").focus();
  page.get("grouping").value = "type";
  page.get("grouping").dispatch("change");
  page.flush();
  assert.deepEqual(page.messages, [
    { type: "ready" },
    { type: "grouping", value: "type", filter: "Drive", generation: 1 },
  ], "the cancelled debounce must not send a separate search");
  assert.equal(page.get("entries").attributes.get("aria-busy"), "true");
  page.send({ type: "loading", generation: 2, filter: "Drive", grouping: "type" });
  page.send({ type: "tree", generation: 2, filter: "Drive", grouping: "type", nodes: [group("double", [entry("Drive/Speed")])] });
  assert.equal(page.focus(), page.get("grouping"));
  assert.ok(page.row("Drive/Speed"));
  page.get("grouping").dispatch("keydown", { key: "Enter" });
  assert.equal(page.messages.some(message => message.type === "activate"), false);
});

test("grouping rejects delayed trees, loading, children and errors from the prior choice", () => {
  const page = fixture();
  page.send({ type: "tree", generation: 1, filter: "", nodes: [entry("Pose", "Pose", { expandable: true })] });
  page.row("Pose").dispatch("keydown", { key: "ArrowRight" });
  page.get("grouping").value = "type";
  page.get("grouping").dispatch("change");
  page.send({ type: "children", generation: 1, id: "Pose", nodes: [entry("stale field")] });
  page.send({ type: "error", generation: 1, message: "stale error" });
  page.send({ type: "loading", generation: 2, filter: "", grouping: "path" });
  page.send({ type: "tree", generation: 2, filter: "", grouping: "path", nodes: [entry("stale tree")] });
  assert.equal(page.get("grouping").value, "type");
  assert.equal(page.get("entries").text().includes("stale"), false);
  assert.equal(page.get("status").text().includes("stale"), false);
  page.get("grouping").value = "path";
  page.get("grouping").dispatch("change");
  assert.deepEqual(page.messages.at(-1), { type: "grouping", value: "path", filter: "", generation: 1 });
  page.send({ type: "loading", generation: 3, filter: "", grouping: "type" });
  page.send({ type: "tree", generation: 4, filter: "", grouping: "path", nodes: [entry("latest path")] });
  page.send({ type: "tree", generation: 3, filter: "", grouping: "type", nodes: [entry("stale type")] });
  assert.equal(page.get("grouping").value, "path");
  assert.ok(page.row("latest path"));
  assert.equal(page.get("entries").text().includes("stale"), false);
});

test("the first host message restores saved grouping and collapse all works within type groups", () => {
  const page = fixture();
  page.send({ type: "loading", generation: 1, filter: "Drive", grouping: "type" });
  assert.equal(page.get("grouping").value, "type");
  page.send({ type: "tree", generation: 1, filter: "Drive", grouping: "type", nodes: [group("double", [entry("Drive/Speed")])] });
  assert.ok(page.row("Drive/Speed"));
  page.get("collapse-all").dispatch("click");
  assert.equal(page.row("double").attributes.get("aria-expanded"), "false");
  assert.equal(page.get("entries").text().includes("Drive/Speed"), false);
  page.row("double").dispatch("keydown", { key: "ArrowRight" });
  assert.ok(page.row("Drive/Speed"));
  assert.equal(page.messages.some(message => message.type === "activate"), false);
  page.get("grouping").value = "path";
  page.get("grouping").dispatch("change");
  page.send({ type: "tree", generation: 2, filter: "Drive", grouping: "path", nodes: [group("Drive", [entry("Speed")])] });
  assert.ok(page.row("Speed"), "a new grouping starts with its own expansion state");
});

test("a log reset cancels an old grouping switch once without erasing a later choice", () => {
  const page = fixture();
  page.send({ type: "tree", generation: 1, filter: "Drive", grouping: "path", nodes: [entry("Drive")] });
  page.get("grouping").value = "type";
  page.get("grouping").dispatch("change");
  page.get("filter").value = "Drive/Pose";
  page.get("filter").dispatch("input");
  page.send({ type: "loading", generation: 2, filter: "", grouping: "path", resetFilter: true });
  assert.equal(page.get("grouping").value, "path", "the rejected old-log grouping cannot leave the new log stuck loading");
  assert.equal(page.get("filter").value, "");
  page.flush();
  assert.equal(page.messages.filter(message => message.type === "filter").length, 0);
  page.get("grouping").value = "type";
  page.get("grouping").dispatch("change");
  assert.deepEqual(page.messages.at(-1), { type: "grouping", value: "type", filter: "", generation: 2 });
  page.send({ type: "tree", generation: 2, filter: "", grouping: "path", resetFilter: true, nodes: [entry("old reply")] });
  assert.equal(page.get("grouping").value, "type", "the same reset cannot overwrite a subsequent grouping edit");
  assert.equal(page.get("entries").text().includes("old reply"), false);
  page.send({ type: "tree", generation: 3, filter: "", grouping: "type", nodes: [group("double", [entry("Voltage")])] });
  assert.ok(page.row("Voltage"));
});

test("collapse all clears every nested level and late lazy children cannot reopen it", () => {
  const page = fixture();
  const struct = entry("Pose", "Pose", { expandable: true });
  const roots = [group("Drive", [group("Odometry", [struct])])];
  page.send({ type: "tree", generation: 1, filter: "Drive/Odometry/Pose", nodes: roots });
  assert.equal(page.row("Pose").attributes.get("aria-level"), "3");
  page.row("Pose").dispatch("keydown", { key: "ArrowRight" });
  assert.deepEqual(page.messages.at(-1), { type: "expand", id: "Pose", generation: 1 });
  page.get("collapse-all").dispatch("click");
  assert.deepEqual(page.messages.at(-1), { type: "collapseAll", generation: 1 });
  const messagesBeforeHostCollapse = page.messages.length;
  page.send({ type: "collapseAll" });
  assert.equal(page.messages.length, messagesBeforeHostCollapse, "host collapse messages must not echo back");
  page.send({ type: "children", generation: 1, id: "Pose", nodes: [group("translation", [entry("x")])] });
  assert.equal(page.row("Drive").attributes.get("aria-expanded"), "false");
  assert.equal(page.get("entries").text().includes("Pose"), false);
  page.row("Drive").dispatch("keydown", { key: "ArrowRight" });
  assert.equal(page.row("Odometry").attributes.get("aria-expanded"), "false");
  page.row("Odometry").dispatch("keydown", { key: "ArrowRight" });
  assert.equal(page.row("Pose").attributes.get("aria-expanded"), "false");
  page.row("Pose").dispatch("keydown", { key: "ArrowRight" });
  assert.equal(page.row("translation").attributes.get("aria-expanded"), "false");
  assert.equal(page.messages.filter(message => message.type === "expand").length, 1, "reopening loaded fields needs no request");
  assert.equal(page.messages.some(message => message.type === "activate"), false);
});

test("host filter resets apply once and cancel an old pending search without erasing newer typing", () => {
  const page = fixture();
  page.send({ type: "tree", generation: 1, filter: "Drive", nodes: [entry("Drive")] });
  page.get("filter").focus();
  page.get("filter").value = "Drive/Pose";
  page.get("filter").dispatch("input");
  page.send({ type: "loading", generation: 2, filter: "", resetFilter: true });
  assert.equal(page.get("filter").value, "", "switching logs or workbench Clear resets the input");
  page.flush();
  assert.equal(page.messages.some(message => message.type === "filter"), false, "old delayed search is cancelled");
  page.get("filter").value = "Intake";
  page.get("filter").dispatch("input");
  page.send({ type: "tree", generation: 2, filter: "", resetFilter: true, nodes: [entry("old result")] });
  assert.equal(page.get("filter").value, "Intake", "the same reset cannot overwrite subsequent typing");
  assert.equal(page.get("entries").text().includes("old result"), false);
  page.flush();
  assert.deepEqual(page.messages.at(-1), { type: "filter", value: "Intake", generation: 2 });
  page.send({ type: "tree", generation: 3, filter: "Intake", nodes: [entry("Intake")] });
  assert.equal(page.get("entries").text().includes("Intake"), true);
  assert.equal(page.focus(), page.get("filter"));
});

test("filter requests carry the generation at editing time across rapid searches and later replies", () => {
  const page = fixture();
  page.send({ type: "tree", generation: 5, filter: "", nodes: [entry("Drive")] });
  page.get("filter").value = "Drive";
  page.get("filter").dispatch("input");
  page.flush();
  page.get("filter").value = "Drive/Pose";
  page.get("filter").dispatch("input");
  // A delayed reply to the first query must not reattribute this edit to its generation.
  page.send({ type: "loading", generation: 6, filter: "Drive" });
  page.flush();
  assert.deepEqual(page.messages.slice(1), [
    { type: "filter", value: "Drive", generation: 5 },
    { type: "filter", value: "Drive/Pose", generation: 5 },
  ]);
  page.send({ type: "tree", generation: 7, filter: "Drive/Pose", nodes: [entry("Pose")] });
  page.get("filter").dispatch("input");
  // Even a matching refresh arriving during debounce cannot change the captured context.
  page.send({ type: "tree", generation: 8, filter: "Drive/Pose", nodes: [entry("Pose")] });
  page.flush();
  assert.deepEqual(page.messages.at(-1), { type: "filter", value: "Drive/Pose", generation: 7 });
});

test("keyboard arrows traverse the visible hierarchy; activation and details are separate", () => {
  const page = fixture();
  page.send({ type: "tree", generation: 4, filter: "", nodes: [group("Drive", [entry("Speed"), entry("Angle")]), entry("Voltage")] });
  page.row("Drive").focus();
  page.row("Drive").dispatch("keydown", { key: "ArrowRight" });
  assert.equal(page.focus(), page.row("Speed"));
  page.row("Speed").dispatch("keydown", { key: "ArrowDown" });
  assert.equal(page.focus(), page.row("Angle"));
  page.row("Angle").dispatch("keydown", { key: "ArrowLeft" });
  assert.equal(page.focus(), page.row("Drive"));
  page.row("Drive").dispatch("keydown", { key: "End" });
  assert.equal(page.focus(), page.row("Voltage"));
  page.row("Voltage").dispatch("keydown", { key: "Enter" });
  assert.deepEqual(page.messages.at(-1), { type: "activate", id: "Voltage", generation: 4 });
  page.row("Voltage").find(element => element.title === "Show details")!.dispatch("click");
  assert.deepEqual(page.messages.at(-1), { type: "details", id: "Voltage", generation: 4 });
  assert.equal(page.messages.filter(message => message.type === "activate").length, 1);
  page.send({ type: "focusFilter" });
  assert.equal(page.focus(), page.get("filter"));
});

test("log switches reject stale trees, children and errors and preserve server messages as text", () => {
  const page = fixture();
  page.send({ type: "tree", generation: 1, filter: "", nodes: [entry("old", "old", { expandable: true })] });
  page.send({ type: "loading", generation: 2, filter: "" });
  page.send({ type: "tree", generation: 2, filter: "", nodes: [entry("new")] });
  page.send({ type: "tree", generation: 1, filter: "", nodes: [entry("stale tree")] });
  page.send({ type: "children", generation: 1, id: "old", nodes: [entry("stale child")] });
  page.send({ type: "error", generation: 1, message: "stale error" });
  assert.equal(page.get("entries").text().includes("new"), true);
  assert.equal(page.get("entries").text().includes("stale"), false);
  assert.equal(page.get("status").text().includes("stale"), false);
  page.send({ type: "error", generation: 2, message: "<img onerror=alert(1)>" });
  assert.equal(page.get("status").textContent, "<img onerror=alert(1)>");
  page.send({ type: "tree", generation: 3, filter: "", nodes: [entry("note", "No entries matched", { kind: "note", tooltip: "Try a shorter name" })] });
  assert.equal(page.get("entries").text(), "No entries matched");
});

test("the sidebar page loads only bundled script/style and includes its inline controls", () => {
  const html = entriesViewPage({ cspSource: "vscode-resource:", styleUri: "style.css", scriptUri: "entries.js", nonce: "nonce" });
  assert.match(html, /default-src 'none'; style-src vscode-resource:; script-src 'nonce-nonce'/);
  assert.doesNotMatch(html, /unsafe-inline|connect-src|https?:/);
  assert.match(html, /id="filter"[^>]*aria-label="Filter entries by name"/);
  assert.match(html, /<label for="grouping">Group by<\/label>/);
  assert.match(html, /<select id="grouping"[^>]*aria-controls="entries"/);
  assert.match(html, /<option value="path">Path<\/option>/);
  assert.match(html, /<option value="type">Data type<\/option>/);
  assert.match(html, /id="collapse-all"[^>]*aria-label="Collapse all"/);
});


test("Entries target label follows only the accepted source generation", () => {
  const page = fixture();
  page.send({ type: "tree", generation: 2, filter: "", nodes: [], target: "A · Plot" });
  assert.equal(page.get("visualization-target").text(), "A · Plot");
  page.send({ type: "tree", generation: 1, filter: "", nodes: [], target: "Hidden B · Plot" });
  assert.equal(page.get("visualization-target").text(), "A · Plot");
  page.send({ type: "loading", generation: 3, filter: "", target: "B · Table" });
  assert.equal(page.get("visualization-target").text(), "B · Table");
});
