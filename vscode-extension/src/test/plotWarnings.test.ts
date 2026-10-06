// Exercise the webview's real response handler and chip rendering without a VS Code window.
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import * as vm from "node:vm";

class Element {
  textContent = "";
  className = "";
  title = "";
  style = {};
  children: Element[] = [];
  classList = {
    add() {
    },
    toggle() {
    },
  };
  addEventListener() {
  }
  append(...children: Element[]) {
    this.children.push(...children);
  }
  replaceChildren(...children: Element[]) {
    this.children = children;
  }
  text(): string {
    return this.textContent + this.children.map(child => child.text()).join("");
  }
}

function fixture(warning?: string) {
  const source = fs.readFileSync(path.join(__dirname, "..", "..", "media", "plot.js"), "utf8");
  const Plot = vm.runInNewContext(source + "\nPlot", { document: { createElement: () => new Element() } });
  Plot.seriesOf = () => ({ metadata: { entries: [{ name: "/Value", warning }] }, data: {}, bucketed: false });
  const plot = Object.create(Plot.prototype);
  const series = { name: "/Value", color: "red", pending: "request", warning: "old warning" };
  const pane = { chips: new Element(), series: [series] };
  plot.requests = new Map([[1, { series, key: "request", plan: {} }]]);
  plot.paneOf = () => pane;
  plot.rebuildChart = () => {};
  return { plot, pane, series };
}

test("the series chip shows the server's decode warning unchanged", () => {
  const warning = "Entry /Value: 1 of 2 records could not be decoded (bad double); results use the rest.";
  const { plot, pane } = fixture(warning);
  plot.onData({ requestId: 1, name: "/Value", bytes: new Uint8Array() });
  assert.ok(pane.chips.text().includes(warning));
});

test("a healthy response clears a previous decode warning", () => {
  const { plot, pane, series } = fixture();
  plot.onData({ requestId: 1, name: "/Value", bytes: new Uint8Array() });
  assert.equal(series.warning, null);
  assert.ok(!pane.chips.text().includes("old warning"));
});
