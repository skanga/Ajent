package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.CancellationSignal;
import com.github.skanga.ajent.domain.ToolUse;
import java.util.function.Consumer;

@FunctionalInterface
public interface ToolPort {
  ToolCompletion execute(ToolUse call);

  default ToolCompletion execute(ToolUse call, CancellationSignal cancellation) {
    return execute(call);
  }

  default ToolCompletion execute(ToolUse call, CancellationSignal cancellation,
                                 Consumer<String> progress) {
    return execute(call, cancellation);
  }
}
