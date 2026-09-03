const FALLBACK_ANSWER = "I could not determine that from the available context.";

export function normalizeHistory(history) {
  if (!Array.isArray(history)) return [];
  return history
    .filter((item) => item && (item.role === "user" || item.role === "assistant") && typeof item.content === "string")
    .map((item) => ({ role: item.role, content: item.content.trim().slice(0, 1000) }))
    .filter((item) => item.content)
    .slice(-12);
}

function historyText(history) {
  const turns = normalizeHistory(history);
  if (!turns.length) return "No previous conversation turns.";
  return turns.map((item) => `${item.role.toUpperCase()}: ${item.content}`).join("\n");
}

export function buildAssistantPrompt(instruction, history = []) {
  const request = String(instruction || "").trim().slice(0, 1000);
  return `You are GameVision, a general-purpose conversational visual assistant that can help a user understand and interact with the currently visible screen. You are NOT a football assistant and must not assume any particular game genre.

Use the provided screen images when the request concerns the screen, a game, an app, an object, text, a button, a menu, or an action that depends on what is visible. You may answer ordinary conversational questions from the conversation context, but never invent visual facts. If something needed for a screen-based answer is not clearly visible, say so.

Never use hidden game memory, undocumented APIs, private state, or outside information to claim something about the current screen. Be concise and directly useful.

RECENT CONVERSATION:
${historyText(history)}

CURRENT USER MESSAGE:
${request}`;
}

export function buildAutomationPrompt(goal, history = []) {
  const request = String(goal || "").trim().slice(0, 1200);
  return `You are GameVision's generic visual game-control planner. The user has explicitly enabled autonomous control. You are NOT specialized for football or any other genre. Infer the current interface only from the supplied screen images and the user's goal.

Return exactly ONE next action. Do not plan a long blind sequence. Observe the current screen, choose one useful action, let Android execute it, then the next request will contain a fresh screen.

Supported actions:
- TAP: tap one normalized screen coordinate.
- LONG_PRESS: hold one normalized coordinate for durationMs.
- SWIPE: move from (x,y) to (x2,y2) over durationMs.
- DRAG: same coordinate model as SWIPE, used when the interaction is a drag.
- WAIT: wait for a visible transition or animation.
- STOP: stop because the goal is complete, the screen is unexpected, evidence is insufficient, or continuing would be unsafe.

Coordinates MUST use the full-screen coordinate system from 0 to 1000 on both axes, regardless of image resolution. Do not invent coordinates. Only interact with controls/areas that are visibly supported by the current images.

The user goal may be broad. Work it out from the screen instead of assuming a game type. Prefer a single conservative action with confidence >= 70. If confidence is below 70, return STOP with a useful stopReason rather than guessing.

RECENT CONTEXT:
${historyText(history)}

USER GOAL:
${request}`;
}

export function normalizeAssistantReply(raw) {
  const answer = typeof raw?.answer === "string" && raw.answer.trim()
    ? raw.answer.trim().slice(0, 1600)
    : FALLBACK_ANSWER;
  const confidence = Math.min(100, Math.max(0, Number(raw?.confidence) || 0));
  return { answer, confidence };
}

export function normalizeAction(raw) {
  const allowed = new Set(["TAP", "LONG_PRESS", "SWIPE", "DRAG", "WAIT", "STOP"]);
  const type = allowed.has(String(raw?.type || "").toUpperCase()) ? String(raw.type).toUpperCase() : "STOP";
  const number = (value) => Math.min(1000, Math.max(0, Number(value) || 0));
  const durationMs = Math.min(5000, Math.max(100, Number(raw?.durationMs) || 600));
  const waitMs = Math.min(10000, Math.max(0, Number(raw?.waitMs) || 800));
  const confidence = Math.min(100, Math.max(0, Number(raw?.confidence) || 0));
  const action = {
    type,
    x: number(raw?.x),
    y: number(raw?.y),
    x2: number(raw?.x2),
    y2: number(raw?.y2),
    durationMs,
    waitMs,
    reason: typeof raw?.reason === "string" ? raw.reason.trim().slice(0, 400) : "",
    confidence,
    verify: raw?.verify !== false,
    stopReason: typeof raw?.stopReason === "string" ? raw.stopReason.trim().slice(0, 400) : ""
  };
  if (confidence < 70 && type !== "STOP") action.type = "STOP";
  return action;
}

export { FALLBACK_ANSWER };
