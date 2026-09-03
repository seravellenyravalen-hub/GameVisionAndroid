function cleanText(value) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

export function normalizeProviderResult(raw, provider) {
  const homeScore = Number.isInteger(raw?.homeScore) && raw.homeScore >= 0 ? raw.homeScore : null;
  const awayScore = Number.isInteger(raw?.awayScore) && raw.awayScore >= 0 ? raw.awayScore : null;
  const confidence = clamp(Number(raw?.confidence) || 0, 0, 100);
  const notes = Array.isArray(raw?.notes)
    ? raw.notes.filter((item) => typeof item === "string" && item.trim()).map((item) => item.trim()).slice(0, 5)
    : [];

  return {
    provider,
    homeTeam: cleanText(raw?.homeTeam),
    awayTeam: cleanText(raw?.awayTeam),
    homeScore,
    awayScore,
    minute: cleanText(raw?.minute),
    event: cleanText(raw?.event),
    confidence,
    notes
  };
}

function sameKnown(a, b) {
  return a !== null && b !== null && a === b;
}

export function mergeProviderResults(openaiResult, geminiResult) {
  const primary = openaiResult || geminiResult;
  if (!primary) throw new Error("No provider result available");

  if (!openaiResult || !geminiResult) {
    const available = primary;
    const score = available.homeScore !== null && available.awayScore !== null
      ? `${available.homeScore}-${available.awayScore}`
      : "Unknown";
    return {
      score,
      confidence: Math.round(available.confidence),
      verified: false,
      verificationStatus: available.confidence >= 70 ? "LIKELY" : "LOW CONFIDENCE",
      risk: "review",
      provider: available.provider,
      agreement: null,
      notes: [
        ...available.notes,
        `${available.provider === "openai" ? "OpenAI" : "Gemini"} was the only available vision provider.`
      ].slice(0, 5),
      prediction: { home: 0, draw: 0, away: 0 },
      details: {
        homeTeam: available.homeTeam,
        awayTeam: available.awayTeam,
        minute: available.minute,
        event: available.event
      }
    };
  }

  const scoreAgrees = sameKnown(openaiResult.homeScore, geminiResult.homeScore)
    && sameKnown(openaiResult.awayScore, geminiResult.awayScore);
  const minuteAgrees = openaiResult.minute !== null && geminiResult.minute !== null
    ? openaiResult.minute === geminiResult.minute
    : true;
  const teamsAgree = (openaiResult.homeTeam === null || geminiResult.homeTeam === null || openaiResult.homeTeam === geminiResult.homeTeam)
    && (openaiResult.awayTeam === null || geminiResult.awayTeam === null || openaiResult.awayTeam === geminiResult.awayTeam);
  const agreement = scoreAgrees && minuteAgrees && teamsAgree;
  const strongEvidence = openaiResult.confidence >= 80 && geminiResult.confidence >= 80;
  const verified = agreement && strongEvidence && openaiResult.homeScore !== null && openaiResult.awayScore !== null && Boolean(openaiResult.minute || geminiResult.minute);

  const score = scoreAgrees && openaiResult.homeScore !== null && openaiResult.awayScore !== null
    ? `${openaiResult.homeScore}-${openaiResult.awayScore}`
    : "Unknown";

  const confidence = agreement
    ? Math.round((openaiResult.confidence * 0.6) + (geminiResult.confidence * 0.4))
    : Math.min(openaiResult.confidence, geminiResult.confidence);

  const status = verified
    ? "VERIFIED"
    : agreement && confidence >= 70
      ? "LIKELY"
      : agreement
        ? "LOW CONFIDENCE"
        : "UNVERIFIED";

  const disagreementNote = agreement
    ? null
    : "Vision providers disagree on visible game details; review the frame before trusting the result.";

  return {
    score,
    confidence,
    verified,
    verificationStatus: status,
    risk: verified ? "low" : "review",
    provider: "openai+gemini",
    agreement,
    notes: [
      ...(openaiResult.notes || []),
      ...(geminiResult.notes || []),
      ...(disagreementNote ? [disagreementNote] : ["OpenAI primary vision and Gemini independent cross-check agree on the visible result."])
    ].filter((value, index, array) => array.indexOf(value) === index).slice(0, 5),
    prediction: { home: 0, draw: 0, away: 0 },
    details: {
      homeTeam: openaiResult.homeTeam || geminiResult.homeTeam,
      awayTeam: openaiResult.awayTeam || geminiResult.awayTeam,
      minute: minuteAgrees ? (openaiResult.minute || geminiResult.minute) : null,
      event: openaiResult.event || geminiResult.event
    }
  };
}
