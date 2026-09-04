import test from "node:test";
import assert from "node:assert/strict";
import { mergeProviderResults, normalizeProviderResult } from "./analysis.js";

const base = { summary: "A game menu with a PLAY button", state: "menu", confidence: 92, elements: [{ label: "PLAY", x: 500, y: 600, width: 220, height: 100, confidence: 94 }], notes: ["The main action is clearly visible."] };

test("normalizes generic visible screen elements", () => {
  const result = normalizeProviderResult(base, "openrouter");
  assert.equal(result.summary, base.summary);
  assert.equal(result.state, "menu");
  assert.equal(result.elements[0].label, "PLAY");
  assert.equal(result.provider, "openrouter");
});

test("marks strongly consistent generic vision as verified", () => {
  const result = mergeProviderResults(normalizeProviderResult(base, "openrouter"), normalizeProviderResult({ ...base, confidence: 88 }, "gemini"));
  assert.equal(result.verified, true);
  assert.equal(result.verificationStatus, "VERIFIED");
  assert.equal(result.agreement, true);
  assert.equal(result.provider, "openrouter+gemini");
});

test("does not verify materially different screen states", () => {
  const result = mergeProviderResults(normalizeProviderResult(base, "openrouter"), normalizeProviderResult({ ...base, state: "gameplay", summary: "A racing track with a car" , confidence: 95 }, "gemini"));
  assert.equal(result.verified, false);
  assert.equal(result.verificationStatus, "UNVERIFIED");
  assert.equal(result.agreement, false);
});
