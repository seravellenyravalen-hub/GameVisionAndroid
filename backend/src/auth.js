import crypto from "node:crypto";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
let Pool;
export const FREE_CREDITS = 50;
export const RESET_WINDOW_MS = 24 * 60 * 60 * 1000;
const PASSWORD_MIN_LENGTH = 8;
let pool;
let schemaPromise;

export function normalizeEmail(value) { return String(value || "").trim().toLowerCase(); }
export function isValidEmail(value) { return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalizeEmail(value)); }

function getPool() {
  if (!process.env.DATABASE_URL) throw new Error("DATABASE_URL is not configured");
  if (!Pool) Pool = require("pg").Pool;
  if (!pool) pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 5, ssl: { rejectUnauthorized: false } });
  return pool;
}

export async function ensureAuthSchema() {
  if (schemaPromise) return schemaPromise;
  schemaPromise = (async () => {
    const db = getPool();
    await db.query(`
      CREATE EXTENSION IF NOT EXISTS pgcrypto;
      CREATE TABLE IF NOT EXISTS gamevision_users (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        email TEXT NOT NULL UNIQUE,
        password_hash TEXT NOT NULL,
        credits_remaining INTEGER NOT NULL DEFAULT ${FREE_CREDITS},
        reset_at TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '24 hours',
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );
      CREATE TABLE IF NOT EXISTS gamevision_sessions (
        token_hash TEXT PRIMARY KEY,
        user_id UUID NOT NULL REFERENCES gamevision_users(id) ON DELETE CASCADE,
        expires_at TIMESTAMPTZ NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );
      CREATE INDEX IF NOT EXISTS gamevision_sessions_user_idx ON gamevision_sessions(user_id);
      CREATE INDEX IF NOT EXISTS gamevision_sessions_expiry_idx ON gamevision_sessions(expires_at);
    `);
  })().catch((error) => { schemaPromise = undefined; throw error; });
  return schemaPromise;
}

export function hashSessionToken(token) { return crypto.createHash("sha256").update(String(token)).digest("hex"); }
export function createSessionToken() { return crypto.randomBytes(48).toString("base64url"); }

export async function hashPassword(password) {
  const value = String(password ?? "");
  if (value.length < PASSWORD_MIN_LENGTH) throw new Error(`Password must be at least ${PASSWORD_MIN_LENGTH} characters`);
  const salt = crypto.randomBytes(16).toString("hex");
  const derived = await new Promise((resolve, reject) => crypto.scrypt(value, salt, 64, { N: 16384, r: 8, p: 1 }, (error, key) => error ? reject(error) : resolve(key)));
  return `scrypt:${salt}:${Buffer.from(derived).toString("hex")}`;
}

export async function verifyPassword(password, stored) {
  try {
    const [algorithm, salt, digest] = String(stored || "").split(":");
    if (algorithm !== "scrypt" || !salt || !digest) return false;
    const derived = await new Promise((resolve, reject) => crypto.scrypt(String(password ?? ""), salt, 64, { N: 16384, r: 8, p: 1 }, (error, key) => error ? reject(error) : resolve(key)));
    const a = Buffer.from(digest, "hex");
    const b = Buffer.from(derived);
    return a.length === b.length && crypto.timingSafeEqual(a, b);
  } catch { return false; }
}

function publicUser(row) { return { id: row.id, email: row.email, creditsRemaining: Number(row.credits_remaining), resetAt: row.reset_at, createdAt: row.created_at }; }

async function refreshAllowance(client, row) {
  if (new Date(row.reset_at).getTime() > Date.now()) return row;
  const updated = await client.query(`UPDATE gamevision_users SET credits_remaining = $1, reset_at = NOW() + INTERVAL '24 hours' WHERE id = $2 RETURNING *`, [FREE_CREDITS, row.id]);
  return updated.rows[0];
}

export async function createAccount(email, password) {
  await ensureAuthSchema();
  const normalized = normalizeEmail(email);
  if (!isValidEmail(normalized)) throw Object.assign(new Error("Enter a valid email address"), { code: "INVALID_EMAIL" });
  const passwordHash = await hashPassword(password);
  try {
    const result = await getPool().query(`INSERT INTO gamevision_users(email, password_hash) VALUES($1, $2) RETURNING *`, [normalized, passwordHash]);
    return publicUser(result.rows[0]);
  } catch (error) {
    if (error?.code === "23505") throw Object.assign(new Error("An account with that email already exists"), { code: "ACCOUNT_EXISTS" });
    throw error;
  }
}

