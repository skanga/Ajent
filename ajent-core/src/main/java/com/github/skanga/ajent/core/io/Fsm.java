package com.github.skanga.ajent.core.io;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/** Generic typestate utilities and single-owner capability tokens. */
public final class Fsm {
  private Fsm() {}

  public interface State {}

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  public @interface TransitionsTo { Class<? extends State>[] value(); }

  public static boolean isState(Class<?> type) {
    return State.class.isAssignableFrom(type);
  }

  public static boolean isLegalEdge(
      Class<? extends State> from, Class<? extends State> to) {
    TransitionsTo transitions = from.getAnnotation(TransitionsTo.class);
    return transitions != null && Arrays.asList(transitions.value()).contains(to);
  }

  public static void requireLegalEdge(
      Class<? extends State> from, Class<? extends State> to) {
    if (!isLegalEdge(from, to)) {
      throw new IllegalArgumentException("Illegal FSM transition: " + from.getName() + " -> " + to.getName());
    }
  }

  public static final class CapabilityToken<T> implements AutoCloseable {
    private final Consumer<? super T> cleanup;
    private T value;
    private boolean consumed;

    public CapabilityToken(T value, Consumer<? super T> cleanup) {
      this.value = Objects.requireNonNull(value, "value");
      this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    public T transfer() {
      if (consumed) throw new IllegalStateException("Capability token has already been consumed");
      consumed = true;
      T transferred = value;
      value = null;
      return transferred;
    }

    @Override public void close() {
      if (consumed) return;
      consumed = true;
      T owned = value;
      value = null;
      cleanup.accept(owned);
    }
  }
}
