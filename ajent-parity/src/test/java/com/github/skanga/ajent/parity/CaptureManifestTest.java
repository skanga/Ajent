package com.github.skanga.ajent.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class CaptureManifestTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void everyBehavioralFixtureClassHasClosedExecutableOrSourceEvidence() throws Exception {
    Path root = repositoryRoot();
    JsonNode manifest = JSON.readTree(root.resolve(
        "ajent-parity/src/test/resources/reference/capture-manifest.json").toFile());
    JsonNode fixtures = manifest.path("fixtureClasses");

    assertThat(manifest.path("reference").path("commit").asText())
        .isEqualTo("c7594d64020cfdacb10b6a0b2074bcedcc827bba");
    assertThat(fixtures).hasSize(10);
    var ids = new HashSet<String>();
    for (JsonNode fixture : fixtures) {
      String id = fixture.path("id").asText();
      assertThat(ids.add(id)).as("unique fixture id %s", id).isTrue();
      assertThat(fixture.path("status").asText()).as("closed status for %s", id)
          .endsWith("-green");
      assertThat(fixture.path("sources")).as("sources for %s", id).isNotEmpty();
      assertThat(fixture.path("cases")).as("cases for %s", id).isNotEmpty();
      assertThat(fixture.has("javaEvidence")
          ? fixture.path("javaEvidence") : fixture.path("evidence"))
          .as("Java evidence for %s", id).isNotEmpty();
      fixture.properties().forEach(property -> {
        if (!property.getKey().startsWith("remaining")) return;
        JsonNode value = property.getValue();
        assertThat(value.isArray() ? value.isEmpty() : value.asText().isEmpty())
            .as("%s.%s", id, property.getKey()).isTrue();
      });
    }
    assertThat(ids).containsExactlyInAnyOrderElementsOf(Set.of(
        "cli", "persistence", "provider-anthropic", "provider-openai-compatible",
        "provider-ollama", "native-tools", "reducer", "acp", "mcp-server-client",
        "terminal"));
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (candidate != null) {
      if (Files.isRegularFile(candidate.resolve("pom.xml"))
          && Files.isDirectory(candidate.resolve("ajent-parity"))) return candidate;
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Ajent repository root not found");
  }
}
