package com.github.skanga.ajent.tools.skills;

import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import com.github.skanga.ajent.tools.host.HostServices;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Bounded agentskills.io discovery, activation, catalog, and validation engine. */
public final class SkillEngine {
  public static final int MAX_SKILLS = 64;
  public static final int MAX_BODY_BYTES = 64 * 1024;
  public static final int MAX_RESOURCES = 32;

  private record Root(Path path, String source) {}
  private record Field(String key, String value) {}
  private record Parsed(String name, String description, String body, String compatibility,
                        String allowedTools, String license, boolean userOnly,
                        List<Skill.Metadata> metadata) {}

  private final Path home;
  private final Path workspace;
  private final WorkspaceSandbox sandbox;
  private final Set<String> activations = new HashSet<>();

  public SkillEngine(Path home, Path workspace, WorkspaceSandbox sandbox) {
    this.home = home.toAbsolutePath().normalize();
    this.workspace = workspace.toAbsolutePath().normalize();
    this.sandbox = sandbox;
  }

  public synchronized List<Skill> all() {
    var skills = new ArrayList<Skill>();
    Set<String> names = new HashSet<>();
    for (Root root : roots()) scan(root, skills, names);
    return List.copyOf(skills);
  }

  public Optional<Skill> find(String name) {
    return all().stream().filter(skill -> skill.name().equals(name)).findFirst();
  }

  public String catalogBlock() {
    List<Skill> eligible = all().stream().filter(skill -> !skill.userOnly()).toList();
    if (eligible.isEmpty()) return "";
    var output = new StringBuilder("\n\n<skills>\n")
        .append("On-demand skills are available. Each is a focused instruction doc you can load IN FULL ")
        .append("with the `skill` tool when its task comes up — don't guess the contents, load it. Skills ")
        .append("may bundle resource files (scripts/, references/, assets/); the activation result lists ")
        .append("them — `read` the specific file when the instructions reference it, resolving relative ")
        .append("paths against the skill directory the result names. Listed: name — description.\n");
    for (Skill skill : eligible) {
      output.append("- ").append(skill.name());
      if (!skill.description().isEmpty()) output.append(" — ").append(skill.description());
      output.append('\n');
    }
    return output.append("</skills>").toString();
  }

  public String activationPayload(Skill skill) {
    var output = new StringBuilder("<skill_content name=\"").append(skill.name()).append("\">\n");
    if (!skill.description().isEmpty()) output.append(skill.description()).append("\n\n");
    if (!skill.compatibility().isEmpty())
      output.append("Compatibility: ").append(skill.compatibility()).append("\n\n");
    if (!skill.license().isEmpty()) output.append("License: ").append(skill.license()).append("\n\n");
    if (!skill.allowedTools().isEmpty()) output.append("Allowed tools: ").append(skill.allowedTools())
        .append(" — prefer these tools while following this skill.\n\n");
    output.append(skill.body()).append('\n');
    if (skill.directory() != null) output.append("\nSkill directory: ").append(skill.directory()).append('\n')
        .append("Relative paths in this skill resolve against the skill directory — use absolute paths in tool calls.\n");
    if (!skill.resources().isEmpty()) {
      output.append("\n<skill_resources>\n");
      for (String resource : skill.resources()) output.append("  ").append(resource).append('\n');
      if (skill.resources().size() >= MAX_RESOURCES)
        output.append("  (listing capped — there may be more files)\n");
      output.append("</skill_resources>\nResources are NOT loaded — `read` the specific file when the instructions call for it.\n");
    }
    return output.append("</skill_content>").toString();
  }

  public synchronized boolean noteActivated(String name) { return activations.add(name); }
  public synchronized void resetActivations() { activations.clear(); }

