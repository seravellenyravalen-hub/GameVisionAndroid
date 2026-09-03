import test from "node:test";
import assert from "node:assert/strict";
import { mergeProviderResults, normalizeProviderResult } from "./analysis.js";

const base = {
  homeTeam: "Home FC",
  awayTeam: "Away FC",
  homeScore: 2,
  awayScore: 1,
  minute: "67:12",
  event: "Goal",
  confidence: 92,
  notes: ["Scoreboard is clearly visible."]
};

test("normalizes a provider result into the GameVision shape", () => {
  const result = normalizeProviderResult({ ...base, confidence: 92 }, "openai");
  assert.equal(result.homeScore, 2);
  assert.equal(result.awayScore, 1);
  assert.equal(result.provider, "openai");
  assert.equal(result.confidence, 92);
});

test("marks matching providers as verified when visual evidence is strong", () => {
  const result = mergeProviderResults(
    normalizeProviderResult(base, "openai"),
    normalizeProviderResult({ ...base, confidence: 88 }, "gemini")
  );
  assert.equal(result.verified, true);
  assert.equal(result.verificationStatus, "VERIFIED");
  assert.equal(result.agreement, true);
  assert.equal(result.score, "2-1");
});

test("does not verify conflicting provider results", () => {
  const result = mergeProviderResults(
    normalizeProviderResult(base, "openai"),
    normalizeProviderResult({ ...base, homeScore: 3, confidence: 95 }, "gemini")
  );
  assert.equal(result.verified, false);
  assert.equal(result.verificationStatus, "UNVERIFIED");
  assert.equal(result.agreement, false);
});

test("falls back to the available provider", () => {
  const result = mergeProviderResults(normalizeProviderResult(base, "openai"), null);
  assert.equal(result.provider, "openai");
  assert.equal(result.verificationStatus, "LIKELY");
  assert.equal(result.verified, false);
});
