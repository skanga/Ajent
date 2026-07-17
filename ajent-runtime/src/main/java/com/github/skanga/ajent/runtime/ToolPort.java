package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.ToolUse;

@FunctionalInterface
public interface ToolPort {
  ToolCompletion execute(ToolUse call);
}
