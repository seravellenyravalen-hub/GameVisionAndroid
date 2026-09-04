import test from "node:test";
import assert from "node:assert/strict";
import { buildAssistantPrompt, buildAutomationPrompt, isScreenDependentInstruction, normalizeAction, normalizeAssistantReply } from "./assistant.js";

test("assistant prompt is generic, visually grounded, and fast", () => {
  const prompt = buildAssistantPrompt("What should I tap?", [
    { role: "user", content: "Open the game" },
    { role: "assistant", content: "The game screen is visible." }
  ]);
  assert.match(prompt, /active general-purpose AI companion/i);
  assert.match(prompt, /OPEN THE GAME/i);
  assert.match(prompt, /football/i);
  assert.match(prompt, /FAST|CONCISE|DIRECT/i);
  assert.match(prompt, /current screen/i);
  assert.match(prompt, /remember|continuity/i);
});

test("assistant prompt defines game assistance modes", () => {
  const prompt = buildAssistantPrompt("Help me play this game");
  for (const mode of ["PLAY", "ASSIST", "WATCH", "GUIDE", "MIXED"]) assert.match(prompt, new RegExp(mode));
  assert.match(prompt, /only tap when I tell you/i);
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
  assert.match(prompt, /re-check the current screen/i);
});

test("automation prompt defines mode-aware control boundaries", () => {
  const prompt = buildAutomationPrompt("Assist me, but only tap when I tell you.");
  assert.match(prompt, /MIXED/i);
  assert.match(prompt, /only tap when the user explicitly authorizes/i);
  assert.match(prompt, /guide|explain/i);
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

test("normalizes expanded touch, text and global actions with low latency defaults", () => {
  assert.equal(normalizeAction({ type: "TAP", x: 100, y: 200, confidence: 40 }).type, "STOP");
  assert.equal(normalizeAction({ type: "TAP", x: 100, y: 200, confidence: 90 }).waitMs, 80);
  assert.equal(normalizeAction({ type: "DOUBLE_TAP", x: 500, y: 500, confidence: 90 }).waitMs, 80);
  assert.equal(normalizeAction({ type: "LONG_PRESS", x: 500, y: 500, confidence: 90 }).waitMs, 80);
  assert.equal(normalizeAction({ type: "OPEN_APP", text: "YouTube", confidence: 90 }).waitMs, 180);
  assert.equal(normalizeAction({ type: "SWIPE", x: 100, y: 200, x2: 700, y2: 200, confidence: 90 }).type, "SWIPE");
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
