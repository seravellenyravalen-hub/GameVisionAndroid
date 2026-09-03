import test from "node:test";
import assert from "node:assert/strict";
import { buildAssistantPrompt, normalizeAssistantReply } from "./assistant.js";

test("builds a grounded instruction prompt from the user's request", () => {
  const prompt = buildAssistantPrompt("Check the scoreboard and tell me the score.");
  assert.match(prompt, /visible screenshot/i);
  assert.match(prompt, /Check the scoreboard and tell me the score/);
  assert.match(prompt, /do not guess/i);
});

test("normalizes an assistant reply without inventing missing text", () => {
  assert.deepEqual(normalizeAssistantReply({ answer: "The score is 2-1.", confidence: 91 }), {
    answer: "The score is 2-1.",
    confidence: 91
  });
  assert.deepEqual(normalizeAssistantReply({ answer: "", confidence: 120 }), {
    answer: "I could not determine that from the visible screen.",
    confidence: 100
  });
});
