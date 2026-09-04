import test from "node:test";
import assert from "node:assert/strict";
import { FrameStore } from "./frameStore.js";

test("stores frames per account and returns the newest frame", () => {
  const store = new FrameStore({ maxAgeMs: 15000 });
  const first = store.put("user-a", [{ role: "full", data: "a" }]);
  const second = store.put("user-a", [{ role: "full", data: "b" }]);
  store.put("user-b", [{ role: "full", data: "other" }]);

  assert.equal(second.sequence > first.sequence, true);
  assert.equal(store.get("user-a").images[0].data, "b");
  assert.equal(store.get("user-b").images[0].data, "other");
});

test("freshness requires a newer sequence and an unexpired frame", () => {
  const store = new FrameStore({ maxAgeMs: 15000 });
  const saved = store.put("user-a", [{ role: "full", data: "a" }]);
  assert.equal(store.isFresh("user-a", saved.sequence - 1), true);
  assert.equal(store.isFresh("user-a", saved.sequence), false);
  assert.equal(store.status("missing").sequence, 0);
});
