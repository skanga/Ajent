package com.github.skanga.ajent.tools.rag;

import com.github.skanga.ajent.core.AgenttyDebugLog;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Batch embedding seam used by hybrid document retrieval. */
@FunctionalInterface
public interface EmbeddingClient {
  record Config(String host, int port, String model) {
    public Config {
      if (host == null || host.isBlank()) throw new IllegalArgumentException("host is required");
      if (port < 1 || port > 65_535) throw new IllegalArgumentException("invalid port");
      model = model == null ? "" : model;
    }

    public static Config disabled() {
      return new Config("127.0.0.1", 11_434, "");
    }

    public static Config agenttyDefaults() {
      return new Config("127.0.0.1", 11_434, "nomic-embed-text");
    }

    /** Resolves AgenTTY's AGENTTY_EMBED_MODEL and AGENTTY_OLLAMA_HOST contract. */
    public static Config fromEnvironment(Map<String, String> environment) {
      String model = environment.getOrDefault("AGENTTY_EMBED_MODEL", "");
      if (model.isEmpty()) model = "nomic-embed-text";
      String host = "127.0.0.1";
      int port = 11_434;
      String endpoint = environment.getOrDefault("AGENTTY_OLLAMA_HOST", "");
      if (!endpoint.isEmpty()) {
        int colon = endpoint.lastIndexOf(':');
        if (colon >= 0) {
          if (colon > 0) host = endpoint.substring(0, colon);
          try {
            int candidate = Integer.parseInt(endpoint.substring(colon + 1));
            if (candidate > 0 && candidate <= 65_535) port = candidate;
          } catch (NumberFormatException failure) {
            AgenttyDebugLog.log("rag.embed_endpoint.port", failure);
            // Keep the native default port.
          }
        } else host = endpoint;
      }
      return new Config(host, port, model);
    }
  }

  Optional<List<float[]>> embed(Config config, List<String> texts);
}
