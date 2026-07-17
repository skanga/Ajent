package com.github.skanga.ajent.domain;

/** Native three-state stream retry/stall machine. */
public sealed interface RetryState {
  record Fresh() implements RetryState {}
  record StallFired() implements RetryState {}
  record Scheduled() implements RetryState {}
}
