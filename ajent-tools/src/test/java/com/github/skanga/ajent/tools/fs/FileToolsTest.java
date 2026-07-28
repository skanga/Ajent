package com.github.skanga.ajent.tools.fs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileToolsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void portsPinnedMcpCppFilesystemEndToEndContract(@TempDir Path directory) throws Exception {
    var tools = tools(directory);
    Path file = directory.resolve("hello.txt");

    ToolResult.Success written = success(tools.execute("write", object()
        .put("file_path", file.toString()).put("content", "line1\nline2\nline3\n")));
    assertThat(written.output().change()).isPresent().get().satisfies(change -> {
      assertThat(change.existedBefore()).isFalse();
      assertThat(change.path()).isEqualTo(file.toAbsolutePath().normalize().toString());
      assertThat(change.added()).isEqualTo(3);
      assertThat(change.after()).isEqualTo("line1\nline2\nline3\n");
      assertThat(change.hunks()).singleElement().satisfies(hunk ->
          assertThat(hunk.patch()).contains("+line1", "+line3"));
    });

    ToolResult.Success read = success(tools.execute("read", object().put("path", file.toString())));
    assertThat(read.output().text()).contains("line1", "line3");
    assertThat(success(tools.execute("read", object().put("path", file.toString())))
        .output().text()).contains("File unchanged since last read");

    ObjectNode edit = object().put("old_text", "line2").put("new_text", "LINE-TWO");
    ToolResult.Success edited = success(tools.execute("edit", object()
        .put("path", file.toString()).set("edits", JSON.createArrayNode().add(edit))));
    assertThat(edited.output().text())
        .startsWith("Edited " + file.toAbsolutePath().normalize() + " (1+ 1-):\n\n```diff\n")
        .contains("--- a/" + file.toAbsolutePath().normalize(), "-line2", "+LINE-TWO")
        .endsWith("\n```");
    assertThat(edited.output().change()).isPresent().get().satisfies(change -> {
      assertThat(change.existedBefore()).isTrue();
      assertThat(change.before()).contains("line2");
      assertThat(change.after()).contains("LINE-TWO");
      assertThat(change.hunks()).singleElement().satisfies(hunk ->
          assertThat(hunk.patch()).contains("-line2", "+LINE-TWO"));
    });
    assertThat(Files.readString(file)).contains("LINE-TWO").doesNotContain("line2");

    Path duplicate = directory.resolve("dup.txt");
    success(tools.execute("write", object().put("file_path", duplicate.toString())
        .put("content", "x\nx\ny\n")));
    ToolResult.Failure ambiguous = failure(tools.execute("edit", object()
        .put("path", duplicate.toString()).set("edits", JSON.createArrayNode().add(
            object().put("old_text", "x").put("new_text", "z")))));
    assertThat(ambiguous.error().kind()).isEqualTo(ToolErrorKind.AMBIGUOUS);
    assertThat(ambiguous.error().detail()).contains("appears");

    assertThat(success(tools.execute("list_dir", object().put("path", directory.toString())))
        .output().text()).contains("hello.txt", "dup.txt");
  }

  @Test
  void overwritePreservesExecutablePermissionsWhenSupported(@TempDir Path directory)
      throws Exception {
    if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) return;
    Path file = Files.writeString(directory.resolve("script.sh"), "old");
    var permissions = java.util.EnumSet.of(
        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
    Files.setPosixFilePermissions(file, permissions);

    success(tools(directory).execute("write",
        object().put("path", file.toString()).put("content", "new")));

    assertThat(Files.getPosixFilePermissions(file)).isEqualTo(permissions);
  }

  @Test
  void readSupportsAliasesRangesErrorsAndBinaryGuard(@TempDir Path directory) throws Exception {
    var tools = tools(directory);
    Path text = Files.writeString(directory.resolve("lines.txt"), "one\r\ntwo\r\nthree\r\nfour");

    ToolResult.Success range = success(tools.execute("read", object()
        .put("file", text.toString()).put("start_line", "2").put("end_line", 3)));
    assertThat(range.output().text()).startsWith("two\nthree\n")
        .doesNotContain("four\n").contains("showing lines 2-3 of 4");

    assertThat(failure(tools.execute("read", object().put("path", directory.resolve("nope").toString())))
        .error().kind()).isEqualTo(ToolErrorKind.NOT_FOUND);
    assertThat(failure(tools.execute("read", object().put("path", directory.toString())))
        .error().kind()).isEqualTo(ToolErrorKind.NOT_A_FILE);
    Path binary = directory.resolve("binary.bin");
    Files.write(binary, new byte[] {1, 0, 2});
    assertThat(failure(tools.execute("read", object().put("path", binary.toString())))
        .error().kind()).isEqualTo(ToolErrorKind.BINARY);
    assertThat(failure(tools.execute("read", object()))
        .error().kind()).isEqualTo(ToolErrorKind.INVALID_ARGS);
  }

  @Test
  void largeImplicitReadsReturnNativeOutlineOrUtf8SafePeek(@TempDir Path directory)
      throws Exception {
    var tools = tools(directory);
    Path dump = Files.writeString(directory.resolve("dump.txt"), "🙂".repeat(25_000));
    String peek = success(tools.execute("read", object().put("path", dump.toString())))
        .output().text();
    assertThat(peek).contains("First 1 KiB of a 97 KiB file", "the file has 1 lines total")
        .doesNotContain("�");
    assertThat(peek.getBytes(StandardCharsets.UTF_8).length).isLessThan(2_000);

    Path source = directory.resolve("large.java");
    Files.writeString(source, "class LargeFile {\n  void usefulMethod() {\n  }\n}\n"
        + "// padding\n".repeat(4_000));
    String outline = success(tools.execute("read", object().put("path", source.toString())))
        .output().text();
    assertThat(outline).contains("File outline retrieved", "[L1] class LargeFile {",
        "[L2] void usefulMethod() {", "NEXT STEPS");
  }

  @Test
  void writeIsAtomicHandlesNoChangeAndRejectsDirectories(@TempDir Path directory) throws Exception {
    var tools = tools(directory);
    Path nested = directory.resolve("a/b/new.txt");
    ObjectNode write = object().put("path", nested.toString());
    write.putArray("text").add("a").add("b");
    ToolResult.Success created = success(tools.execute("write", write));
    assertThat(created.output().text()).contains("Created");
    assertThat(Files.readString(nested)).isEqualTo("a\nb");
    assertThat(Files.exists(nested.resolveSibling("new.txt.tmp"))).isFalse();
    assertThat(success(tools.execute("write", object()
        .put("path", nested.toString()).put("content", "a\nb"))).output().text())
        .isEqualTo("File already matches content — no changes written.");
    assertThat(failure(tools.execute("write", object()
        .put("path", directory.toString()).put("content", "x"))).error().kind())
        .isEqualTo(ToolErrorKind.NOT_A_FILE);
  }

  @Test
  void editWarnsWhenFileChangedSinceAReadAndRefreshesTheSharedSnapshot(@TempDir Path directory)
      throws Exception {
    Path file = Files.writeString(directory.resolve("stale-edit.txt"), "alpha\nbeta\n");
    var observingTools = tools(directory);
    success(observingTools.execute("read", object().put("path", file.toString())));

    FileTime changed = FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2_000);
    Files.writeString(file, "external\nbeta\n");
    Files.setLastModifiedTime(file, changed);

    ObjectNode firstEdit = object().put("old_text", "beta").put("new_text", "BETA");
    String warning = success(tools(directory).execute("edit", object().put("path", file.toString())
        .set("edits", JSON.createArrayNode().add(firstEdit)))).output().text();
    assertThat(warning)
        .startsWith("⚠  The file has changed on disk since the last time a tool observed it this session.")
        .contains("The edit was applied to the CURRENT bytes", "Edited");
    assertThat(Files.readString(file)).isEqualTo("external\nBETA\n");

    ObjectNode secondEdit = object().put("old_text", "BETA").put("new_text", "final");
    String refreshed = success(observingTools.execute("edit", object().put("path", file.toString())
        .set("edits", JSON.createArrayNode().add(secondEdit)))).output().text();
    assertThat(refreshed).startsWith("Edited").doesNotContain("changed on disk");
  }

  @Test
  void writeWarnsOnSameTimestampSizeChangeAndThenRefreshesTheSnapshot(@TempDir Path directory)
      throws Exception {
    Path file = Files.writeString(directory.resolve("stale-write.txt"), "seen");
    var tools = tools(directory);
    success(tools.execute("read", object().put("path", file.toString())));
    FileTime observed = Files.getLastModifiedTime(file);

    Files.writeString(file, "externally enlarged");
    Files.setLastModifiedTime(file, observed);

    String warning = success(tools.execute("write", object().put("path", file.toString())
        .put("content", "replacement"))).output().text();
    assertThat(warning)
        .startsWith("⚠  The file has changed on disk since the last time a tool observed it this session.")
        .contains("The write OVERWROTE those changes", "Overwrote");

    String refreshed = success(tools.execute("write", object().put("path", file.toString())
        .put("content", "replacement again"))).output().text();
    assertThat(refreshed).startsWith("Overwrote").doesNotContain("changed on disk");
  }

  @Test
  void listSortsDirectoriesFirstSkipsRecursiveBuildContentsAndRejectsEscape(@TempDir Path directory)
      throws Exception {
    Path workspace = Files.createDirectories(directory.resolve("workspace"));
    Files.writeString(workspace.resolve("z.txt"), "z");
    Files.createDirectories(workspace.resolve("a-dir"));
    Path build = Files.createDirectories(workspace.resolve("build"));
    Files.writeString(build.resolve("hidden.txt"), "hidden");
    var tools = tools(workspace);

    String flat = success(tools.execute("list_dir", object().put("path", workspace.toString())))
        .output().text();
    assertThat(flat.indexOf("a-dir/")).isLessThan(flat.indexOf("z.txt"));
    String recursive = success(tools.execute("list_dir", object()
        .put("path", workspace.toString()).put("recursive", true))).output().text();
    assertThat(recursive).contains("build/").doesNotContain("hidden.txt");

    ToolResult.Failure escaped = failure(tools.execute(
        "read", object().put("path", directory.resolve("outside.txt").toString())));
    assertThat(escaped.error().kind()).isEqualTo(ToolErrorKind.OUT_OF_WORKSPACE);
    assertThat(escaped.error().detail())
        .contains("Restart ajent in a parent directory", "--workspace <dir>");
  }

  @Test
  void sourceRelevantFailureAndNoOpPathsRemainTyped(@TempDir Path directory) throws Exception {
    var tools = tools(directory);
    assertThat(failure(tools.execute("unknown", object())).error().kind())
        .isEqualTo(ToolErrorKind.UNKNOWN);
    assertThat(failure(tools.execute("write", object())).error().kind())
        .isEqualTo(ToolErrorKind.INVALID_ARGS);
    assertThat(failure(tools.execute("write", object().put("path", "x"))).error().kind())
        .isEqualTo(ToolErrorKind.INVALID_ARGS);
    assertThat(failure(tools.execute("write", object().put("path", "large")
        .put("content", "x".repeat(5 * 1024 * 1024 + 1)))).error().kind())
        .isEqualTo(ToolErrorKind.TOO_LARGE);

    Path parentFile = Files.writeString(directory.resolve("parent"), "not a directory");
    assertThat(failure(tools.execute("write", object()
        .put("path", parentFile.resolve("child").toString()).put("content", "x"))).error().kind())
        .isEqualTo(ToolErrorKind.NOT_A_DIRECTORY);

    Path text = Files.writeString(directory.resolve("edit.txt"), "alpha\nbeta\n");
    ObjectNode noop = object().put("old_text", "alpha").put("new_text", "alpha");
    assertThat(success(tools.execute("edit", object().put("path", text.toString())
        .set("edits", JSON.createArrayNode().add(noop)))).output().text())
        .contains("No edits were applied");
    ObjectNode missing = object().put("old_text", "missing").put("new_text", "new");
    assertThat(failure(tools.execute("edit", object().put("path", text.toString())
        .set("edits", JSON.createArrayNode().add(missing)))).error().kind())
        .isEqualTo(ToolErrorKind.NO_MATCH);
    assertThat(failure(tools.execute("edit", object().put("path", directory.toString())
        .set("edits", JSON.createArrayNode().add(missing)))).error().kind())
        .isEqualTo(ToolErrorKind.NOT_A_FILE);
    Path binary = directory.resolve("edit.bin");
    Files.write(binary, new byte[] {0, 1});
    assertThat(failure(tools.execute("edit", object().put("path", binary.toString())
        .set("edits", JSON.createArrayNode().add(missing)))).error().kind())
        .isEqualTo(ToolErrorKind.BINARY);

    Path empty = Files.createDirectory(directory.resolve("empty"));
    assertThat(success(tools.execute("list_dir", object().put("path", empty.toString())))
        .output().text()).isEqualTo("empty directory");
    assertThat(failure(tools.execute("list_dir", object().put("path", text.toString())))
        .error().kind()).isEqualTo(ToolErrorKind.NOT_A_DIRECTORY);
    assertThat(failure(tools.execute("list_dir", object().put("path", "absent")))
        .error().kind()).isEqualTo(ToolErrorKind.NOT_FOUND);
  }

  @Test
  void displayDescriptionsPrefixSuccessfulToolOutput(@TempDir Path directory) throws Exception {
    var tools = tools(directory);
    Path file = directory.resolve("described.txt");
    assertThat(success(tools.execute("write", object().put("path", file.toString())
        .put("content", "body").put("display_description", "Creating fixture"))).output().text())
        .startsWith("Creating fixture\nCreated");
    assertThat(success(tools.execute("read", object().put("path", file.toString())
        .put("display_description", "Reading fixture"))).output().text())
        .startsWith("Reading fixture\nbody");
    assertThat(success(tools.execute("list_dir", object().put("path", directory.toString())
        .put("display_description", "Listing fixture"))).output().text())
        .startsWith("Listing fixture\n");
  }

  @Test
  void fileStateCacheEvictsLeastRecentlyUsedBeyondCapacity(@TempDir Path directory)
      throws Exception {
    int previous = FileTools.fileStateCacheCapacity;
    FileTools.resetFileStateCaches();
    FileTools.fileStateCacheCapacity = 2;
    try {
      var tools = tools(directory);
      Path a = Files.writeString(directory.resolve("a.txt"), "aaa\n");
      Path b = Files.writeString(directory.resolve("b.txt"), "bbb\n");
      Path c = Files.writeString(directory.resolve("c.txt"), "ccc\n");
      // Read a, then b, then c: with capacity 2 the least-recently-used entry (a) is evicted.
      success(tools.execute("read", object().put("path", a.toString())));
      success(tools.execute("read", object().put("path", b.toString())));
      success(tools.execute("read", object().put("path", c.toString())));
      // c is still cached, so a re-read reports "unchanged"...
      assertThat(success(tools.execute("read", object().put("path", c.toString())))
          .output().text()).contains("File unchanged since last read");
      // ...but a was evicted, so its re-read returns real content, not the stale-read signal.
      assertThat(success(tools.execute("read", object().put("path", a.toString())))
          .output().text()).contains("aaa").doesNotContain("File unchanged since last read");
    } finally {
      FileTools.fileStateCacheCapacity = previous;
      FileTools.resetFileStateCaches();
    }
  }

  private static FileTools tools(Path root) {
    return new FileTools(new WorkspaceSandbox(root, root, root));
  }

  private static ObjectNode object() { return JSON.createObjectNode(); }

  private static ToolResult.Success success(ToolResult result) {
    assertThat(result).isInstanceOf(ToolResult.Success.class);
    return (ToolResult.Success) result;
  }

  private static ToolResult.Failure failure(ToolResult result) {
    assertThat(result).isInstanceOf(ToolResult.Failure.class);
    return (ToolResult.Failure) result;
  }
}
