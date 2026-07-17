package com.github.skanga.ajent.tools.skills;

import java.nio.file.Path;
import java.util.List;

/** One discovered Agent Skills document and its progressively disclosed resources. */
public record Skill(String name, String description, String body, String source,
                    String compatibility, String allowedTools, String license,
                    boolean userOnly, Path directory, List<Metadata> metadata,
                    List<String> resources) {
  public record Metadata(String key, String value) {}

  public Skill {
    metadata = List.copyOf(metadata);
    resources = List.copyOf(resources);
  }
}
