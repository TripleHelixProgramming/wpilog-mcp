import { test } from "node:test";
import * as assert from "node:assert/strict";
const { series } = require("../../media/chartSpec.js");
test("uPlot receives the server's histogram counts and exact scatter pairs", () => {
  assert.deepEqual(series({ version: 1, kind: "histogram", series: [{ name: "/Volts", unit: "V", bins: [{ low: 0, high: 2, count: 3 }, { low: 2, high: 4, count: 1 }] }] }),
    [{ name: "/Volts", unit: "V", x: [1, 3], y: [3, 1] }]);
  assert.deepEqual(series({ version: 1, kind: "scatter", pairs: [{ x: "/a", y: "/b", points: [[1, 3, 4], [2, 6, 5]] }] }), [{ name: "/b versus /a", x: [1, 2], y: [3, 6] }]);
  assert.throws(() => series({ version: 999 }), /version/);
});
