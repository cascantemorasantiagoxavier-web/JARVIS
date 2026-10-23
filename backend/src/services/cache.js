// Caché LRU con caducidad. Map conserva el orden de inserción.
export class TtlCache {
  constructor(maxEntries = 200, ttlMs = 3_600_000, now = () => Date.now()) {
    this.max = maxEntries;
    this.ttl = ttlMs;
    this.now = now;
    this.map = new Map();
  }

  get(key) {
    const e = this.map.get(key);
    if (!e) return undefined;
    if (e.exp < this.now()) {
      this.map.delete(key);
      return undefined;
    }
    this.map.delete(key);
    this.map.set(key, e);
    return e.value;
  }

  set(key, value) {
    if (this.ttl <= 0) return;
    this.map.delete(key);
    this.map.set(key, { value, exp: this.now() + this.ttl });
    while (this.map.size > this.max) this.map.delete(this.map.keys().next().value);
  }
}
