const FALLBACK_ANSWER = "I could not determine that from the visible screen.";

export function buildAssistantPrompt(instruction) {
  const request = String(instruction || "").trim().slice(0, 1000);
  return `You are the GameVision screen assistant. Use ONLY the visible screenshot provided with this request. Follow the user's instruction when it is about what is visible on screen. Do not use hidden game data, memory, APIs, or outside knowledge. Do not guess. If the requested information is not clearly visible, say that you cannot determine it from the screen. Keep the answer concise and directly useful.\n\nUser instruction: ${request}`;
}

export function normalizeAssistantReply(raw) {
  const answer = typeof raw?.answer === "string" && raw.answer.trim()
    ? raw.answer.trim().slice(0, 1200)
    : FALLBACK_ANSWER;
  const confidence = Math.min(100, Math.max(0, Number(raw?.confidence) || 0));
  return { answer, confidence };
}

export { FALLBACK_ANSWER };
