package com.github.skanga.ajent.tools.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.tools.policy.EffectSet;
import com.github.skanga.ajent.tools.rag.EmbeddingClient;
import com.github.skanga.ajent.tools.rag.KnowledgeSource;
import java.util.List;
import java.util.Optional;

/** Dynamic tool source used for live MCP capabilities without coupling tools to protocols. */
public interface ExternalToolRuntime {
  List<ToolSpecification> specifications();

  Optional<EffectSet> effects(String name);

  ToolResult execute(String name, ObjectNode arguments);

  default Optional<KnowledgeSource> knowledgeSource(
      EmbeddingClient.Config embedding, EmbeddingClient client) {
    return Optional.empty();
  }

  static ExternalToolRuntime none() {
    return Empty.INSTANCE;
  }

  enum Empty implements ExternalToolRuntime {
    INSTANCE;

    @Override public List<ToolSpecification> specifications() { return List.of(); }
    @Override public Optional<EffectSet> effects(String name) { return Optional.empty(); }
    @Override public ToolResult execute(String name, ObjectNode arguments) {
      throw new IllegalArgumentException("unknown external tool: " + name);
    }
  }
}
