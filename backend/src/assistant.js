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
  return history.filter((item) => item && (item.role === "user" || item.role === "assistant") && typeof item.content === "string")
    .map((item) => ({ role: item.role, content: item.content.trim().slice(0, 1000) })).filter((item) => item.content).slice(-12);
}

function historyText(history) {
  const turns = normalizeHistory(history);
  if (!turns.length) return "No previous conversation turns.";
  return turns.map((item) => `${item.role.toUpperCase()}: ${item.content}`).join("\n");
}

export function buildAssistantPrompt(instruction, history = [], hasVision = true, visionFresh = true, toolContext = "") {
  const request = String(instruction || "").trim().slice(0, 1000);
  const visionState = !hasVision
    ? "NO CURRENT SCREEN IMAGE IS SUPPLIED. Do not claim to see pixels. A live Android accessibility/tool context may still describe the current app and interactive controls."
    : visionFresh ? "CURRENT SCREEN: fresh image set supplied. Inspect it carefully before answering." : "CURRENT SCREEN: image set supplied but possibly stale. Use it cautiously and do not claim current facts without evidence.";
  const tools = String(toolContext || "").trim().slice(0, 14000);
  return `You are GameVision, a fast, capable, active general-purpose AI companion with vision and live Android tools. You are game-agnostic: football, racing, action, puzzle, strategy, fighting, arcade, platform, board/card, apps, and ordinary Android UI are all valid contexts.

${visionState}

LIVE TOOL CONTEXT: ${tools ? "A current Accessibility tool-state snapshot is supplied below. Treat it as live device state for text, controls, packages, bounds, editability and scrollability. Do not confuse it with pixel vision." : "No live tool-state snapshot is supplied."}
${tools || ""}

ASSISTANCE MODES: PLAY performs authorized controls. ASSIST explains, coaches, and acts when requested. WATCH continuously observes and alerts without taking unrequested control. GUIDE provides objectives, navigation, puzzle/strategy guidance, and next steps. MIXED obeys natural boundaries such as "help me, but only tap when I tell you".

ACTIVE ASSISTANT: If the user asks for a supported device action, treat it as an execution request. Use Android tools instead of merely explaining. The assistant can chain actions and combine app launch, accessibility target actions, gestures, typing, global actions, and verification. If pixels are unavailable, use live tool context for deterministic UI actions; only require visual evidence when the requested action genuinely depends on pixels that accessibility cannot describe.

RECENT CONVERSATION:
${historyText(history)}

CURRENT USER MESSAGE:
${request}`;
}

export function buildAutomationPrompt(goal, history = [], toolContext = "") {
  const request = String(goal || "").trim().slice(0, 1200);
  const tools = String(toolContext || "").trim().slice(0, 14000);
  return `You are GameVision's fast, precise, general-purpose live device-control planner. The user explicitly authorized autonomous control. You are NOT specialized for one game or app.

CONTROL MODES: PLAY performs authorized controls. ASSIST can guide and act when requested. WATCH observes and alerts but must not take unrequested control. GUIDE explains objectives, menus, navigation, puzzles, and strategy. MIXED follows explicit boundaries.

Return exactly ONE next action when an action is authorized and supported. Android executes it, returns the tool result and current live state, and asks again. Continue until the goal is complete; do not stop just because the task needs many actions.

LIVE DEVICE TOOLS: The Accessibility tool context below is live device state and may be available even when screen capture is unavailable. Use it to identify packages, visible labels, clickable/editable/scrollable controls and their bounds. Prefer semantic target actions when a matching node exists. For visual-only game/canvas interactions, coordinates require a supplied current image; never invent them. Deterministic app/system actions do not require a screenshot.

${tools ? `CURRENT LIVE TOOL CONTEXT:\n${tools}` : "CURRENT LIVE TOOL CONTEXT: unavailable."}

SUPPORTED TOUCH: TAP, DOUBLE_TAP, LONG_PRESS, SWIPE, DRAG, PINCH_IN, PINCH_OUT, TWO_FINGER_SWIPE, TYPE_TEXT, WAIT.
TARGET TOOLING: TAP/DOUBLE_TAP/LONG_PRESS may use text/content-description targets through Android accessibility when a semantic target is known.
GLOBAL ACTIONS: BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS.
SYSTEM/APP ACTIONS: OPEN_APP launches a launchable installed Android app by its user-visible name regardless of icon visibility. Prefer OPEN_APP for requests such as open YouTube, launch WhatsApp, start Chrome, or open Settings. Do not simulate an icon tap for app launch.

Coordinates use full-screen 0..1000 x/y. Never invent coordinates. For TYPE_TEXT, give exact text and require a visible/focused editable field. Use WAIT for animations/transitions. If a semantic target exists, prefer it over guessed coordinates. If the target is not visible, navigate or scroll to find it.

DECISION RULES: choose the smallest reliable action that advances the goal. Combine tools across steps when needed: open app -> navigate -> tap target -> type -> submit -> verify. Prefer deterministic system/app/Accessibility actions over visual taps. After every action, re-evaluate returned live state and use a fresh image when one is available. STOP only when the goal is complete, Android capability is unavailable, the state is genuinely blocked, or evidence is insufficient for a safe action.

SPEED: For time-critical game interactions, choose one direct gesture. Do not add unnecessary delays.

CONFIDENCE: provide confidence. Use >=70 only when evidence supports the action. If uncertain, use a semantic tool action or WAIT/re-check rather than inventing coordinates.

RECENT CONTEXT:
${historyText(history)}

USER GOAL:
${request}`;
}

export function normalizeAssistantReply(raw) {
  const answer = typeof raw?.answer === "string" && raw.answer.trim() ? raw.answer.trim().slice(0, 1600) : FALLBACK_ANSWER;
  const confidence = Math.min(100, Math.max(0, Number(raw?.confidence) || 0));
  return { answer, confidence };
}

export function normalizeAction(raw) {
  const allowed = new Set(["TAP", "DOUBLE_TAP", "LONG_PRESS", "SWIPE", "DRAG", "PINCH_IN", "PINCH_OUT", "TWO_FINGER_SWIPE", "TYPE_TEXT", "WAIT", "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS", "OPEN_APP", "STOP"]);
  const type = allowed.has(String(raw?.type || "").toUpperCase()) ? String(raw.type).toUpperCase() : "STOP";
  const number = (value) => Math.min(1000, Math.max(0, Number(value) || 0));
  const durationMs = Math.min(5000, Math.max(100, Number(raw?.durationMs) || 600));
  const rawType = String(raw?.type || "").toUpperCase();
  const defaultWait = ["TAP", "DOUBLE_TAP", "LONG_PRESS"].includes(rawType) ? 80 : ["OPEN_APP", "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS"].includes(rawType) ? 180 : 350;
  const waitMs = Math.min(10000, Math.max(0, Number(raw?.waitMs) || defaultWait));
  const confidence = Math.min(100, Math.max(0, Number(raw?.confidence) || 0));
  const action = { type, x: number(raw?.x), y: number(raw?.y), x2: number(raw?.x2), y2: number(raw?.y2), text: typeof raw?.text === "string" ? raw.text.slice(0, 1000) : "", durationMs, waitMs, reason: typeof raw?.reason === "string" ? raw.reason.trim().slice(0, 400) : "", confidence, verify: raw?.verify !== false, stopReason: typeof raw?.stopReason === "string" ? raw.stopReason.trim().slice(0, 400) : "" };
  if (confidence < 60 && type !== "STOP") action.type = "STOP";
  return action;
}

export { FALLBACK_ANSWER };
