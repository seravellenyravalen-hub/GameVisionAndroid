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

export function mergeProviderResults(primaryResult, secondaryResult) {
  const primary = primaryResult || secondaryResult;
  if (!primary) throw new Error("No provider result available");
  if (!primaryResult || !secondaryResult) {
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
      notes: [...primary.notes, `${primary.provider} was the only available vision provider.`].slice(0, 5)
    };
  }

  const summaryAgree = primaryResult.summary.toLowerCase() === secondaryResult.summary.toLowerCase();
  const stateAgree = primaryResult.state === secondaryResult.state || primaryResult.state === "unknown" || secondaryResult.state === "unknown";
  const agreement = summaryAgree || stateAgree;
  const confidence = agreement
    ? Math.round(primaryResult.confidence * 0.6 + secondaryResult.confidence * 0.4)
    : Math.min(primaryResult.confidence, secondaryResult.confidence);
  const verified = agreement && primaryResult.confidence >= 80 && secondaryResult.confidence >= 80;
  return {
    summary: primaryResult.summary,
    state: stateAgree ? (primaryResult.state === "unknown" ? secondaryResult.state : primaryResult.state) : "uncertain",
    confidence,
    verified,
    verificationStatus: verified ? "VERIFIED" : agreement && confidence >= 70 ? "LIKELY" : agreement ? "LOW CONFIDENCE" : "UNVERIFIED",
    risk: verified ? "low" : "review",
    provider: `${primaryResult.provider}+${secondaryResult.provider}`,
    agreement,
    elements: primaryResult.elements.length >= secondaryResult.elements.length ? primaryResult.elements : secondaryResult.elements,
    notes: [
      ...primaryResult.notes,
      ...secondaryResult.notes,
      agreement ? `${primaryResult.provider} vision and ${secondaryResult.provider} cross-check are broadly consistent.` : "Vision providers disagree; review the current screen before trusting the result."
    ].filter((value, index, array) => array.indexOf(value) === index).slice(0, 5)
  };
}
