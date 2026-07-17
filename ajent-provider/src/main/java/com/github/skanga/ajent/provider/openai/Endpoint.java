package com.github.skanga.ajent.provider.openai;

import java.util.Objects;

public record Endpoint(
    String host,
    int port,
    String path,
    String modelsPath,
    boolean useTls,
    String label,
    boolean nativeApi) {
  public Endpoint {
    host = Objects.requireNonNull(host, "host");
    path = Objects.requireNonNull(path, "path");
    modelsPath = Objects.requireNonNull(modelsPath, "modelsPath");
    label = Objects.requireNonNull(label, "label");
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException("port is outside the TCP range");
    }
  }

  public static Endpoint fromSpec(String specification) {
    String spec = Objects.requireNonNull(specification, "specification");
    return switch (spec) {
      case "", "openai" -> hosted(
          "api.openai.com", "/v1/chat/completions", "/v1/models", "openai");
      case "groq" -> hosted(
          "api.groq.com", "/openai/v1/chat/completions", "/openai/v1/models", "groq");
      case "openrouter" -> hosted(
          "openrouter.ai", "/api/v1/chat/completions", "/api/v1/models", "openrouter");
      case "together" -> hosted(
          "api.together.xyz", "/v1/chat/completions", "/v1/models", "together");
      case "cerebras" -> hosted(
          "api.cerebras.ai", "/v1/chat/completions", "/v1/models", "cerebras");
      case "ollama" -> new Endpoint(
          "localhost", 11_434, "/api/chat", "/api/tags", false, "ollama", true);
      case "llama.cpp" -> new Endpoint(
          "localhost", 8_080, "/v1/chat/completions", "/v1/models", false,
          "llama.cpp", false);
      default -> custom(spec);
    };
  }

  private static Endpoint hosted(String host, String path, String modelsPath, String label) {
    return new Endpoint(host, 443, path, modelsPath, true, label, false);
  }

  private static Endpoint custom(String spec) {
    int separator = spec.lastIndexOf(':');
    if (separator < 0) {
      return new Endpoint(spec, 443, "/v1/chat/completions", "/v1/models", true, spec, false);
    }
    String host = spec.substring(0, separator);
    int port = parsePort(spec.substring(separator + 1));
    return new Endpoint(
        host, port, "/v1/chat/completions", "/v1/models", port == 443, spec, false);
  }

  private static int parsePort(String value) {
    try {
      int port = Integer.parseInt(value);
      return port > 0 && port <= 65_535 ? port : 443;
    } catch (NumberFormatException ignored) {
      return 443;
    }
  }
}
