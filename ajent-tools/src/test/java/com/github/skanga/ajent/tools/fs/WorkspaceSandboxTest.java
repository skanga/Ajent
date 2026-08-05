package com.github.skanga.ajent.tools.fs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSandboxTest {

  @Test
  void allowsExistingAndFutureWorkspacePathsButRejectsPrefixSiblings(@TempDir Path directory)
      throws Exception {
    Path root = Files.createDirectories(directory.resolve("project"));
    Path sibling = Files.createDirectories(directory.resolve("project-other"));
    var sandbox = new WorkspaceSandbox(root, root, directory.resolve("home"));

    assertThat(sandbox.isWithin(root)).isTrue();
    assertThat(sandbox.isWithin(root.resolve("new/sub/file.txt"))).isTrue();
    assertThat(sandbox.isWithin(sibling.resolve("file.txt"))).isFalse();
    assertThat(sandbox.isWithin(Path.of(""))).isFalse();
  }

  @Test
  void normalizesQuotesWhitespaceRelativePathsAndTilde(@TempDir Path directory)
      throws Exception {
    Path root = Files.createDirectories(directory.resolve("project"));
    Path home = Files.createDirectories(root.resolve("home"));
    var sandbox = new WorkspaceSandbox(root, root, home);

    assertThat(sandbox.normalize("  'src/file.txt'\t"))
        .isEqualTo(root.resolve("src/file.txt").toAbsolutePath().normalize());
    assertThat(sandbox.normalize("~/notes.txt"))
        .isEqualTo(home.resolve("notes.txt").toAbsolutePath().normalize());
    assertThat(sandbox.normalize("~"))
        .isEqualTo(home.toAbsolutePath().normalize());
  }

  @Test
  void readAllowlistDoesNotGrantWriteAccess(@TempDir Path directory) throws Exception {
    Path root = Files.createDirectories(directory.resolve("project"));
    Path skills = Files.createDirectories(directory.resolve("skills"));
    Path skill = Files.writeString(skills.resolve("SKILL.md"), "instructions");
    var sandbox = new WorkspaceSandbox(root, root, directory.resolve("home"));

    assertThat(sandbox.isReadable(skill)).isFalse();
    sandbox.allowReadRoot(skills);
    assertThat(sandbox.isReadable(skill)).isTrue();
    assertThat(sandbox.isWithin(skill)).isFalse();
  }

  @Test
  void symlinkCannotEscapeWorkspaceWhenPlatformAllowsCreatingOne(@TempDir Path directory)
      throws Exception {
    Path root = Files.createDirectories(directory.resolve("project"));
    Path outside = Files.createDirectories(directory.resolve("outside"));
    Path link = root.resolve("escape");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (IOException | UnsupportedOperationException exception) {
      return;
    }

    var sandbox = new WorkspaceSandbox(root, root, directory.resolve("home"));
    assertThat(sandbox.isWithin(link.resolve("secret.txt"))).isFalse();
  }

  @Test
  void skipListMatchesAjent() {
    for (String name : new String[] {".git", "node_modules", "build", "target", "__pycache__",
        ".cache", "vendor", "dist", "out", ".next", ".venv", "cmake-build-debug",
        "cmake-build-release", ".idea", ".vscode", "_deps", "third_party", "thirdparty",
        "3rdparty", "external"}) {
      assertThat(WorkspaceSandbox.shouldSkipDirectory(name)).as(name).isTrue();
    }
    assertThat(WorkspaceSandbox.shouldSkipDirectory("src")).isFalse();
  }
}
