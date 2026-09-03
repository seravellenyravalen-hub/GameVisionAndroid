import test from "node:test";
import assert from "node:assert/strict";
import { buildAssistantPrompt, buildAutomationPrompt, isScreenDependentInstruction, normalizeAction, normalizeAssistantReply } from "./assistant.js";

test("assistant prompt is generic and includes recent conversation", () => {
  const prompt = buildAssistantPrompt("What should I tap?", [
    { role: "user", content: "Open the game" },
    { role: "assistant", content: "The game screen is visible." }
  ]);
  assert.match(prompt, /active general-purpose AI companion/i);
  assert.match(prompt, /OPEN THE GAME/i);
  assert.match(prompt, /football/i);
});

test("automation prompt is game agnostic and one-step", () => {
  const prompt = buildAutomationPrompt("Play this game for me.");
  assert.match(prompt, /NOT specialized for football/i);
  assert.match(prompt, /exactly ONE next action/i);
  assert.match(prompt, /TAP/);
  assert.match(prompt, /SWIPE/);
  assert.match(prompt, /DOUBLE_TAP/);
  assert.match(prompt, /PINCH_IN/);
  assert.match(prompt, /PINCH_OUT/);
  assert.match(prompt, /TWO_FINGER_SWIPE/);
  assert.match(prompt, /TYPE_TEXT/);
  assert.match(prompt, /GLOBAL ACTIONS/i);
});

test("normalizes assistant reply without inventing missing text", () => {
  assert.deepEqual(normalizeAssistantReply({ answer: "I can see the button.", confidence: 91 }), { answer: "I can see the button.", confidence: 91 });
  assert.deepEqual(normalizeAssistantReply({ answer: "", confidence: 120 }), { answer: "I could not determine that from the available context.", confidence: 100 });
});

test("distinguishes ordinary conversation from screen-dependent requests", () => {
  assert.equal(isScreenDependentInstruction("How are you?"), false);
  assert.equal(isScreenDependentInstruction("What is this button?"), true);
  assert.equal(isScreenDependentInstruction("Tap the blue button"), true);
  assert.equal(isScreenDependentInstruction("Tell me what you remember from our chat"), false);
});

test("normalizes expanded touch, text and global actions", () => {
  assert.equal(normalizeAction({ type: "TAP", x: 100, y: 200, confidence: 40 }).type, "STOP");
  assert.equal(normalizeAction({ type: "SWIPE", x: 100, y: 200, x2: 700, y2: 200, confidence: 90 }).type, "SWIPE");
  assert.equal(normalizeAction({ type: "DOUBLE_TAP", x: 500, y: 500, confidence: 90 }).type, "DOUBLE_TAP");
  assert.equal(normalizeAction({ type: "PINCH_IN", x: 500, y: 500, x2: 700, y2: 500, confidence: 90 }).type, "PINCH_IN");
  assert.equal(normalizeAction({ type: "PINCH_OUT", x: 500, y: 500, x2: 700, y2: 500, confidence: 90 }).type, "PINCH_OUT");
  assert.equal(normalizeAction({ type: "TWO_FINGER_SWIPE", x: 300, y: 500, x2: 700, y2: 500, confidence: 90 }).type, "TWO_FINGER_SWIPE");
  assert.equal(normalizeAction({ type: "TYPE_TEXT", text: "hello", confidence: 90 }).text, "hello");
  assert.equal(normalizeAction({ type: "BACK", confidence: 95 }).type, "BACK");
  assert.equal(normalizeAction({ type: "HOME", confidence: 95 }).type, "HOME");
  assert.equal(normalizeAction({ type: "RECENTS", confidence: 95 }).type, "RECENTS");
  assert.equal(normalizeAction({ type: "NOTIFICATIONS", confidence: 95 }).type, "NOTIFICATIONS");
  assert.equal(normalizeAction({ type: "QUICK_SETTINGS", confidence: 95 }).type, "QUICK_SETTINGS");
});