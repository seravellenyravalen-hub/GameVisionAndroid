import crypto from "node:crypto";

export class FrameStore {
  constructor({ maxAgeMs = 15000, epoch = crypto.randomUUID() } = {}) {
    this.maxAgeMs = maxAgeMs;
    this.epoch = String(epoch);
    this.sequence = 0;
    this.frames = new Map();
  }

  put(userId, images, capturedAt = Date.now()) {
    const sequence = ++this.sequence;
    const frame = { images, capturedAt, sequence, serverEpoch: this.epoch, userId };
    this.frames.set(String(userId), frame);
    return frame;
  }

  get(userId) {
    return this.frames.get(String(userId)) || null;
  }

  status(userId) {
    const frame = userId == null ? [...this.frames.values()].sort((a, b) => b.sequence - a.sequence)[0] : this.get(userId);
    if (!frame) return { sequence: 0, capturedAt: null, ageMs: null, fresh: false, serverEpoch: this.epoch };
    const ageMs = Date.now() - frame.capturedAt;
    return {
      sequence: frame.sequence,
      capturedAt: frame.capturedAt,
      ageMs: Math.max(0, ageMs),
      fresh: ageMs >= 0 && ageMs <= this.maxAgeMs,
      serverEpoch: frame.serverEpoch
    };
  }

  isFresh(userId, minSequence = 0, minEpoch = null) {
    const frame = this.get(userId);
    if (!frame) return false;
    const ageMs = Date.now() - frame.capturedAt;
    const epochChanged = minEpoch != null && String(minEpoch) !== frame.serverEpoch;
    return Boolean((epochChanged || frame.sequence > minSequence) && ageMs >= 0 && ageMs <= this.maxAgeMs);
  }
}
