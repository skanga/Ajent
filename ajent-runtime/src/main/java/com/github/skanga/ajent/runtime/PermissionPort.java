package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.ToolUse;

@FunctionalInterface
public interface PermissionPort {
  record Decision(boolean approved, boolean always) {}
  Decision request(ToolUse call);
}
