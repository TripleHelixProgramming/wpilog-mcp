import { test } from "node:test";
import * as assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
const root = resolve(__dirname, "../../..");
test("real follow broker check compiles both owners and runs on both extension CI platforms", () => {
  const gradle = readFileSync(resolve(root, "build.gradle"), "utf8");
  const workflow = readFileSync(resolve(root, ".github/workflows/ci.yml"), "utf8");
  assert.ok(/tasks\.register\('followBrokerTest', Exec\)/.test(gradle), "followBrokerTest task is required");
  const task = gradle.slice(gradle.indexOf("tasks.register('followBrokerTest'"), gradle.indexOf('// ==================== Build VS Code Extension'));
  for (const item of ["'testClasses'", "'compileExtension'", "WPILOG_FOLLOW_CLASSPATH", "WPILOG_FOLLOW_JAVA", "--test", "liveBrokerReal.test.js"]) assert.ok(task.includes(item), item);
  const step = workflow.match(/- name: Check shared follow broker against the real server\n[\s\S]*?(?=\n      - name:)/)?.[0];
  assert.ok(step); assert.ok(step.includes('run: ./gradlew followBrokerTest')); assert.ok(!step.includes('matrix.os =='));
});
