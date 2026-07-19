package com.github.skanga.ajent.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.cli.AjentCli;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

final class ReleaseConfigurationTest {
  @Test
  void releaseVersionArchivesSbomAndWorkflowStayAligned() throws Exception {
    Path root = repositoryRoot();
    var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(root.resolve("pom.xml").toFile());
    String version = document.getDocumentElement().getElementsByTagName("version")
        .item(0).getTextContent();
    String assembly = Files.readString(
        root.resolve("ajent-cli/src/assembly/distribution.xml"));
    String workflow = Files.readString(root.resolve(".github/workflows/release.yml"));

    assertThat(version).isEqualTo(AjentCli.VERSION).doesNotContain("SNAPSHOT");
    assertThat(assembly)
        .contains("<format>zip</format>", "<format>tar.gz</format>")
        .contains("ajent.jar", "ajent.cmd", "README.md", "LICENSE", "NOTICE",
            "CHANGELOG.md", "ajent-sbom.json", "../docs")
        .doesNotContain("agentty");
    assertThat(workflow)
        .contains("tags:", "\"v*\"", "java-version: \"25\"", "bash ./mvnw -q verify",
            "mvn-toolchain-id: jdk-25", "cp \"$HOME/.m2/toolchains.xml\"",
            "sha256sum", "gh release create", "ajent-sbom.json")
        .contains("test \"$version\" = \"${GITHUB_REF_NAME#v}\"")
        .contains("test \"$version\" = \"${version%-SNAPSHOT}\"");
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.isRegularFile(current.resolve(".github/workflows/ci.yml"))) return current;
      current = current.getParent();
    }
    throw new IllegalStateException("Ajent repository root not found");
  }
}
