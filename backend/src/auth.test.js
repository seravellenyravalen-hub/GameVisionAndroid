import test from "node:test";
import assert from "node:assert/strict";
import { hashPassword, verifyPassword, createSessionToken, hashSessionToken, normalizeEmail, isValidEmail, FREE_CREDITS, RESET_WINDOW_MS, automationSessionIsActive } from "./auth.js";

test("normalizes and validates account email addresses", () => {
  assert.equal(normalizeEmail("  USER@Example.COM "), "user@example.com");
  assert.equal(isValidEmail("user@example.com"), true);
  assert.equal(isValidEmail("not-an-email"), false);
});

test("password hashing verifies the original password and rejects a wrong one", async () => {
  const stored = await hashPassword("correct horse battery staple");
  assert.equal(await verifyPassword("correct horse battery staple", stored), true);
  assert.equal(await verifyPassword("wrong password", stored), false);
});

test("session tokens are opaque and only their hash is stored", () => {
  const token = createSessionToken();
  assert.equal(token.length >= 40, true);
  assert.equal(hashSessionToken(token).length, 64);
  assert.notEqual(hashSessionToken(token), token);
});

test("free allowance has a finite reset window", () => {
  assert.equal(Number.isInteger(FREE_CREDITS), true);
  assert.equal(FREE_CREDITS > 0, true);
  assert.equal(RESET_WINDOW_MS > 0, true);
});

test("automation sessions stay reusable only while their lease is active", () => {
  const now = Date.parse("2026-09-05T12:00:00.000Z");
  assert.equal(automationSessionIsActive({ expires_at: "2026-09-05T12:10:00.000Z", closed_at: null }, now), true);
  assert.equal(automationSessionIsActive({ expires_at: "2026-09-05T11:59:59.000Z", closed_at: null }, now), false);
  assert.equal(automationSessionIsActive({ expires_at: "2026-09-05T12:10:00.000Z", closed_at: "2026-09-05T12:01:00.000Z" }, now), false);
});
