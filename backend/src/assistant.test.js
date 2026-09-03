import test from "node:test";
import assert from "node:assert/strict";
import { buildAssistantPrompt, buildAutomationPrompt, normalizeAction, normalizeAssistantReply } from "./assistant.js";

test("assistant prompt is generic and includes recent conversation", () => {
  const prompt = buildAssistantPrompt("What should I tap?", [
    { role: "user", content: "Open the game" },
    { role: "assistant", content: "The game screen is visible." }
  ]);
  assert.match(prompt, /general-purpose conversational visual assistant/i);
  assert.match(prompt, /OPEN THE GAME/i);
  assert.match(prompt, /football/i);
});

test("automation prompt is game agnostic and one-step", () => {
  const prompt = buildAutomationPrompt("Play this game for me.");
  assert.match(prompt, /NOT specialized for football/i);
  assert.match(prompt, /exactly ONE next action/i);
  assert.match(prompt, /TAP/);
  assert.match(prompt, /SWIPE/);
});

test("normalizes assistant reply without inventing missing text", () => {
  assert.deepEqual(normalizeAssistantReply({ answer: "I can see the button.", confidence: 91 }), { answer: "I can see the button.", confidence: 91 });
  assert.deepEqual(normalizeAssistantReply({ answer: "", confidence: 120 }), { answer: "I could not determine that from the available context.", confidence: 100 });
});

test("forces uncertain automation decisions to STOP", () => {
  assert.equal(normalizeAction({ type: "TAP", x: 100, y: 200, confidence: 40 }).type, "STOP");
  assert.equal(normalizeAction({ type: "SWIPE", x: 100, y: 200, x2: 700, y2: 200, confidence: 90 }).type, "SWIPE");
});
