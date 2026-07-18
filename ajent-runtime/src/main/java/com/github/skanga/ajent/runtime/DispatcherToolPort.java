package com.github.skanga.ajent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.domain.CancellationSignal;
import java.util.function.Consumer;
import com.github.skanga.ajent.tools.runtime.ToolDispatcher;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.util.Objects;

/** Adapts the exhaustive native tool dispatcher to the headless loop seam. */
public final class DispatcherToolPort implements ToolPort {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ToolDispatcher dispatcher;

  public DispatcherToolPort(ToolDispatcher dispatcher) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
  }

  @Override public ToolCompletion execute(ToolUse call) {
    return execute(call, new CancellationSignal());
  }

  @Override public ToolCompletion execute(ToolUse call, CancellationSignal cancellation) {
    return execute(call, cancellation, ignored -> {});
  }

  @Override public ToolCompletion execute(ToolUse call, CancellationSignal cancellation,
                                          Consumer<String> progress) {
    try {
      return switch (dispatcher.execute(
          call.name().value(), JSON.valueToTree(call.arguments()), cancellation, progress)) {
        case ToolResult.Success success -> new ToolCompletion.Success(
            success.output().text(), success.output().change());
        case ToolResult.Failure failure -> new ToolCompletion.Failure(failure.error().render());
      };
    } catch (RuntimeException exception) {
      return new ToolCompletion.Failure("[INTERNAL] " + exception.getMessage());
    }
  }
}
