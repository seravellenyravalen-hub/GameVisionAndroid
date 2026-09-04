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

test("exposes a stable server epoch so clients can detect a restarted frame store", () => {
  const store = new FrameStore({ maxAgeMs: 15000, epoch: "test-epoch" });
  const status = store.status("missing");
  assert.equal(status.serverEpoch, "test-epoch");
  const saved = store.put("user-a", [{ role: "full", data: "a" }]);
  assert.equal(store.status("user-a").serverEpoch, "test-epoch");
  assert.equal(saved.serverEpoch, "test-epoch");
});

test("rejects a frame timestamp that is too far in the future", () => {
  const store = new FrameStore({ maxAgeMs: 15000 });
  const future = store.put("user-a", [{ role: "full", data: "future" }], Date.now() + 120000);
  assert.equal(store.isFresh("user-a", future.sequence - 1), false);
  assert.equal(store.status("user-a").fresh, false);
});
