const FALLBACK_ANSWER = "I could not determine that from the available context.";

const VISUAL_PATTERNS = [
  /\b(screen|display|visible|see|look|shown|picture|image|button|menu|icon|text|page|window|game|level|player|object)\b/i,
  /\b(tap|click|press|hold|swipe|scroll|drag|drop|move|open|close|select|choose|type|enter|go back|go home|recents|notification|quick settings|zoom|pinch)\b/i,
  /\b(do|perform|execute|control|play|start|stop|launch|run)\b.*\b(it|this|that|game|app|screen|button|menu)\b/i
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
    ? "NO CURRENT SCREEN IMAGE IS SUPPLIED. Answer from conversation/general knowledge only. Never claim to see the screen."
    : visionFresh
      ? "CURRENT SCREEN: fresh image set supplied. Inspect it carefully before answering."
      : "CURRENT SCREEN: image set supplied but possibly stale. Use it cautiously and do not claim current facts without evidence.";
  return `You are GameVision, a fast, capable, active general-purpose AI companion with vision. You are game-agnostic: football, racing, action, puzzle, strategy, fighting, arcade, platform, board/card, apps, and ordinary Android UI are all valid contexts.

${visionState}

FAST MODE: reason internally, then answer directly and concisely. Do not waste tokens repeating the request or explaining obvious steps. Preserve conversation continuity: use recent turns, the user's goal, and the current screen together. When the screen is supplied, inspect the full image and detail regions and cross-check important facts. If a visual fact changed, trust the newest frame. Never invent a visual fact or claim an action succeeded unless Android reports success.

ACTIVE ASSISTANT: If the user asks for a supported device action, treat it as an execution request. The companion can route supported commands to autonomous control. Do not merely give instructions when the task can be executed. If a screen-dependent request has no frame, say that a fresh frame is needed.

RECENT CONVERSATION:
${historyText(history)}

CURRENT USER MESSAGE:
${request}`;
}

export function buildAutomationPrompt(goal, history = []) {
  const request = String(goal || "").trim().slice(0, 1200);
  return `You are GameVision's fast, precise, general-purpose visual-control planner. The user explicitly authorized autonomous control. You are NOT specialized for football, another game genre, or a particular app.

Return exactly ONE next action. Android executes it, captures a newer screen, and asks again. Continue until the goal is complete; do not stop just because the task needs many actions.

VISION FIRST: inspect the full current screen plus all supplied detail regions. Reconcile them before choosing coordinates. Use recent context to remember the goal and previous actions, but the newest screen is authoritative for what is currently visible.

SUPPORTED TOUCH: TAP, DOUBLE_TAP, LONG_PRESS, SWIPE, DRAG, PINCH_IN, PINCH_OUT, TWO_FINGER_SWIPE, TYPE_TEXT, WAIT.
GLOBAL ACTIONS: BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS.
SYSTEM/APP ACTIONS: OPEN_APP launches a launchable installed Android app by its user-visible name, regardless of whether its icon is currently visible. Prefer OPEN_APP for requests such as open YouTube, launch WhatsApp, start Chrome, or open Settings when the requested app is installed. Do not simulate an icon tap for an app-launch request.

Coordinates use full-screen 0..1000 x/y. Never invent coordinates. Use only visible targets. For TYPE_TEXT, give exact text and require a visible/focused editable field. Use WAIT for animations/transitions. If the target is not visible, navigate or scroll to find it rather than stopping prematurely.

DECISION RULES: choose the smallest reliable action that advances the user's goal. Prefer deterministic system/app actions over visual taps when they are available. After every action, expect a new frame and re-evaluate the whole screen. Keep state through the conversation and action history. STOP only when the goal is complete, the Android capability is unavailable, the screen is genuinely blocked/unexpected, or there is insufficient visual evidence to act safely.

SPEED: For time-critical game interactions, choose a single direct gesture rather than unnecessary planning or WAIT. Do not add delays unless the current UI needs them.

CONFIDENCE: provide your confidence in the selected action. Prefer >=70 when evidence is adequate; if uncertain, re-check the current screen instead of guessing.

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
  const allowed = new Set(["TAP", "DOUBLE_TAP", "LONG_PRESS", "SWIPE", "DRAG", "PINCH_IN", "PINCH_OUT", "TWO_FINGER_SWIPE", "TYPE_TEXT", "WAIT", "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS", "OPEN_APP", "STOP"]);
  const type = allowed.has(String(raw?.type || "").toUpperCase()) ? String(raw.type).toUpperCase() : "STOP";
  const number = (value) => Math.min(1000, Math.max(0, Number(value) || 0));
  const durationMs = Math.min(5000, Math.max(100, Number(raw?.durationMs) || 600));
  const waitMs = Math.min(10000, Math.max(0, Number(raw?.waitMs) || 800));
  const confidence = Math.min(100, Math.max(0, Number(raw?.confidence) || 0));
  const action = { type, x: number(raw?.x), y: number(raw?.y), x2: number(raw?.x2), y2: number(raw?.y2), text: typeof raw?.text === "string" ? raw.text.slice(0, 1000) : "", durationMs, waitMs, reason: typeof raw?.reason === "string" ? raw.reason.trim().slice(0, 400) : "", confidence, verify: raw?.verify !== false, stopReason: typeof raw?.stopReason === "string" ? raw.stopReason.trim().slice(0, 400) : "" };
  if (confidence < 60 && type !== "STOP") action.type = "STOP";
  return action;
}

export { FALLBACK_ANSWER };
