package com.github.skanga.ajent.tools.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.Attachment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceIndexTest {
  @Test void indexesFilesOnceAndSkipsGeneratedAndNestedHiddenDirectories(@TempDir Path root)
      throws Exception {
    write(root, "src/A.java", "class A {}\n");
    write(root, "README.md", "docs\n");
    write(root, ".github/workflows/build.yml", "name: build\n");
    write(root, "target/generated.txt", "skip\n");
    write(root, "src/.secret/hidden.java", "class Hidden {}\n");
    var index = new WorkspaceIndex(root);

    assertThat(index.files()).extracting(path -> path.replace('\\', '/'))
        .containsExactly(".github/workflows/build.yml", "README.md", "src/A.java");
    write(root, "later.txt", "later\n");
    assertThat(index.files()).noneMatch(path -> path.endsWith("later.txt"));
  }

  @Test void extractsDeclarationsAcrossNativeLanguagePatterns(@TempDir Path root) throws Exception {
    write(root, "src/A.java", "// class Fake\nclass Alpha {\n  int x;\n}\n");
    write(root, "app.py", "def work(x):\n    pass\nclass PyType:\n    pass\n");
    write(root, "main.ts", "export async function fetchIt() {}\ninterface Shape {}\n");
    write(root, "main.rs", "pub struct RustType {}\npub fn rust_fn() {}\n");
    write(root, "ignored.md", "class NotSource {}\n");

    var index = new WorkspaceIndex(root);
    var symbols = index.symbols();
    assertThat(symbols)
        .extracting(symbol -> symbol.name() + ":" + symbol.lineNumber())
        .containsExactly("Alpha:2", "PyType:3", "RustType:1", "Shape:2", "fetchIt:1",
            "rust_fn:2", "work:1");
    assertThat(index.symbols()).isSameAs(symbols);
  }

  @Test void ignoresExtensionlessAndOversizedSourceFiles(@TempDir Path root) throws Exception {
    write(root, "Makefile", "class Extensionless {}\n");
    write(root, "huge.java", " ".repeat(512 * 1024) + "\nclass TooLarge {}\n");

    assertThat(new WorkspaceIndex(root).symbols()).isEmpty();
  }

  @Test void attachmentReadsLatestContainedBytesAndRejectsEscapes(@TempDir Path root)
      throws Exception {
    Path file = write(root, "src/A.java", "old");
    var index = new WorkspaceIndex(root);
    var fileRef = attachment(Attachment.Kind.FILE_REF, "src/A.java", new byte[0]);
    Files.writeString(file, "new");
    assertThat(new String(index.attachmentBody(fileRef), StandardCharsets.UTF_8)).isEqualTo("new");

    Path outside = Files.createTempFile("ajent-outside", ".txt");
    assertThat(index.attachmentBody(attachment(
        Attachment.Kind.FILE_REF, outside.toString(), new byte[0]))).isEmpty();
    assertThat(index.attachmentBody(attachment(
        Attachment.Kind.FILE_REF, "missing.txt", new byte[0]))).isEmpty();
    assertThat(index.attachmentBody(attachment(
        Attachment.Kind.FILE_REF, "src/A.java", "fixed".getBytes(StandardCharsets.UTF_8))))
        .asString(StandardCharsets.UTF_8).isEqualTo("fixed");
    assertThat(index.attachmentBody(attachment(
        Attachment.Kind.SYMBOL, "src/A.java", new byte[0])))
        .asString(StandardCharsets.UTF_8).isEqualTo("new");
    assertThat(index.attachmentBody(attachment(
        Attachment.Kind.PASTE, "src/A.java", "inline".getBytes(StandardCharsets.UTF_8))))
        .asString(StandardCharsets.UTF_8).isEqualTo("inline");
    assertThat(index.attachmentBody(attachment(
        Attachment.Kind.FILE_REF, "   ", new byte[0]))).isEmpty();
    assertThat(index.attachmentBody(attachment(
        Attachment.Kind.FILE_REF, "src", new byte[0]))).isEmpty();
    assertThat(index.attachmentBody(attachment(
        Attachment.Kind.FILE_REF, "\u0000", new byte[0]))).isEmpty();
  }

  @Test void absentWorkspaceProducesEmptyIndexes(@TempDir Path root) throws Exception {
    Path missing = root.resolve("missing");
    var index = new WorkspaceIndex(missing);

    assertThat(index.files()).isEmpty();
    assertThat(index.symbols()).isEmpty();
  }

  private static Attachment attachment(Attachment.Kind kind, String path, byte[] body) {
    return new Attachment(kind, body, path, "", "", 0, 0, body.length);
  }

  private static Path write(Path root, String relative, String body) throws Exception {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, body);
    return file;
  }
}
