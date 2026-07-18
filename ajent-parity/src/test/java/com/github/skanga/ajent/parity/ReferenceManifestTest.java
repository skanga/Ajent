package com.github.skanga.ajent.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ReferenceManifestTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PINNED_COMMIT = "c7594d64020cfdacb10b6a0b2074bcedcc827bba";

  @Test
  void inventoriesEveryPinnedReferenceProgramAndValidatesGreenCounterparts() throws Exception {
    Path root = repositoryRoot();
    JsonNode manifest = JSON.readTree(root.resolve(
        "ajent-parity/src/test/resources/reference/test-manifest.json").toFile());
    JsonNode tests = manifest.path("tests");

    assertThat(manifest.path("reference").path("commit").asText()).isEqualTo(PINNED_COMMIT);
    assertThat(tests).hasSize(53);
    assertThat(manifest.path("summary").path("total").asInt()).isEqualTo(tests.size());

    var sources = new HashSet<String>();
    int registered = 0;
    int buildOnly = 0;
    int sourceOnly = 0;
    int green = 0;
    for (JsonNode entry : tests) {
      String id = entry.path("id").asText();
      String source = entry.path("source").asText();
      assertThat(id).as("manifest id").isNotBlank();
      assertThat(source).as("source for %s", id).startsWith("agentty/tests/").endsWith(".cpp");
      assertThat(sources.add(source)).as("unique source for %s", id).isTrue();
      assertThat(entry.path("classification").asText()).as("classification for %s", id)
          .isNotBlank();
      assertThat(entry.path("surface").asText()).as("surface for %s", id).isNotBlank();
      assertThat(entry.path("referenceRun").asText()).as("reference run for %s", id)
          .isNotBlank();

      if (entry.path("ctestRegistered").asBoolean()) registered++;
      else if (entry.path("cmakeTarget").isNull()) sourceOnly++;
      else buildOnly++;

      if (entry.path("status").asText().equals("java-green-reference-source-reviewed")) {
        green++;
        assertThat(entry.path("evidence")).as("evidence for %s", id).isNotEmpty();
        for (String counterpart : counterparts(entry)) {
          assertThat(counterpart).as("counterpart path for %s", id).doesNotStartWith("agentty/");
          assertThat(root.resolve(counterpart)).as("counterpart for %s", id).isRegularFile();
        }
      }
    }

    assertThat(registered).isEqualTo(manifest.path("summary").path("ctestRegistered").asInt());
    assertThat(buildOnly).isEqualTo(manifest.path("summary").path("buildOnlyTargets").asInt());
    assertThat(sourceOnly).isEqualTo(manifest.path("summary").path("sourceOnly").asInt());
    assertThat(green).isEqualTo(27);
  }

  private static List<String> counterparts(JsonNode entry) {
    var result = new ArrayList<String>();
    result.add(entry.path("javaCounterpart").asText());
    entry.path("supportingCounterparts").forEach(value -> result.add(value.asText()));
    assertThat(result).allSatisfy(path -> assertThat(path).isNotBlank());
    return List.copyOf(result);
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
