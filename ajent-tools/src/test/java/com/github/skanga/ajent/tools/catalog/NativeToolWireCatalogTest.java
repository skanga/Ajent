package com.github.skanga.ajent.tools.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.provider.ToolSpecification;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeToolWireCatalogTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> PINNED_ORDER = List.of(
      "read", "edit", "write", "bash", "grep", "glob", "list_dir", "repo_map",
      "todo", "web_fetch", "web_search", "find_definition", "diagnostics",
      "git_status", "git_diff", "git_log", "git_commit", "remember", "forget",
      "wipe_memory", "task", "skill", "search_docs");
  private static final List<String> STANDALONE_MCP_ORDER = List.of(
      "read", "edit", "write", "bash", "grep", "glob", "list_dir", "repo_map",
      "todo", "web_fetch", "web_search", "find_definition", "diagnostics",
      "git_status", "git_diff", "git_log", "git_commit", "remember", "forget",
      "wipe_memory", "task", "skill", "search_docs");
  private static final List<String> PROVIDER_FACING_ORDER = List.of(
      "read", "edit", "write", "bash", "grep", "glob", "list_dir", "todo",
      "web_fetch", "web_search", "find_definition", "diagnostics", "git_status",
      "git_diff", "git_log", "git_commit", "remember", "forget", "wipe_memory",
      "task", "skill", "search_docs");

  @Test
  void publishesEveryPinnedAgenTTYToolInRecallBiasOrder() {
    assertThat(NativeToolWireCatalog.all())
        .extracting(ToolSpecification::name)
        .containsExactlyElementsOf(PINNED_ORDER);
    assertThat(NativeToolWireCatalog.all()).allSatisfy(specification ->
        assertThat(ToolCatalog.byName(specification.name())).isPresent());
  }

  @Test
  void publishesTheExactStandaloneMcpRegistrySubsetAndOrder() {
    assertThat(NativeToolWireCatalog.standaloneMcp())
        .extracting(ToolSpecification::name)
        .containsExactlyElementsOf(STANDALONE_MCP_ORDER)
        .contains("repo_map");
    assertThat(NativeToolWireCatalog.standaloneMcp()).isUnmodifiable();
  }

  @Test
  void publishesTheExactProviderFacingRegistrySubsetAndRecallBiasOrder() {
    assertThat(NativeToolWireCatalog.providerFacing())
        .extracting(ToolSpecification::name)
        .containsExactlyElementsOf(PROVIDER_FACING_ORDER)
        .doesNotContain("repo_map");
    assertThat(NativeToolWireCatalog.providerFacing()).isUnmodifiable();
  }

  @Test
  void preservesRepresentativeNativeDescriptionsSchemasAndStreamingMetadata() {
    ToolSpecification read = NativeToolWireCatalog.byName("read").orElseThrow();
    ToolSpecification repoMap = NativeToolWireCatalog.byName("repo_map").orElseThrow();

    assertThat(read.description()).contains("SYMBOL OUTLINE");
    assertThat(read.inputSchema().path("required").get(0).asText()).isEqualTo("path");
    assertThat(read.inputSchema().path("properties").path("start_line").path("type").asText())
        .isEqualTo("integer");
    assertThat(repoMap.description()).contains("PageRank", "FIRST");
    assertThat(repoMap.inputSchema().path("properties").path("budget").path("description")
        .asText()).contains("1000-60000", "8000");
    assertThat(NativeToolWireCatalog.byName("edit").orElseThrow().eagerInputStreaming()).isTrue();
    assertThat(read.eagerInputStreaming()).isFalse();
  }

  @Test
  void returnsAnUnmodifiableCatalogAndDefensiveSchemaCopies() {
    assertThat(NativeToolWireCatalog.all()).isUnmodifiable();
    ToolSpecification read = NativeToolWireCatalog.byName("read").orElseThrow();
    var mutated = read.inputSchema();
    mutated.withObject("properties").remove("path");
    assertThat(NativeToolWireCatalog.byName("read").orElseThrow().inputSchema()
        .path("properties").has("path")).isTrue();
    assertThat(NativeToolWireCatalog.byName("missing")).isEmpty();
  }

  @Test
  void fixtureAnnotationsExactlyMatchEffectProjection() throws Exception {
    try (var stream = NativeToolWireCatalogTest.class.getResourceAsStream("native-tools.json")) {
      var definitions = JSON.readTree(stream);
      for (var definition : definitions) {
        String name = definition.path("name").asText();
        var effects = NativeToolWireCatalog.wireEffects(name);
        boolean destructive = effects.has(com.github.skanga.ajent.tools.policy.Effect.EXEC);
        assertThat(definition.path("annotations").path("readOnlyHint").asBoolean())
            .isEqualTo(!destructive);
        assertThat(definition.path("annotations").path("destructiveHint").asBoolean())
            .isEqualTo(destructive);
        assertThat(definition.path("annotations").path("openWorldHint").asBoolean())
            .isEqualTo(effects.has(com.github.skanga.ajent.tools.policy.Effect.NET));
      }
    }
  }

  @Test
  void rejectsMalformedOrIncompleteCatalogsWithTypedContext() {
    assertThatThrownBy(() -> NativeToolWireCatalog.validate(JSON.createObjectNode()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("native tool catalog must be a JSON array");
    assertThatThrownBy(() -> NativeToolWireCatalog.validate(JSON.createArrayNode().add("bad")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("entry 0", "definition must be an object");
    var missingName = JSON.createArrayNode();
    missingName.addObject();
    assertThatThrownBy(() -> NativeToolWireCatalog.validate(missingName))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("entry 0", "name must be non-blank text");
    assertThatThrownBy(() -> NativeToolWireCatalog.wireEffects("missing"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unknown native tool: missing");
  }
}
