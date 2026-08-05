package com.github.skanga.ajent.tools.policy;

import com.github.skanga.ajent.domain.Profile;

/** Single source of truth for Ajent's effects-by-profile permission table. */
public final class PermissionPolicy {
  private PermissionPolicy() {}

  public static PermissionDecision permission(EffectSet effects, Profile profile) {
    if (profile == Profile.WRITE) return PermissionDecision.ALLOW;
    if (effects.has(Effect.EXEC)) return PermissionDecision.PROMPT;
    if (effects.has(Effect.WRITE_FS)) return PermissionDecision.PROMPT;
    if (effects.has(Effect.NET)) return PermissionDecision.PROMPT;
    if (profile == Profile.MINIMAL && effects.has(Effect.READ_FS)) {
      return PermissionDecision.PROMPT;
    }
    return PermissionDecision.ALLOW;
  }

  public static String reason(EffectSet effects, Profile profile) {
    if (profile == Profile.WRITE) return "auto-approved (Write profile)";
    if (effects.has(Effect.EXEC)) return "wants to run an arbitrary subprocess";
    if (effects.has(Effect.WRITE_FS)) return "will modify files on disk";
    if (effects.has(Effect.NET)) return "will reach the network";
    if (profile == Profile.MINIMAL && effects.has(Effect.READ_FS)) {
      return "wants to read from disk (Minimal profile)";
    }
    return "no side effects (auto-approved)";
  }
}