  public List<String> lint(Skill skill) {
    var diagnostics = new ArrayList<String>();
    if (skill.name().isEmpty()) diagnostics.add("name is empty");
    if (utf8Length(skill.name()) > 64) diagnostics.add("name exceeds 64 characters");
    boolean badCharacter = false;
    boolean previousHyphen = false;
    boolean doubleHyphen = false;
    for (int index = 0; index < skill.name().length(); index++) {
      char character = skill.name().charAt(index);
      boolean valid = character >= 'a' && character <= 'z' || character >= '0'
          && character <= '9' || character == '-';
      if (!valid) badCharacter = true;
      if (character == '-' && previousHyphen) doubleHyphen = true;
      previousHyphen = character == '-';
    }
    if (badCharacter) diagnostics.add("name has invalid characters (allowed: a-z, 0-9, hyphen)");
    if (!skill.name().isEmpty() && (skill.name().charAt(0) == '-'
        || skill.name().charAt(skill.name().length() - 1) == '-'))
      diagnostics.add("name must not start or end with a hyphen");
    if (doubleHyphen) diagnostics.add("name contains consecutive hyphens");
    Path directoryName = skill.directory() == null ? null : skill.directory().getFileName();
    if (directoryName != null && !directoryName.toString().equals(skill.name())) {
      diagnostics.add("name does not match parent directory '" + directoryName + "'");
    }
    if (skill.description().isEmpty()) diagnostics.add("description is missing");
    if (utf8Length(skill.description()) > 1024) diagnostics.add("description exceeds 1024 characters");
    if (utf8Length(skill.compatibility()) > 500)
      diagnostics.add("compatibility exceeds 500 characters");
    long lines = 1 + skill.body().chars().filter(character -> character == '\n').count();
    if (lines > 500) diagnostics.add("body is " + lines
        + " lines (spec recommends ≤ 500 — move detail to references/)");
    return List.copyOf(diagnostics);
  }

  public HostServices.SkillResolver resolver() {
    return name -> {
      Optional<Skill> found = find(name);
      if (found.isEmpty()) {
        String available = String.join(", ", all().stream().map(Skill::name).toList());
        String error = "no skill named '" + name + "'" + (available.isEmpty()
            ? " — no skills are installed in this workspace" : " — available: " + available);
        return new HostServices.SkillResolution(Optional.empty(), error);
      }
      Skill skill = found.orElseThrow();
      String body = noteActivated(skill.name()) ? activationPayload(skill)
          : "Skill '" + skill.name() + "' is already active in this session — its instructions are in "
              + "an earlier tool_result. Refer to that instead of re-loading.";
      return new HostServices.SkillResolution(Optional.of(body), "");
    };
  }

  private List<Root> roots() {
    return List.of(new Root(workspace.resolve(".agentty/skills"), "project"),
        new Root(workspace.resolve(".agents/skills"), "project"),
        new Root(workspace.resolve(".claude/skills"), "project"),
        new Root(home.resolve(".agentty/skills"), "user"),
        new Root(home.resolve(".agents/skills"), "user"),
        new Root(home.resolve(".claude/skills"), "user"));
  }

  private void scan(Root root, List<Skill> output, Set<String> names) {
    if (!Files.isDirectory(root.path())) return;
    List<Path> directories;
    try (Stream<Path> stream = Files.list(root.path())) {
      directories = stream.filter(Files::isDirectory).sorted().toList();
    } catch (IOException | SecurityException exception) {
      return;
    }
    for (Path directory : directories) {
      if (output.size() >= MAX_SKILLS) return;
      String raw = readCapped(directory.resolve("SKILL.md"));
      if (raw.isEmpty()) continue;
      Path directoryName = directory.getFileName();
      if (directoryName == null) continue;
      Parsed parsed = parse(raw, directoryName.toString());
      if (parsed.name().isEmpty() || !names.add(parsed.name())) continue;
      Path canonical = canonical(directory);
      List<String> resources = resources(canonical);
      if (sandbox != null) sandbox.allowReadRoot(canonical);
      output.add(new Skill(parsed.name(), parsed.description(), parsed.body(), root.source(),
          parsed.compatibility(), parsed.allowedTools(), parsed.license(), parsed.userOnly(),
          canonical, parsed.metadata(), resources));
    }
  }

