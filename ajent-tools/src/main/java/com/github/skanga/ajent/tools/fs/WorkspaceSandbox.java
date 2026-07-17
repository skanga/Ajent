package com.github.skanga.ajent.tools.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Component-wise, symlink-safe filesystem boundary. */
public final class WorkspaceSandbox {
  private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
      ".git", "node_modules", "build", "target", "__pycache__", ".cache", "vendor",
      "dist", "out", ".next", ".venv", "cmake-build-debug", "cmake-build-release",
      ".idea", ".vscode", "_deps", "third_party", "thirdparty", "3rdparty", "external");

  private final Path workspaceRoot;
  private final Path workingDirectory;
  private final Path home;
  private final List<Path> readRoots = new ArrayList<>();

  public WorkspaceSandbox(Path workspaceRoot, Path workingDirectory, Path home) {
    this.workspaceRoot = canonicalizeLoose(Objects.requireNonNull(workspaceRoot, "workspaceRoot"))
        .orElseGet(() -> workspaceRoot.toAbsolutePath().normalize());
    this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
        .toAbsolutePath().normalize();
    this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
  }

  public Path workspaceRoot() { return workspaceRoot; }

  public Path normalize(String raw) {
    String value = trimSpacesAndTabs(Objects.requireNonNull(raw, "raw"));
    if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
        || (value.startsWith("'") && value.endsWith("'")))) {
      value = value.substring(1, value.length() - 1);
    }
    Path path;
    if (value.equals("~")) path = home;
    else if (value.startsWith("~/")) path = home.resolve(value.substring(2));
    else path = Path.of(value);
    if (!path.isAbsolute()) path = workingDirectory.resolve(path);
    return path.toAbsolutePath().normalize();
  }

  public boolean isWithin(Path target) {
    if (target.toString().isEmpty()) return false;
    return canonicalizeLoose(target).map(path -> path.startsWith(workspaceRoot)).orElse(false);
  }

  public synchronized void allowReadRoot(Path root) {
    canonicalizeLoose(root).ifPresent(canonical -> {
      if (!readRoots.contains(canonical)) readRoots.add(canonical);
    });
  }

  public synchronized boolean isReadable(Path target) {
    Optional<Path> canonical = canonicalizeLoose(target);
    if (canonical.isEmpty()) return false;
    if (canonical.orElseThrow().startsWith(workspaceRoot)) return true;
    return readRoots.stream().anyMatch(canonical.orElseThrow()::startsWith);
  }

  public static boolean shouldSkipDirectory(String name) {
    return SKIPPED_DIRECTORIES.contains(name);
  }

  private static Optional<Path> canonicalizeLoose(Path target) {
    try {
      Path absolute = target.toAbsolutePath().normalize();
      var missing = new ArrayDeque<Path>();
      Path existing = absolute;
      while (existing != null && !Files.exists(existing)) {
        Path name = existing.getFileName();
        if (name != null) missing.addFirst(name);
        existing = existing.getParent();
      }
      if (existing == null) return Optional.empty();
      Path canonical = existing.toRealPath();
      for (Path component : missing) canonical = canonical.resolve(component);
      return Optional.of(canonical.normalize());
    } catch (IOException | SecurityException exception) {
      return Optional.empty();
    }
  }

  private static String trimSpacesAndTabs(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) start++;
    while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) end--;
    return value.substring(start, end);
  }
}
