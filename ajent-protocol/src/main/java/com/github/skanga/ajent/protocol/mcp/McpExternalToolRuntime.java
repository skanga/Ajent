package com.github.skanga.ajent.protocol.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.tools.policy.EffectSet;
import com.github.skanga.ajent.tools.runtime.ExternalToolRuntime;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Projects a live MCP registry into the protocol-neutral agent tool runtime. */
public final class McpExternalToolRuntime implements ExternalToolRuntime {
  private final McpRegistry registry;

  public McpExternalToolRuntime(McpRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @Override public List<ToolSpecification> specifications() {
    return registry.tools().stream().map(McpRegistry.ProjectedTool::specification).toList();
  }

  @Override public Optional<EffectSet> effects(String name) {
    return registry.tools().stream()
        .filter(tool -> tool.specification().name().equals(name))
        .map(McpRegistry.ProjectedTool::effects).findFirst();
  }

  @Override public ToolResult execute(String name, ObjectNode arguments) {
    return registry.execute(name, arguments);
  }
}