  private static Parsed parse(String raw, String slug) {
    String[] lines = raw.split("\n", -1);
    String name = slug;
    String description = "";
    String compatibility = "";
    String allowedTools = "";
    String license = "";
    boolean userOnly = false;
    var metadata = new ArrayList<Skill.Metadata>();
    int bodyLine = 0;
    if (lines.length > 0 && trim(lines[0]).equals("---")) {
      boolean inMetadata = false;
      String blockKey = null;
      boolean fold = false;
      var block = new StringBuilder();
      for (int index = 1; index < lines.length; index++) {
        String line = stripCarriageReturn(lines[index]);
        if (trim(line).equals("---")) {
          if (blockKey != null) {
            String[] values = assign(blockKey, block.toString(), name, description, compatibility,
                allowedTools, license);
            name = values[0]; description = values[1]; compatibility = values[2];
            allowedTools = values[3]; license = values[4];
          }
          bodyLine = index + 1;
          break;
        }
        int indent = indentation(line);
        if (blockKey != null) {
          if (indent > 0 || trim(line).isEmpty()) {
            String value = trim(line);
            if (!value.isEmpty()) {
              if (!block.isEmpty()) block.append(fold ? ' ' : '\n');
              block.append(value);
            }
            continue;
          }
          String[] values = assign(blockKey, block.toString(), name, description, compatibility,
              allowedTools, license);
          name = values[0]; description = values[1]; compatibility = values[2];
          allowedTools = values[3]; license = values[4];
          blockKey = null;
          block.setLength(0);
        }
        if (inMetadata && indent > 0) {
          parseField(line).ifPresent(field -> metadata.add(new Skill.Metadata(field.key(), field.value())));
          continue;
        }
        inMetadata = false;
        Optional<Field> possible = parseField(line);
        if (possible.isEmpty()) continue;
        Field field = possible.orElseThrow();
        if (field.key().equals("metadata") && field.value().isEmpty()) {
          inMetadata = true;
          continue;
        }
        String value = field.value();
        if (field.key().equals("disable-model-invocation")) {
          userOnly = value.equals("true") || value.equals("1") || value.equals("yes");
        } else if (value.equals("|") || value.equals("|-") || value.equals("|+")
            || value.equals(">") || value.equals(">-") || value.equals(">+")) {
          if (isScalarField(field.key())) {
            blockKey = field.key();
            fold = value.charAt(0) == '>';
          }
        } else {
          String[] values = assign(field.key(), value, name, description, compatibility,
              allowedTools, license);
          name = values[0]; description = values[1]; compatibility = values[2];
          allowedTools = values[3]; license = values[4];
        }
      }
    }
    String body = trim(String.join("\n", java.util.Arrays.copyOfRange(lines, bodyLine, lines.length)));
    if (description.isEmpty()) for (String line : body.split("\n", -1)) {
      String value = trim(line);
      if (!value.isEmpty()) {
        description = value;
        break;
      }
    }
    return new Parsed(name, description, body, compatibility, allowedTools, license, userOnly,
        List.copyOf(metadata));
  }

  private static String[] assign(String key, String value, String name, String description,
                                 String compatibility, String allowedTools, String license) {
    switch (key) {
      case "name" -> { if (!value.isEmpty()) name = value; }
      case "description" -> description = value;
      case "compatibility" -> compatibility = value;
      case "allowed-tools" -> allowedTools = value;
      case "license" -> license = value;
      default -> { }
    }
    return new String[] {name, description, compatibility, allowedTools, license};
  }

  private static boolean isScalarField(String key) {
    return key.equals("description") || key.equals("compatibility")
        || key.equals("allowed-tools") || key.equals("license");
  }

  private static Optional<Field> parseField(String line) {
    int colon = line.indexOf(':');
    if (colon < 0) return Optional.empty();
    String key = trim(line.substring(0, colon));
    String value = trim(line.substring(colon + 1));
    if (value.length() >= 2 && (value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'
        || value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))
      value = value.substring(1, value.length() - 1);
    return key.isEmpty() ? Optional.empty() : Optional.of(new Field(key, value));
  }

  private static List<String> resources(Path directory) {
    var result = new ArrayList<String>();
    try (Stream<Path> stream = Files.walk(directory, 4)) {
      stream.filter(Files::isRegularFile).forEach(path -> {
        if (result.size() >= MAX_RESOURCES) return;
        Path relative = directory.relativize(path);
        if (relative.getNameCount() > 3 || hidden(relative)
            || relative.getNameCount() == 1 && relative.toString().equals("SKILL.md")) return;
        result.add(relative.toString().replace('\\', '/'));
      });
    } catch (IOException | SecurityException ignored) {
      return List.of();
    }
    result.sort(Comparator.naturalOrder());
    return List.copyOf(result);
  }

  private static boolean hidden(Path relative) {
    for (Path part : relative) if (part.toString().startsWith(".")) return true;
    return false;
  }

  private static String readCapped(Path path) {
    try {
      long size = Files.size(path);
      if (!Files.isRegularFile(path) || size == 0 || size > MAX_BODY_BYTES) return "";
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException | SecurityException exception) {
      return "";
    }
  }

  private static Path canonical(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException exception) {
      return path.toAbsolutePath().normalize();
    }
  }

  private static int utf8Length(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
  private static int indentation(String value) {
    int result = 0;
    while (result < value.length() && value.charAt(result) == ' ') result++;
    return result;
  }
  private static String stripCarriageReturn(String value) {
    return value.endsWith("\r") ? value.substring(0, value.length() - 1) : value;
  }
  private static String trim(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && isSpace(value.charAt(start))) start++;
    while (end > start && isSpace(value.charAt(end - 1))) end--;
    return value.substring(start, end);
  }
  private static boolean isSpace(char value) {
    return value == ' ' || value == '\t' || value == '\r' || value == '\n';
  }
}
