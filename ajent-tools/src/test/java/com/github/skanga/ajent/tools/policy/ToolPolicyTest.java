package com.github.skanga.ajent.tools.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.Profile;
import org.junit.jupiter.api.Test;

class ToolPolicyTest {

  @Test
  void permissionMatchesAllFortyEightAjentCells() {
    for (int bits = 0; bits < 16; bits++) {
      var effects = new EffectSet(bits);
      for (Profile profile : Profile.values()) {
        PermissionDecision expected = profile == Profile.WRITE
            || (profile == Profile.ASK && (bits & 0b1110) == 0)
            || (profile == Profile.MINIMAL && bits == 0)
            ? PermissionDecision.ALLOW : PermissionDecision.PROMPT;
        assertThat(PermissionPolicy.permission(effects, profile))
            .as("effects=%s profile=%s", bits, profile)
            .isEqualTo(expected);
      }
    }
  }

  @Test
  void reasonUsesAjentCapabilityPrecedence() {
    assertThat(PermissionPolicy.reason(EffectSet.of(Effect.EXEC, Effect.WRITE_FS), Profile.ASK))
        .isEqualTo("wants to run an arbitrary subprocess");
    assertThat(PermissionPolicy.reason(EffectSet.of(Effect.WRITE_FS, Effect.NET), Profile.ASK))
        .isEqualTo("will modify files on disk");
    assertThat(PermissionPolicy.reason(EffectSet.of(Effect.NET), Profile.ASK))
        .isEqualTo("will reach the network");
    assertThat(PermissionPolicy.reason(EffectSet.of(Effect.READ_FS), Profile.MINIMAL))
        .isEqualTo("wants to read from disk (Minimal profile)");
    assertThat(PermissionPolicy.reason(EffectSet.pure(), Profile.ASK))
        .isEqualTo("no side effects (auto-approved)");
    assertThat(PermissionPolicy.reason(EffectSet.of(Effect.EXEC), Profile.WRITE))
        .isEqualTo("auto-approved (Write profile)");
  }

  @Test
  void parallelSafetyAndEffectLabelsMatchAjent() {
    var read = EffectSet.of(Effect.READ_FS);
    var net = EffectSet.of(Effect.NET);
    var write = EffectSet.of(Effect.WRITE_FS);
    var exec = EffectSet.of(Effect.EXEC);
    assertThat(EffectSet.isParallelSafe(EffectSet.pure(), write)).isTrue();
    assertThat(EffectSet.isParallelSafe(read, read)).isTrue();
    assertThat(EffectSet.isParallelSafe(read, net)).isTrue();
    assertThat(EffectSet.isParallelSafe(read, write)).isFalse();
    assertThat(EffectSet.isParallelSafe(write, read)).isFalse();
    assertThat(EffectSet.isParallelSafe(exec, EffectSet.pure())).isFalse();
    assertThat(EffectSet.of(Effect.READ_FS, Effect.WRITE_FS, Effect.NET, Effect.EXEC).toString())
        .isEqualTo("Exec, WriteFs, Net, ReadFs");
    assertThat(EffectSet.pure().toString()).isEqualTo("Pure");
  }
}
