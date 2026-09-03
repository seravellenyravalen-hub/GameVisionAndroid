function cleanText(value) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

export function normalizeProviderResult(raw, provider) {
  const elements = Array.isArray(raw?.elements)
    ? raw.elements.map((item) => ({
        label: cleanText(item?.label),
        x: clamp(Number(item?.x) || 0, 0, 1000),
        y: clamp(Number(item?.y) || 0, 0, 1000),
        width: clamp(Number(item?.width) || 0, 0, 1000),
        height: clamp(Number(item?.height) || 0, 0, 1000),
        confidence: clamp(Number(item?.confidence) || 0, 0, 100)
      })).filter((item) => item.label).slice(0, 12)
    : [];
  return {
    provider,
    summary: cleanText(raw?.summary) || "No clear screen summary.",
    state: cleanText(raw?.state) || "unknown",
    confidence: clamp(Number(raw?.confidence) || 0, 0, 100),
    elements,
    notes: Array.isArray(raw?.notes)
      ? raw.notes.filter((item) => typeof item === "string" && item.trim()).map((item) => item.trim()).slice(0, 5)
      : []
  };
}

export function mergeProviderResults(openaiResult, geminiResult) {
  const primary = openaiResult || geminiResult;
  if (!primary) throw new Error("No provider result available");
  if (!openaiResult || !geminiResult) {
    return {
      summary: primary.summary,
      state: primary.state,
      confidence: Math.round(primary.confidence),
      verified: false,
      verificationStatus: primary.confidence >= 70 ? "LIKELY" : "LOW CONFIDENCE",
      risk: "review",
      provider: primary.provider,
      agreement: null,
      elements: primary.elements,
      notes: [...primary.notes, `${primary.provider === "openai" ? "OpenAI" : "Gemini"} was the only available vision provider.`].slice(0, 5)
    };
  }

  const summaryAgree = openaiResult.summary.toLowerCase() === geminiResult.summary.toLowerCase();
  const stateAgree = openaiResult.state === geminiResult.state || openaiResult.state === "unknown" || geminiResult.state === "unknown";
  const agreement = summaryAgree || stateAgree;
  const confidence = agreement
    ? Math.round(openaiResult.confidence * 0.6 + geminiResult.confidence * 0.4)
    : Math.min(openaiResult.confidence, geminiResult.confidence);
  const verified = agreement && openaiResult.confidence >= 80 && geminiResult.confidence >= 80;
  return {
    summary: openaiResult.summary,
    state: stateAgree ? (openaiResult.state === "unknown" ? geminiResult.state : openaiResult.state) : "uncertain",
    confidence,
    verified,
    verificationStatus: verified ? "VERIFIED" : agreement && confidence >= 70 ? "LIKELY" : agreement ? "LOW CONFIDENCE" : "UNVERIFIED",
    risk: verified ? "low" : "review",
    provider: "openai+gemini",
    agreement,
    elements: openaiResult.elements.length >= geminiResult.elements.length ? openaiResult.elements : geminiResult.elements,
    notes: [
      ...openaiResult.notes,
      ...geminiResult.notes,
      agreement ? "OpenAI primary vision and Gemini cross-check are broadly consistent." : "Vision providers disagree; review the current screen before trusting the result."
    ].filter((value, index, array) => array.indexOf(value) === index).slice(0, 5)
  };
}
