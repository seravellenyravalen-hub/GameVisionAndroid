import test from "node:test";
import assert from "node:assert/strict";
import { parseFastCommand } from "./fastCommandRouter.js";

test("routes basic navigation without AI", () => {
  assert.deepEqual(parseFastCommand("go back"), { type: "BACK" });
  assert.deepEqual(parseFastCommand("go home"), { type: "HOME" });
  assert.deepEqual(parseFastCommand("open recent apps"), { type: "RECENTS" });
});

test("routes simple gestures and text", () => {
  assert.deepEqual(parseFastCommand("tap login"), { type: "TAP_TARGET", target: "login" });
  assert.deepEqual(parseFastCommand("double tap submit"), { type: "DOUBLE_TAP_TARGET", target: "submit" });
  assert.deepEqual(parseFastCommand("long press menu"), { type: "LONG_PRESS_TARGET", target: "menu" });
  assert.deepEqual(parseFastCommand("type hello world"), { type: "TYPE_TEXT", text: "hello world" });
  assert.deepEqual(parseFastCommand("wait 500 ms"), { type: "WAIT", waitMs: 500 });
});

test("does not steal complex natural-language tasks from the AI planner", () => {
  assert.equal(parseFastCommand("find the cheapest flight and book it"), null);
  assert.equal(parseFastCommand("what is 25 percent of 480"), null);
});
