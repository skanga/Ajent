package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.CancellationSignal;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.util.List;
import java.util.function.Consumer;

@FunctionalInterface
public interface ProviderPort {
  void stream(long turnId, List<Message> messages, CancellationSignal cancellation,
              Consumer<StreamEvent> events);
}