/** Creates the account and session in one application flow, avoiding a second password lookup. */
export async function createAccountSession(email, password) {
  await ensureAuthSchema();
  const normalized = normalizeEmail(email);
  if (!isValidEmail(normalized)) throw Object.assign(new Error("Enter a valid email address"), { code: "INVALID_EMAIL" });
  const passwordHash = await hashPassword(password);
  const client = await getPool().connect();
  try {
    await client.query("BEGIN");
    const result = await client.query(`INSERT INTO gamevision_users(email, password_hash) VALUES($1, $2) RETURNING *`, [normalized, passwordHash]);
    const user = result.rows[0];
    const token = createSessionToken();
    await client.query(`INSERT INTO gamevision_sessions(token_hash, user_id, expires_at) VALUES($1, $2, NOW() + INTERVAL '30 days')`, [hashSessionToken(token), user.id]);
    await client.query("COMMIT");
    return { token, user: publicUser(user) };
  } catch (error) {
    await client.query("ROLLBACK");
    if (error?.code === "23505") throw Object.assign(new Error("An account with that email already exists"), { code: "ACCOUNT_EXISTS" });
    throw error;
  } finally { client.release(); }
}

export async function loginAccount(email, password) {
  await ensureAuthSchema();
  const normalized = normalizeEmail(email);
  const result = await getPool().query(`SELECT * FROM gamevision_users WHERE email = $1`, [normalized]);
  if (!result.rows[0] || !(await verifyPassword(password, result.rows[0].password_hash))) throw Object.assign(new Error("Email or password is incorrect"), { code: "INVALID_CREDENTIALS" });
  const user = await refreshAllowance(getPool(), result.rows[0]);
  const token = createSessionToken();
  await getPool().query(`INSERT INTO gamevision_sessions(token_hash, user_id, expires_at) VALUES($1, $2, NOW() + INTERVAL '30 days')`, [hashSessionToken(token), user.id]);
  return { token, user: publicUser(user) };
}

export async function getUserForToken(token) {
  if (!token) return null;
  await ensureAuthSchema();
  const tokenHash = hashSessionToken(token);
  const result = await getPool().query(`SELECT u.*, s.expires_at AS session_expires_at FROM gamevision_sessions s JOIN gamevision_users u ON u.id = s.user_id WHERE s.token_hash = $1`, [tokenHash]);
  if (!result.rows[0]) return null;
  const row = result.rows[0];
  if (new Date(row.session_expires_at).getTime() <= Date.now()) { await getPool().query(`DELETE FROM gamevision_sessions WHERE token_hash = $1`, [tokenHash]); return null; }
  const user = await refreshAllowance(getPool(), row);
  return publicUser(user);
}

export async function logoutToken(token) {
  if (!token) return;
  await ensureAuthSchema();
  await getPool().query(`DELETE FROM gamevision_sessions WHERE token_hash = $1`, [hashSessionToken(token)]);
}

export async function consumeCredit(userId) {
  await ensureAuthSchema();
  const client = await getPool().connect();
  try {
    await client.query("BEGIN");
    const found = await client.query(`SELECT * FROM gamevision_users WHERE id = $1 FOR UPDATE`, [userId]);
    if (!found.rows[0]) throw Object.assign(new Error("Account not found"), { code: "ACCOUNT_NOT_FOUND" });
    const user = await refreshAllowance(client, found.rows[0]);
    if (Number(user.credits_remaining) <= 0) { await client.query("COMMIT"); return { allowed: false, user: publicUser(user) }; }
    const updated = await client.query(`UPDATE gamevision_users SET credits_remaining = credits_remaining - 1 WHERE id = $1 RETURNING *`, [userId]);
    await client.query("COMMIT");
    return { allowed: true, user: publicUser(updated.rows[0]) };
  } catch (error) { await client.query("ROLLBACK"); throw error; }
  finally { client.release(); }
}

export async function refundCredit(userId) {
  await ensureAuthSchema();
  const result = await getPool().query(`UPDATE gamevision_users SET credits_remaining = LEAST($1, credits_remaining + 1) WHERE id = $2 RETURNING *`, [FREE_CREDITS, userId]);
  return result.rows[0] ? publicUser(result.rows[0]) : null;
}

export function authMiddleware() {
  return async (req, res, next) => {
    try {
      const header = String(req.headers.authorization || "");
      const bearer = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
      const cookies = String(req.headers.cookie || "").split(";").map((item) => item.trim());
      const cookieToken = cookies.find((item) => item.startsWith("gv_session="))?.slice("gv_session=".length) || "";
      const user = await getUserForToken(bearer || cookieToken);
      if (!user) return res.status(401).json({ error: "Sign in required", code: "AUTH_REQUIRED" });
      req.authUser = user; req.authToken = bearer || cookieToken; next();
    } catch (error) { console.error("Authentication error:", error?.message || error); res.status(503).json({ error: "Account service unavailable", code: "AUTH_UNAVAILABLE" }); }
  };
}

export const passwordRequirements = { minLength: PASSWORD_MIN_LENGTH };