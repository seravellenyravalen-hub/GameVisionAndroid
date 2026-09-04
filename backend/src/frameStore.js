export class FrameStore {
  constructor({ maxAgeMs = 15000 } = {}) {
    this.maxAgeMs = maxAgeMs;
    this.sequence = 0;
    this.frames = new Map();
  }

  put(userId, images, capturedAt = Date.now()) {
    const sequence = ++this.sequence;
    const frame = { images, capturedAt, sequence, userId };
    this.frames.set(String(userId), frame);
    return frame;
  }

  get(userId) {
    return this.frames.get(String(userId)) || null;
  }

  status(userId) {
    const frame = userId == null ? [...this.frames.values()].sort((a, b) => b.sequence - a.sequence)[0] : this.get(userId);
    if (!frame) return { sequence: 0, capturedAt: null, ageMs: null, fresh: false };
    return {
      sequence: frame.sequence,
      capturedAt: frame.capturedAt,
      ageMs: Math.max(0, Date.now() - frame.capturedAt),
      fresh: Date.now() - frame.capturedAt <= this.maxAgeMs
    };
  }

  isFresh(userId, minSequence = 0) {
    const frame = this.get(userId);
    return Boolean(frame && frame.sequence > minSequence && Date.now() - frame.capturedAt <= this.maxAgeMs);
  }
}
