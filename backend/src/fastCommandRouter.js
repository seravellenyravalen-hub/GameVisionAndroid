export function parseFastCommand(input) {
  const value = String(input || "").trim();
  const lower = value.toLowerCase();
  if (!value) return null;
  if (/^(?:go )?back$/.test(lower)) return { type: "BACK" };
  if (/^(?:go )?home$/.test(lower)) return { type: "HOME" };
  if (/^(?:open )?(?:recent apps|recents|recent)$/.test(lower)) return { type: "RECENTS" };
  if (/^(?:open )?notifications?$/.test(lower)) return { type: "NOTIFICATIONS" };
  if (/^(?:open )?quick settings$/.test(lower)) return { type: "QUICK_SETTINGS" };
  const tap = lower.match(/^(?:tap|click|press|touch)\s+(.+)$/i);
  if (tap) return { type: "TAP_TARGET", target: tap[1].trim() };
  const doubleTap = lower.match(/^double tap\s+(.+)$/i);
  if (doubleTap) return { type: "DOUBLE_TAP_TARGET", target: doubleTap[1].trim() };
  const longPress = lower.match(/^(?:long press|hold)\s+(.+)$/i);
  if (longPress) return { type: "LONG_PRESS_TARGET", target: longPress[1].trim() };
  const typeText = value.match(/^(?:type|enter|write)\s+(.+)$/i);
  if (typeText) return { type: "TYPE_TEXT", text: typeText[1] };
  const wait = lower.match(/^wait\s+(\d+)\s*(?:ms|milliseconds?)?$/i);
  if (wait) return { type: "WAIT", waitMs: Math.min(5000, Math.max(0, Number(wait[1]))) };
  return null;
}
