const FALLBACK_ANSWER = "I could not determine that from the available context.";

const VISUAL_PATTERNS = [
  /\b(screen|display|visible|see|look|shown|picture|image|button|menu|icon|text|page|window|game|level|player|object)\b/i,
  /\b(tap|click|press|hold|swipe|scroll|drag|drop|move|open|close|select|choose|type|enter|go back|go home|recents|notification|quick settings)\b/i,
  /\b(do|perform|execute|control|play|start|stop)\b.*\b(it|this|that|game|app|screen|button|menu)\b/i
];

export function isScreenDependentInstruction(instruction) {
  const request = String(instruction || "").trim();
  return VISUAL_PATTERNS.some((pattern) => pattern.test(request));
}

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

export function buildAssistantPrompt(instruction, history = [], hasVision = true, visionFresh = true) {
  const request = String(instruction || "").trim().slice(0, 1000);
  const visionState = !hasVision
    ? "NO CURRENT SCREEN IMAGE IS SUPPLIED. Answer from the conversation/general knowledge only. Do not claim to see the screen or invent visual facts."
    : visionFresh
      ? "A fresh current screen image set is supplied. Use it when relevant."
      : "A screen image set is supplied, but it may be stale. Use it only for facts that are still reasonably supported and do not claim it is current.";
  return `You are GameVision, an active general-purpose AI companion. You can converse naturally, reason about the user's request, use current screen evidence when available, and help carry out user-authorized tasks through the capabilities exposed by the Android companion. You are NOT a football assistant and must not assume any particular game genre.

${visionState}

Be active and useful. Do not respond with a generic refusal when the request can be answered from the available context. If the request requires a screen fact and no current screen is supplied, clearly say that a fresh screen is needed. If a task requires an action, explain the next useful step rather than pretending it was completed.

Never invent a visual fact, hidden game state, or completed action. Never claim success unless the platform reports success.

RECENT CONVERSATION:
${historyText(history)}

CURRENT USER MESSAGE:
${request}`;
}

export function buildAutomationPrompt(goal, history = []) {
  const request = String(goal || "").trim().slice(0, 1200);
  return `You are GameVision's active generic visual-control planner. The user has explicitly enabled autonomous control. You are NOT specialized for football or any other genre. Infer the current interface only from the supplied screen images and the user's goal.

Return exactly ONE next action. Do not plan a long blind sequence. Android will execute it, capture a newer screen, and ask you for the next action.

Supported coordinate actions:
- TAP: tap one normalized screen coordinate.
- DOUBLE_TAP: two taps at the same normalized coordinate.
- LONG_PRESS: hold one normalized coordinate for durationMs.
- SWIPE: move from (x,y) to (x2,y2) over durationMs.
- DRAG: same coordinate model as SWIPE, used when a held drag is needed.
- TYPE_TEXT: put the supplied text into the currently focused editable field using Android accessibility semantics.
- WAIT: wait for a visible transition or animation.

Supported global actions:
- BACK: Android Back.
- HOME: Android Home.
- RECENTS: Android recent-apps overview.
- NOTIFICATIONS: open notifications.
- QUICK_SETTINGS: open Quick Settings.

GLOBAL ACTIONS are only valid when the Android accessibility service reports that the requested system action is available.

Coordinates MUST use the full-screen coordinate system from 0 to 1000 on both axes, regardless of image resolution. Do not invent coordinates. Only interact with controls/areas that are visibly supported by the current images.

For TYPE_TEXT, provide the exact intended text in the text field. Use it only when the screen shows an editable field or the previous action has focused one.

The user goal may be broad. Work it out from the screen instead of assuming a game type. Prefer an action that advances the goal. Do not stop merely because the task is multi-step; continue one verified action at a time. Use STOP only when the goal is complete, the screen is genuinely blocked/unexpected, the requested capability is unavailable, or evidence is insufficient to choose a safe action.

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
  const allowed = new Set(["TAP", "DOUBLE_TAP", "LONG_PRESS", "SWIPE", "DRAG", "TYPE_TEXT", "WAIT", "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS", "STOP"]);
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
    text: typeof raw?.text === "string" ? raw.text.slice(0, 1000) : "",
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
