package com.github.skanga.ajent.provider;

/** Typed HTTP-layer failure kinds used at the provider boundary. */
public enum HttpErrorKind {
  CANCELLED,
  RESOLVE,
  CONNECT,
  TLS,
  PROTOCOL,
  SOCKET_HANGUP,
  TIMEOUT,
  PEER_CLOSED,
  STATUS,
  BODY,
  UNKNOWN
}
