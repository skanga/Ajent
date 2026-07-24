package com.github.skanga.ajent.tools.workspace;

import com.github.skanga.ajent.core.workspace.WorkspaceSymbol;
import com.github.skanga.ajent.domain.Attachment;
import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Cached AgenTTY-compatible workspace file and declaration index. */
public final class WorkspaceIndex {
  private static final int FILE_CAP = 5_000;
  private static final int SYMBOL_CAP = 50_000;
  private static final long SYMBOL_FILE_LIMIT = 512L * 1024;
  private static final Set<String> SOURCE_EXTENSIONS = Set.of(
      ".cpp", ".hpp", ".c", ".h", ".cc", ".hh", ".cxx", ".hxx", ".py", ".js",
      ".ts", ".jsx", ".tsx", ".go", ".rs", ".java", ".kt", ".rb", ".swift", ".zig",
      ".lua");
  private static final List<Pattern> SYMBOL_PATTERNS = List.of(
      Pattern.compile("^\\s*(?:class|struct|enum(?:\\s+class)?|union|namespace)\\s+(\\w+)"),
      Pattern.compile("^\\s*typedef\\s+.+?\\s+(\\w+)\\s*;"),
      Pattern.compile("^[\\w:][\\w\\s*&<>:]*?\\s(\\w+)\\s*\\([^;]*?(?:\\)\\s*\\{|\\)\\s*$)"),
      Pattern.compile("^\\s*(?:def|class)\\s+(\\w+)"),
      Pattern.compile("^\\s*(?:export\\s+)?(?:async\\s+)?(?:function|class|const|let|var|type|interface)\\s+(\\w+)"),
      Pattern.compile("^func(?:\\s+\\([^)]*\\))?\\s+(\\w+)"),
      Pattern.compile("^type\\s+(\\w+)"),
      Pattern.compile("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:fn|struct|enum|trait|type|mod|const|static)\\s+(\\w+)"));

  private final Path root;
  private volatile List<String> files;
  private volatile List<WorkspaceSymbol> symbols;

  public WorkspaceIndex(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public List<String> files() {
    List<String> result = files;
    if (result != null) return result;
    synchronized (this) {
      if (files == null) files = scanFiles();
      return files;
    }
  }

  public List<WorkspaceSymbol> symbols() {
    List<WorkspaceSymbol> result = symbols;
    if (result != null) return result;
    synchronized (this) {
      if (symbols == null) symbols = scanSymbols();
      return symbols;
    }
  }

  public byte[] attachmentBody(Attachment attachment) {
    if (attachment.kind() != Attachment.Kind.FILE_REF
        && attachment.kind() != Attachment.Kind.SYMBOL) return attachment.body();
    if (attachment.body().length > 0 || attachment.path().isBlank()) return attachment.body();
    try {
      Path candidate = Path.of(attachment.path());
      if (!candidate.isAbsolute()) candidate = root.resolve(candidate);
      Path real = candidate.toRealPath();
      Path realRoot = root.toRealPath();
      if (!real.startsWith(realRoot) || !Files.isRegularFile(real)) return new byte[0];
      return Files.readAllBytes(real);
    } catch (IOException | RuntimeException exception) {
      return new byte[0];
    }
  }

  private List<String> scanFiles() {
    var result = new ArrayList<String>();
    walk(new SimpleFileVisitor<>() {
      @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
        if (attributes.isRegularFile() && result.size() < FILE_CAP) result.add(relative(file));
        return result.size() >= FILE_CAP ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
      }
    });
    result.sort(Comparator.naturalOrder());
    return List.copyOf(result);
  }

  private List<WorkspaceSymbol> scanSymbols() {
    var result = new ArrayList<WorkspaceSymbol>();
    var scanned = new int[1];
    walk(new SimpleFileVisitor<>() {
      @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
        if (!attributes.isRegularFile() || result.size() >= SYMBOL_CAP || scanned[0] >= FILE_CAP
            || !SOURCE_EXTENSIONS.contains(extension(file))
            || attributes.size() > SYMBOL_FILE_LIMIT) return FileVisitResult.CONTINUE;
        scanned[0]++;
        scanSymbols(file, result);
        return result.size() >= SYMBOL_CAP ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
      }
    });
    result.sort(Comparator.comparing(WorkspaceSymbol::name));
    return List.copyOf(result);
  }

  private void walk(SimpleFileVisitor<Path> visitor) {
    if (!Files.isDirectory(root)) return;
    try {
      Files.walkFileTree(root, new SimpleFileVisitor<>() {
        @Override public FileVisitResult preVisitDirectory(
            Path directory, BasicFileAttributes attributes) {
          if (directory.equals(root)) return FileVisitResult.CONTINUE;
          Path fileName = directory.getFileName();
          if (fileName == null) return FileVisitResult.CONTINUE;
          String name = fileName.toString();
          int depth = root.relativize(directory).getNameCount() - 1;
          return WorkspaceSandbox.shouldSkipDirectory(name) || depth > 0 && name.startsWith(".")
              ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
            throws IOException {
          return visitor.visitFile(file, attributes);
        }
        @Override public FileVisitResult visitFileFailed(Path file, IOException exception) {
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException | SecurityException ignored) { }
  }

  private void scanSymbols(Path file, List<WorkspaceSymbol> result) {
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      int lineNumber = 0;
      while ((line = reader.readLine()) != null && result.size() < SYMBOL_CAP) {
        lineNumber++;
        String stripped = line.stripLeading();
        if (stripped.startsWith("//")) continue;
        for (Pattern pattern : SYMBOL_PATTERNS) {
          var matcher = pattern.matcher(line);
          if (matcher.find()) {
            result.add(new WorkspaceSymbol(matcher.group(1), relative(file), lineNumber));
            break;
          }
        }
      }
    } catch (IOException | RuntimeException ignored) { }
  }

  private String relative(Path file) {
    try { return root.relativize(file).toString(); }
    catch (IllegalArgumentException exception) { return file.toString(); }
  }

  private static String extension(Path file) {
    Path fileName = file.getFileName();
    if (fileName == null) return "";
    String name = fileName.toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
  }
}
