package com.github.skanga.ajent.tools.runtime;

import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.tools.fs.FileTools;
import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import com.github.skanga.ajent.tools.git.GitTools;
import com.github.skanga.ajent.tools.host.HostServices;
import com.github.skanga.ajent.tools.host.HostTools;
import com.github.skanga.ajent.tools.memory.JsonlMemoryStore;
import com.github.skanga.ajent.tools.memory.MemoryTools;
import com.github.skanga.ajent.tools.policy.EffectSet;
import com.github.skanga.ajent.tools.process.ProcessTools;
import com.github.skanga.ajent.tools.process.ProcessRunner;
import com.github.skanga.ajent.tools.prompt.AgentSystemPrompt;
import com.github.skanga.ajent.tools.rag.AgenttyDocRetriever;
import com.github.skanga.ajent.tools.rag.MemoryKnowledgeSource;
import com.github.skanga.ajent.tools.rag.SkillsKnowledgeSource;
import com.github.skanga.ajent.tools.search.RepoMapTools;
import com.github.skanga.ajent.tools.search.SearchTools;
import com.github.skanga.ajent.tools.skills.SkillEngine;
import com.github.skanga.ajent.tools.web.JdkWebTransport;
import com.github.skanga.ajent.tools.web.WebTools;
import com.github.skanga.ajent.tools.web.WebTransport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Production composition root for the complete local AgenTTY-compatible toolset. */
public final class ToolRuntimeFactory {
  private ToolRuntimeFactory() {}

  public record Components(
      ToolDispatcher dispatcher,
      JsonlMemoryStore memory,
      SkillEngine skills,
      AgentSystemPrompt systemPrompt,
      ExternalToolRuntime externalTools) {
    public Components {
      dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
      memory = Objects.requireNonNull(memory, "memory");
      skills = Objects.requireNonNull(skills, "skills");
      systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
      externalTools = Objects.requireNonNull(externalTools, "externalTools");
    }

    public List<ToolSpecification> additionalTools() { return externalTools.specifications(); }
    public Optional<EffectSet> effects(String name) {
      return com.github.skanga.ajent.tools.catalog.ToolCatalog.byName(name)
          .map(com.github.skanga.ajent.tools.catalog.ToolSpec::effects)
          .or(() -> externalTools.effects(name));
    }
  }

  public record Configuration(
      Path workspace,
      Path workingDirectory,
      Path home,
      Path docsRoot,
      WebTransport webTransport,
      HostServices.TodoSink todoSink,
      HostServices.SubagentRunner subagentRunner,
      ProcessRunner processRunner,
      ExternalToolRuntime externalTools) {
    public Configuration(
        Path workspace, Path workingDirectory, Path home, Path docsRoot,
        WebTransport webTransport, HostServices.TodoSink todoSink,
        HostServices.SubagentRunner subagentRunner) {
      this(workspace, workingDirectory, home, docsRoot, webTransport, todoSink, subagentRunner,
          new ProcessRunner(), ExternalToolRuntime.none());
    }

    public Configuration(
        Path workspace, Path workingDirectory, Path home, Path docsRoot,
        WebTransport webTransport, HostServices.TodoSink todoSink,
        HostServices.SubagentRunner subagentRunner, ProcessRunner processRunner) {
      this(workspace, workingDirectory, home, docsRoot, webTransport, todoSink, subagentRunner,
          processRunner, ExternalToolRuntime.none());
    }

    public Configuration {
      workspace = normalizeRequired(workspace, "workspace");
      workingDirectory = workingDirectory == null
          ? workspace : workingDirectory.toAbsolutePath().normalize();
      home = normalizeRequired(home, "home");
      docsRoot = docsRoot == null ? null : docsRoot.toAbsolutePath().normalize();
      processRunner = Objects.requireNonNull(processRunner, "processRunner");
      externalTools = externalTools == null ? ExternalToolRuntime.none() : externalTools;
    }

    public static Configuration standalone(Path workspace, Path home) {
      Path normalized = normalizeRequired(workspace, "workspace");
      return new Configuration(normalized, normalized, home, discoverDocs(normalized),
          new JdkWebTransport(), null, null, new ProcessRunner(), ExternalToolRuntime.none());
    }
  }

  public static ToolDispatcher create(Configuration configuration) {
    return compose(configuration).dispatcher();
  }

  public static Components compose(Configuration configuration) {
    Objects.requireNonNull(configuration, "configuration");
    var sandbox = new WorkspaceSandbox(
        configuration.workspace(), configuration.workingDirectory(), configuration.home());
    var skills = new SkillEngine(configuration.home(), configuration.workspace(), sandbox);
    var memory = new JsonlMemoryStore(configuration.home(), configuration.workspace());
    var retriever = new AgenttyDocRetriever(
        configuration.docsRoot(), new SkillsKnowledgeSource(skills),
        new MemoryKnowledgeSource(memory), null, true, true);
    var host = new HostTools(
        configuration.todoSink(), skills.resolver(), retriever, configuration.subagentRunner());
    var dispatcher = new ToolDispatcher(
        new FileTools(sandbox), new ProcessTools(sandbox, configuration.processRunner()),
        new SearchTools(sandbox),
        new RepoMapTools(sandbox), new GitTools(sandbox), host, new MemoryTools(memory),
        new WebTools(configuration.webTransport()), configuration.externalTools());
    return new Components(dispatcher, memory, skills, new AgentSystemPrompt(
        configuration.workspace(), configuration.home(), memory, skills,
        System.getProperty("os.name", "unknown")), configuration.externalTools());
  }

  private static Path discoverDocs(Path workspace) {
    Path docs = workspace.resolve("docs");
    if (Files.isDirectory(docs)) {
      return docs;
    }
    Path knowledge = workspace.resolve(".agentty/knowledge");
    return Files.isDirectory(knowledge) ? knowledge : null;
  }

  private static Path normalizeRequired(Path path, String label) {
    return Objects.requireNonNull(path, label).toAbsolutePath().normalize();
  }
}
