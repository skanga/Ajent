package com.github.skanga.ajent.tools.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.skanga.ajent.tools.catalog.OutputBudget;
import com.github.skanga.ajent.tools.catalog.ToolCatalog;
import com.github.skanga.ajent.tools.catalog.ToolKind;
import com.github.skanga.ajent.tools.catalog.ToolSpec;
import com.github.skanga.ajent.tools.fs.FileTools;
import com.github.skanga.ajent.tools.git.GitTools;
import com.github.skanga.ajent.tools.host.HostTools;
import com.github.skanga.ajent.tools.memory.MemoryTools;
import com.github.skanga.ajent.tools.process.ProcessTools;
import com.github.skanga.ajent.tools.search.RepoMapTools;
import com.github.skanga.ajent.tools.search.SearchTools;
import com.github.skanga.ajent.tools.web.WebTools;
import com.github.skanga.ajent.domain.CancellationSignal;
import java.util.function.Consumer;
import java.util.Objects;

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
  private final ExternalToolRuntime external;

  public ToolDispatcher(FileTools files, ProcessTools processes, SearchTools search,
      RepoMapTools repoMap, GitTools git, HostTools host, MemoryTools memory, WebTools web) {
    this(files, processes, search, repoMap, git, host, memory, web, ExternalToolRuntime.none());
  }

  public ToolDispatcher(FileTools files, ProcessTools processes, SearchTools search,
      RepoMapTools repoMap, GitTools git, HostTools host, MemoryTools memory, WebTools web,
      ExternalToolRuntime external) {
    this.files = files;
    this.processes = processes;
    this.search = search;
    this.repoMap = repoMap;
    this.git = git;
    this.host = host;
    this.memory = memory;
    this.web = web;
    this.external = Objects.requireNonNull(external, "external");
  }

  public ToolResult execute(String name, JsonNode arguments) {
    return execute(name, arguments, new CancellationSignal());
  }

  public ToolResult execute(String name, JsonNode arguments, CancellationSignal cancellation) {
    return execute(name, arguments, cancellation, ignored -> {});
  }

  public ToolResult execute(String name, JsonNode arguments, CancellationSignal cancellation,
                            Consumer<String> progress) {
    var specification = ToolCatalog.byName(name);
    if (specification.isEmpty()) {
      if (external.effects(name).isEmpty())
        return failure(ToolErrorKind.NOT_FOUND, "unknown tool: " + name);
      JsonNode safeArguments = arguments != null && arguments.isObject()
          ? arguments : JsonNodeFactory.instance.objectNode();
      try {
        return external.execute(name, (com.fasterxml.jackson.databind.node.ObjectNode) safeArguments);
      } catch (RuntimeException exception) {
        return failure(ToolErrorKind.UNKNOWN, "tool crashed: " + exception.getMessage());
      }
    }
    ToolSpec spec = specification.orElseThrow();
    JsonNode safeArguments = arguments != null && arguments.isObject()
        ? arguments : JsonNodeFactory.instance.objectNode();
    ToolResult result;
    try {
      result = switch (family(spec.kind())) {
        case FILESYSTEM -> files.execute(name, safeArguments);
        case PROCESS -> processes.execute(name, safeArguments, progress);
        case SEARCH -> search.execute(name, safeArguments);
        case REPOSITORY_MAP -> repoMap.execute(safeArguments);
        case GIT -> git.execute(name, safeArguments);
        case HOST -> host.execute(name, safeArguments, cancellation, progress);
        case MEMORY -> memory.execute(name, safeArguments);
        case WEB -> web.execute(name, safeArguments);
      };
    } catch (RuntimeException exception) {
      return failure(ToolErrorKind.UNKNOWN, "tool crashed: " + exception.getMessage());
    }
    if (result instanceof ToolResult.Failure failed) {
      String detail = family(spec.kind()) == ToolFamily.HOST
          || failed.error().kind() == ToolErrorKind.UNKNOWN
          ? failed.error().detail() : failed.error().render();
      result = failure(ToolErrorKind.UNKNOWN, detail);
    }
    return applyBudget(spec, result);
  }

  static ToolResult applyBudget(ToolSpec spec, ToolResult result) {
    if (!(result instanceof ToolResult.Success success) || spec.maxOutputCharacters() <= 0)
      return result;
    ToolOutput output = success.output();
    String bounded = OutputBudget.apply(
        output.text(), spec.maxOutputCharacters(), spec.truncationStrategy());
    if (bounded.equals(output.text())) return result;
    return new ToolResult.Success(new ToolOutput(bounded, output.change()));
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
