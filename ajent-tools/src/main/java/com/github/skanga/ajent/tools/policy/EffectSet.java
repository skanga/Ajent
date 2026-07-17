package com.github.skanga.ajent.tools.policy;

/** Immutable four-bit tool capability set. */
public record EffectSet(int bits) {
  public static EffectSet pure() { return new EffectSet(0); }

  public static EffectSet of(Effect... effects) {
    int bits = 0;
    for (Effect effect : effects) bits |= effect.bit();
    return new EffectSet(bits);
  }

  public boolean has(Effect effect) { return (bits & effect.bit()) != 0; }

  public boolean isEmpty() { return bits == 0; }

  public EffectSet union(EffectSet other) { return new EffectSet(bits | other.bits); }

  public static boolean isParallelSafe(EffectSet active, EffectSet wanted) {
    boolean activeExclusive = active.has(Effect.WRITE_FS) || active.has(Effect.EXEC);
    boolean wantedExclusive = wanted.has(Effect.WRITE_FS) || wanted.has(Effect.EXEC);
    return !(activeExclusive || wantedExclusive) || active.isEmpty();
  }

  @Override
  public String toString() {
    if (isEmpty()) return "Pure";
    var result = new StringBuilder();
    append(result, Effect.EXEC);
    append(result, Effect.WRITE_FS);
    append(result, Effect.NET);
    append(result, Effect.READ_FS);
    return result.toString();
  }

  private void append(StringBuilder result, Effect effect) {
    if (!has(effect)) return;
    if (!result.isEmpty()) result.append(", ");
    result.append(effect);
  }
}
