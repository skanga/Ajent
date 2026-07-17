package com.github.skanga.ajent.tools.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.skanga.ajent.tools.catalog.ToolCatalog;
import com.github.skanga.ajent.tools.catalog.ToolKind;
import com.github.skanga.ajent.tools.fs.FileTools;
import com.github.skanga.ajent.tools.git.GitTools;
import com.github.skanga.ajent.tools.host.HostTools;
import com.github.skanga.ajent.tools.memory.MemoryTools;
import com.github.skanga.ajent.tools.process.ProcessTools;
import com.github.skanga.ajent.tools.search.RepoMapTools;
import com.github.skanga.ajent.tools.search.SearchTools;
import com.github.skanga.ajent.tools.web.WebTools;

/** Single catalog-backed dispatch point for every built-in tool. */
public final class ToolDispatcher {
  private final FileTools files;
  private final ProcessTools processes;
  private final SearchTools search;
  private final RepoMapTools repoMap;
  private final GitTools git;
  private final HostTools host;
  private final MemoryTools memory;
  private final WebTools web;

  public ToolDispatcher(FileTools files, ProcessTools processes, SearchTools search,
      RepoMapTools repoMap, GitTools git, HostTools host, MemoryTools memory, WebTools web) {
    this.files = files;
    this.processes = processes;
    this.search = search;
    this.repoMap = repoMap;
    this.git = git;
    this.host = host;
    this.memory = memory;
    this.web = web;
  }

  public ToolResult execute(String name, JsonNode arguments) {
    var specification = ToolCatalog.byName(name);
    if (specification.isEmpty()) return failure(ToolErrorKind.UNKNOWN, "unknown tool: " + name);
    ToolKind kind = specification.orElseThrow().kind();
    return switch (family(kind)) {
      case FILESYSTEM -> files.execute(name, arguments);
      case PROCESS -> processes.execute(name, arguments);
      case SEARCH -> search.execute(name, arguments);
      case REPOSITORY_MAP -> repoMap.execute(arguments);
      case GIT -> git.execute(name, arguments);
      case HOST -> host.execute(name, arguments);
      case MEMORY -> memory.execute(name, arguments);
      case WEB -> web.execute(name, arguments);
    };
  }

  public static ToolFamily family(ToolKind kind) {
    return switch (kind) {
      case READ, EDIT, WRITE, LIST_DIR -> ToolFamily.FILESYSTEM;
      case BASH, DIAGNOSTICS -> ToolFamily.PROCESS;
      case GREP, GLOB, FIND_DEFINITION -> ToolFamily.SEARCH;
      case REPO_MAP -> ToolFamily.REPOSITORY_MAP;
      case GIT_STATUS, GIT_DIFF, GIT_LOG, GIT_COMMIT -> ToolFamily.GIT;
      case TODO, TASK, SKILL, SEARCH_DOCS -> ToolFamily.HOST;
      case REMEMBER, FORGET, WIPE -> ToolFamily.MEMORY;
      case WEB_FETCH, WEB_SEARCH -> ToolFamily.WEB;
    };
  }

  private static ToolResult failure(ToolErrorKind kind, String detail) {
    return new ToolResult.Failure(new ToolError(kind, detail));
  }
}
