package com.github.skanga.ajent.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Differential standalone MCP characterization against the pinned AgenTTY executable. */
final class NativeMcpParityIT {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void handshakeCatalogAndRealWriteMatchPinnedExecutable(@TempDir Path root) throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-workspace"));
    prepareToolWorkspace(nativeWorkspace);
    prepareToolWorkspace(javaWorkspace);

    Transcript nativeTranscript;
    try (var process = McpProcess.start(command(nativeBinary, nativeWorkspace),
        root.resolve("native-home"), false, toolEnvironment(nativeWorkspace))) {
      nativeTranscript = exercise(process, nativeWorkspace);
    }
    assertThat(nativeWorkspace.resolve("served.txt")).as(nativeTranscript.toString())
        .hasContent("through MCP");

    Transcript javaTranscript;
    try (var process = McpProcess.start(javaCommand(ajentJar, javaWorkspace),
        root.resolve("java-home"), true, toolEnvironment(javaWorkspace))) {
      javaTranscript = exercise(process, javaWorkspace);
    }
    assertThat(javaWorkspace.resolve("served.txt")).as(javaTranscript.toString())
        .hasContent("through MCP");

    Transcript nativeNormalized = normalize(nativeTranscript, nativeWorkspace, true);
    Transcript javaNormalized = normalize(javaTranscript, javaWorkspace, false);
    assertThat(toolNames(nativeNormalized)).doesNotContain("repo_map");
    assertThat(toolNames(javaNormalized)).contains("repo_map");
    assertThat(firstDifference(sortToolCatalog(nativeNormalized),
        sortToolCatalog(withoutCatalogTool(javaNormalized, "repo_map")))).isEmpty();
  }

  @Test
  void skillsMemoryRagAndRepositoryMapExecutableBehaviorIsCharacterized(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-knowledge-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-knowledge-workspace"));
    prepareToolWorkspace(nativeWorkspace);
    prepareToolWorkspace(javaWorkspace);

    KnowledgeCapture nativeCapture;
    try (var process = McpProcess.start(command(nativeBinary, nativeWorkspace),
        root.resolve("native-knowledge-home"), false, toolEnvironment(nativeWorkspace))) {
      nativeCapture = exerciseKnowledge(process, nativeWorkspace);
    }
    KnowledgeCapture javaCapture;
    try (var process = McpProcess.start(javaCommand(ajentJar, javaWorkspace),
        root.resolve("java-knowledge-home"), true, toolEnvironment(javaWorkspace))) {
      javaCapture = exerciseKnowledge(process, javaWorkspace);
    }

    assertThat(jsonDifference(
        normalize(nativeCapture.skill(), nativeWorkspace.toString(), true),
        normalize(javaCapture.skill(), javaWorkspace.toString(), false), "skill")).isEmpty();
    assertThat(jsonDifference(
        normalize(nativeCapture.remember(), nativeWorkspace.toString(), true),
        normalize(javaCapture.remember(), javaWorkspace.toString(), false), "remember")).isEmpty();
    assertThat(jsonDifference(
        normalize(nativeCapture.forgetPreview(), nativeWorkspace.toString(), true),
        normalize(javaCapture.forgetPreview(), javaWorkspace.toString(), false),
        "forget-preview")).isEmpty();
    assertThat(jsonDifference(
        normalize(nativeCapture.forget(), nativeWorkspace.toString(), true),
        normalize(javaCapture.forget(), javaWorkspace.toString(), false), "forget")).isEmpty();
    assertThat(jsonDifference(
        normalize(nativeCapture.wipe(), nativeWorkspace.toString(), true),
        normalize(javaCapture.wipe(), javaWorkspace.toString(), false), "wipe")).isEmpty();

    assertThat(toolText(nativeCapture.skill())).contains("quartz parity skill instructions");
    assertThat(toolText(javaCapture.skill())).contains("quartz parity skill instructions");
    assertThat(toolText(nativeCapture.docsRag())).contains("zebra", "BM25-only");
    assertThat(toolText(javaCapture.docsRag())).contains("zebra", "BM25-only", "confidence");
    assertThat(toolText(nativeCapture.memoryRag())).doesNotContain("memory://");
    assertThat(toolText(javaCapture.memoryRag()))
        .contains("flux capacitor", "memory://", "confidence");

    assertThat(nativeCapture.repoMap().path("error").path("message").asText())
        .isEqualTo("unknown tool: repo_map");
    assertThat(toolText(javaCapture.repoMap()))
        .contains("Repository map", "ParityCode.java", "parityAnswer");
    assertThat(Files.readString(repository.resolve("agentty/src/mcp/serve.cpp")))
        .contains("Register every native tool");
    assertThat(Files.readString(repository.resolve("agentty/src/tool/mcp_tools_bridge.cpp")))
        .contains("\"repo_map\"");
  }

  @Test
  void nativeToolValidationWorkspaceTimeoutCancellationAndUtf8TruncationMatchPinnedExecutable(
      @TempDir Path root) throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-edge-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-edge-workspace"));
    Files.writeString(nativeWorkspace.resolve("large.txt"), "🙂".repeat(25_000));
    Files.writeString(javaWorkspace.resolve("large.txt"), "🙂".repeat(25_000));

    Transcript nativeTranscript;
    try (var process = McpProcess.start(command(nativeBinary, nativeWorkspace),
        root.resolve("native-edge-home"), false, toolEnvironment(nativeWorkspace))) {
      nativeTranscript = exerciseNativeToolEdges(process, nativeWorkspace);
    }
    Transcript javaTranscript;
    try (var process = McpProcess.start(javaCommand(ajentJar, javaWorkspace),
        root.resolve("java-edge-home"), true, toolEnvironment(javaWorkspace))) {
      javaTranscript = exerciseNativeToolEdges(process, javaWorkspace);
    }

    Transcript normalizedNative = normalize(nativeTranscript, nativeWorkspace, true);
    Transcript normalizedJava = normalize(javaTranscript, javaWorkspace, false);
    String recoveryHint = "Restart ajent in a parent directory or pass --workspace <dir> "
        + "to widen the scope.";
    assertThat(normalizedNative.frames().get(2).toString()).contains(recoveryHint);
    assertThat(normalizedJava.frames().get(2).toString()).contains(recoveryHint);
    assertThat(Files.readString(
        repository.resolve("agentty/mcp-cpp/src/tools/util/fs_helpers.cpp")))
        .contains("Restart agentty in a parent directory");
    assertThat(firstDifference(normalizedNative, normalizedJava)).isEmpty();
    String truncated = normalizedJava.frames().getLast().toString();
    assertThat(truncated).contains("chars elided", "output exceeded tool's budget")
        .doesNotContain("\uFFFD");
  }

  private static Transcript exerciseNativeToolEdges(McpProcess process, Path workspace)
      throws Exception {
    var exchanges = new ArrayList<JsonNode>();
    exchanges.add(process.call("initialize", JSON.readTree("""
        {"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"ajent-parity","version":"1"}}
        """)));
    process.notify("notifications/initialized", JSON.createObjectNode());
    exchanges.add(process.call("tools/call", toolCall("read")));
    exchanges.add(process.call("tools/call", toolCall(
        "read", "path", workspace.getParent().resolve("outside.txt").toString())));
    ObjectNode timeout = toolCall("bash", "command", "Start-Sleep -Seconds 5");
    timeout.withObject("arguments").put("timeout_ms", 100);
    exchanges.add(process.call("tools/call", timeout));
    ObjectNode cancelled = toolCall("bash", "command", "Start-Sleep -Seconds 5");
    cancelled.withObject("arguments").put("timeout_ms", 1_000);
    exchanges.add(process.callThenCancel("tools/call", cancelled));
    exchanges.add(process.call("tools/call",
        toolCall("read", "path", workspace.resolve("large.txt").toString())));
    ObjectNode rangedRead = toolCall(
        "read", "path", workspace.resolve("large.txt").toString());
    rangedRead.withObject("arguments").put("offset", 1).put("limit", 1_999);
    exchanges.add(process.call("tools/call", rangedRead));
    return new Transcript(List.copyOf(exchanges));
  }

  private static Transcript sortToolCatalog(Transcript transcript) {
    var frames = new ArrayList<JsonNode>();
    for (JsonNode original : transcript.frames()) {
      JsonNode frame = original.deepCopy();
      JsonNode tools = frame.path("result").path("tools");
      if (tools.isArray()) {
        var sorted = new ArrayList<JsonNode>();
        tools.forEach(sorted::add);
        sorted.sort(java.util.Comparator.comparing(tool -> tool.path("name").asText()));
        var array = JSON.createArrayNode();
        sorted.forEach(array::add);
        ((ObjectNode) frame.path("result")).set("tools", array);
      }
      frames.add(frame);
    }
    return new Transcript(List.copyOf(frames));
  }

  private static Transcript withoutCatalogTool(Transcript transcript, String name) {
    var frames = new ArrayList<JsonNode>();
    for (JsonNode original : transcript.frames()) {
      JsonNode frame = original.deepCopy();
      JsonNode tools = frame.path("result").path("tools");
      if (tools.isArray()) {
        var retained = JSON.createArrayNode();
        tools.forEach(tool -> {
          if (!name.equals(tool.path("name").asText())) retained.add(tool);
        });
        ((ObjectNode) frame.path("result")).set("tools", retained);
      }
      frames.add(frame);
    }
    return new Transcript(List.copyOf(frames));
  }

  private static List<String> toolNames(Transcript transcript) {
    JsonNode tools = transcript.frames().stream()
        .map(frame -> frame.path("result").path("tools"))
        .filter(JsonNode::isArray)
        .findFirst().orElseThrow();
    var names = new ArrayList<String>();
    tools.forEach(tool -> names.add(tool.path("name").asText()));
    return List.copyOf(names);
  }

  @Test
  void configuredStdioToolsResourcesPromptsProgressAndCancellationMatchPinnedExecutable(
      @TempDir Path root) throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path fixture = root.resolve("McpParityServer.java");
    Files.writeString(fixture, downstreamFixture(), StandardCharsets.UTF_8);

    Path nativeWorkspace = Files.createDirectories(root.resolve("native-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-workspace"));
    Path nativeMarker = root.resolve("native-cancelled.txt");
    Path javaMarker = root.resolve("java-cancelled.txt");
    Path nativeRefresh = root.resolve("native-refreshed.txt");
    Path javaRefresh = root.resolve("java-refreshed.txt");
    Path nativeConfig = downstreamConfig(
        root.resolve("native-mcp.json"), fixture, nativeMarker, nativeRefresh);
    Path javaConfig = downstreamConfig(
        root.resolve("java-mcp.json"), fixture, javaMarker, javaRefresh);

    Transcript nativeTranscript;
    try (var process = McpProcess.start(command(nativeBinary, nativeWorkspace),
        root.resolve("native-home"), false, downstreamEnvironment(nativeConfig))) {
      nativeTranscript = exerciseDownstream(process);
      awaitNoMarker(nativeMarker);
      awaitMarker(nativeRefresh, "refreshed");
    }

    Transcript javaTranscript;
    try (var process = McpProcess.start(javaCommand(ajentJar, javaWorkspace),
        root.resolve("java-home"), true, downstreamEnvironment(javaConfig))) {
      javaTranscript = exerciseDownstream(process);
      awaitNoMarker(javaMarker);
      awaitMarker(javaRefresh, "refreshed");
    }

    Transcript nativeNormalized = sortToolCatalog(
        normalize(nativeTranscript, nativeWorkspace, true));
    Transcript javaNormalized = sortToolCatalog(
        normalize(javaTranscript, javaWorkspace, false));
    assertThat(toolNames(nativeNormalized)).doesNotContain("repo_map");
    assertThat(toolNames(javaNormalized)).contains("repo_map");
    assertThat(firstDifference(nativeNormalized,
        sortToolCatalog(withoutCatalogTool(javaNormalized, "repo_map")))).isEmpty();
  }

  @Test
  void configuredStreamableHttpSessionHeadersJsonSseAndCapabilitiesMatchPinnedExecutable(
      @TempDir Path root) throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-http-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-http-workspace"));

    try (var fixture = new HttpMcpFixture()) {
      Path nativeConfig = httpConfig(root.resolve("native-http.json"), fixture.endpoint());
      Transcript nativeTranscript;
      try (var process = McpProcess.start(command(nativeBinary, nativeWorkspace),
          root.resolve("native-http-home"), false,
          Map.of("AGENTTY_MCP_CONFIG", nativeConfig.toString()))) {
        nativeTranscript = exerciseHttpDownstream(process);
      }
      List<HttpRecord> nativeWire = canonicalHttpStartup(fixture.takeRecords(true));

      Path javaConfig = httpConfig(root.resolve("java-http.json"), fixture.endpoint());
      Transcript javaTranscript;
      try (var process = McpProcess.start(javaCommand(ajentJar, javaWorkspace),
          root.resolve("java-http-home"), true,
          Map.of("AGENTTY_MCP_CONFIG", javaConfig.toString()))) {
        javaTranscript = exerciseHttpDownstream(process);
      }
      List<HttpRecord> javaWire = canonicalHttpStartup(fixture.takeRecords(false));

      assertThat(javaWire).containsExactlyElementsOf(nativeWire);
      Transcript nativeNormalized = sortToolCatalog(
          normalize(nativeTranscript, nativeWorkspace, true));
      Transcript javaNormalized = sortToolCatalog(
          normalize(javaTranscript, javaWorkspace, false));
      assertThat(toolNames(nativeNormalized)).doesNotContain("repo_map");
      assertThat(toolNames(javaNormalized)).contains("repo_map");
      assertThat(firstDifference(nativeNormalized,
          sortToolCatalog(withoutCatalogTool(javaNormalized, "repo_map")))).isEmpty();
    }
  }

  private static Transcript exercise(McpProcess process, Path workspace) throws Exception {
    var exchanges = new ArrayList<JsonNode>();
    exchanges.add(process.call("initialize", JSON.readTree("""
        {"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"ajent-parity","version":"1"}}
        """)));
    process.notify("notifications/initialized", JSON.createObjectNode());
    exchanges.add(process.call("ping", JSON.createObjectNode()));
    exchanges.add(process.call("tools/list", JSON.createObjectNode()));
    ObjectNode write = JSON.createObjectNode().put("name", "write");
    write.putObject("arguments").put("path", workspace.resolve("served.txt").toString())
        .put("content", "through MCP");
    exchanges.add(process.call("tools/call", write));
    exerciseToolFamilies(process, workspace, exchanges);
    exchanges.add(process.call("does/not/exist", JSON.createObjectNode()));
    return new Transcript(List.copyOf(exchanges));
  }

  private static void exerciseToolFamilies(
      McpProcess process, Path workspace, List<JsonNode> exchanges) throws Exception {
    Path seed = workspace.resolve("src/seed.txt");
    recordTool(process, exchanges,
        toolCall("read", "path", workspace.resolve("served.txt").toString()));
    recordTool(process, exchanges, toolCall("list_dir", "path", workspace.toString()));
    ObjectNode edit = toolCall("edit");
    edit.withObject("arguments").put("path", seed.toString()).putArray("edits").addObject()
        .put("old_text", "beta").put("new_text", "MCP-EDITED");
    recordTool(process, exchanges, edit);
    recordTool(process, exchanges, toolCall("grep", "pattern", "MCP-EDITED"));
    recordTool(process, exchanges, toolCall("glob", "pattern", "*.java"));
    recordTool(process, exchanges, toolCall("bash", "command", "echo mcp-bash-ok"));
    ObjectNode todo = toolCall("todo");
    todo.withObject("arguments").putArray("todos").addObject()
        .put("content", "prove MCP tool parity").put("status", "in_progress");
    recordTool(process, exchanges, todo);
    recordTool(process, exchanges, toolCall("web_fetch", "url", "not a url"));
    recordTool(process, exchanges, toolCall("web_search", "query", ""));
    // The pinned Windows binary invokes rg with POSIX single quotes and consequently returns
    // false negatives for positive definition searches. Exercise the deterministic no-hit wire
    // result here; SearchToolsTest pins Ajent's source-intended positive scanner behavior.
    ObjectNode definition = toolCall("find_definition", "symbol", "missingParityDefinition");
    definition.withObject("arguments").put("path", workspace.toString());
    recordTool(process, exchanges, definition);
    recordTool(process, exchanges,
        toolCall("diagnostics", "command", "echo mcp-diagnostics-ok"));
    recordTool(process, exchanges, toolCall("git_status", "path", workspace.toString()));
    recordTool(process, exchanges, toolCall("git_diff", "path", workspace.toString()));
    ObjectNode commit = toolCall("git_commit", "message", "MCP parity commit");
    commit.withObject("arguments").put("path", workspace.toString()).put("stage_all", true);
    recordTool(process, exchanges, commit);
    ObjectNode log = toolCall("git_log", "path", workspace.toString());
    log.withObject("arguments").put("count", 2).put("oneline", true);
    recordTool(process, exchanges, log);
    recordTool(process, exchanges, toolCall("task", "prompt", ""));
    ObjectNode remember = toolCall("remember", "text", "parity memory sentinel");
    remember.withObject("arguments").put("scope", "project");
    recordTool(process, exchanges, remember);
    ObjectNode forget = toolCall("forget", "substring", "memory sentinel");
    forget.withObject("arguments").put("dry_run", true);
    recordTool(process, exchanges, forget);
    ObjectNode wipe = toolCall("wipe_memory", "scope", "project");
    wipe.withObject("arguments").put("confirm", true);
    recordTool(process, exchanges, wipe);
    recordTool(process, exchanges, toolCall("skill", "name", "parity-skill"));
    recordTool(process, exchanges, toolCall("search_docs", "query", ""));
    assertThat(exchanges).hasSize(25);
  }

  private static KnowledgeCapture exerciseKnowledge(McpProcess process, Path workspace)
      throws Exception {
    process.call("initialize", JSON.readTree("""
        {"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"ajent-knowledge-parity","version":"1"}}
        """));
    process.notify("notifications/initialized", JSON.createObjectNode());
    JsonNode skill = process.call("tools/call", toolCall("skill", "name", "parity-skill"));
    ObjectNode rememberCall = toolCall("remember", "text",
        "the flux capacitor requires gigawatt plutonium calibration");
    rememberCall.withObject("arguments").put("scope", "project");
    JsonNode remember = process.call("tools/call", rememberCall);
    JsonNode memoryRag = process.call("tools/call",
        toolCall("search_docs", "query", "flux capacitor plutonium"));
    JsonNode docsRag = process.call("tools/call",
        toolCall("search_docs", "query", "zebra quagga migration"));
    ObjectNode previewCall = toolCall("forget", "substring", "flux capacitor");
    previewCall.withObject("arguments").put("dry_run", true);
    JsonNode forgetPreview = process.call("tools/call", previewCall);
    JsonNode forget = process.call("tools/call",
        toolCall("forget", "substring", "flux capacitor"));
    ObjectNode wipeSeed = toolCall("remember", "text", "wipe knowledge parity sentinel");
    wipeSeed.withObject("arguments").put("scope", "project");
    process.call("tools/call", wipeSeed);
    ObjectNode wipeCall = toolCall("wipe_memory", "scope", "project");
    wipeCall.withObject("arguments").put("confirm", true);
    JsonNode wipe = process.call("tools/call", wipeCall);
    ObjectNode repoMapCall = toolCall("repo_map", "path", workspace.toString());
    repoMapCall.withObject("arguments").put("budget", 1000);
    JsonNode repoMap = process.call("tools/call", repoMapCall);
    return new KnowledgeCapture(skill, remember, memoryRag, docsRag, forgetPreview,
        forget, wipe, repoMap);
  }

  private static String toolText(JsonNode response) {
    JsonNode content = response.path("result").path("content");
    if (!content.isArray()) return "";
    var text = new StringBuilder();
    content.forEach(block -> text.append(block.path("text").asText()));
    return text.toString();
  }

  private static void recordTool(
      McpProcess process, List<JsonNode> exchanges, ObjectNode call) throws Exception {
    JsonNode response = process.call("tools/call", call);
    JsonNode content = response.path("result").path("content");
    assertThat(content.isArray()).as(call.toString()).isTrue();
    assertThat(content.isEmpty()).as(call.toString()).isFalse();
    exchanges.add(response);
  }

  private static void prepareToolWorkspace(Path workspace) throws Exception {
    Path source = Files.createDirectories(workspace.resolve("src"));
    Files.writeString(source.resolve("seed.txt"), "alpha\nbeta\ngamma\n");
    Files.writeString(source.resolve("ParityCode.java"),
        "final class ParityCode {\n  static int parityAnswer() { return 42; }\n}\n");
    Files.writeString(source.resolve("ParityMap.cpp"),
        "int parityAnswer() {\n  return 42;\n}\n");
    Path docs = Files.createDirectories(workspace.resolve("docs"));
    Files.writeString(docs.resolve("parity.md"),
        "# Quartz guide\nThe quartz parity knowledge sentinel is deterministic.\n\n"
            + "The zebra quagga migrates across the savanna every solstice season.\n");
    Path skill = Files.createDirectories(
        workspace.resolve(".agents/skills/parity-skill"));
    Files.writeString(skill.resolve("SKILL.md"), """
        ---
        name: parity-skill
        description: Executable MCP parity fixture
        ---
        Follow the quartz parity skill instructions.
        """);
    runGit(workspace, "init", "-q");
    runGit(workspace, "config", "user.email", "parity@example.test");
    runGit(workspace, "config", "user.name", "MCP Parity");
    runGit(workspace, "add", "-A");
    runGit(workspace, "commit", "-qm", "seed");
  }

  private static void runGit(Path workspace, String... arguments) throws Exception {
    var command = new ArrayList<String>();
    command.add("git");
    command.addAll(List.of(arguments));
    var builder = new ProcessBuilder(command).directory(workspace.toFile())
        .redirectErrorStream(true);
    builder.environment().putAll(Map.of(
        "GIT_AUTHOR_DATE", "2001-02-03T04:05:06Z",
        "GIT_COMMITTER_DATE", "2001-02-03T04:05:06Z", "TZ", "UTC"));
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(output).isZero();
  }

  private static Map<String, String> toolEnvironment(Path workspace) {
    return Map.of(
        "AGENTTY_DOCS_DIR", workspace.resolve("docs").toString(),
        "GIT_AUTHOR_DATE", "2001-02-03T04:05:06Z",
        "GIT_COMMITTER_DATE", "2001-02-03T04:05:06Z",
        "TZ", "UTC");
  }

  private static Transcript exerciseDownstream(McpProcess process) throws Exception {
    var exchanges = new ArrayList<JsonNode>();
    exchanges.add(process.call("initialize", JSON.readTree("""
        {"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"ajent-parity","version":"1"}}
        """)));
    process.notify("notifications/initialized", JSON.createObjectNode());
    JsonNode listed = process.call("tools/list", JSON.createObjectNode());
    assertThat(listed.toString()).as(process.stderr())
        .contains("remote_echo", "remote_rich", "remote_fail",
        "remote_change", "remote_slow", "mcp_read_resource", "mcp_get_prompt");
    exchanges.add(listed);
    JsonNode echo = process.call("tools/call", toolCall("remote_echo", "message", "hello"));
    assertThat(echo.toString()).contains("remote:hello");
    exchanges.add(echo);
    JsonNode rich = process.call("tools/call", toolCall("remote_rich", "unused", true));
    assertThat(rich.toString()).contains("rich", "image/png", "audio/wav", "answer");
    exchanges.add(rich);
    JsonNode failed = process.call("tools/call", toolCall("remote_fail", "unused", true));
    assertThat(failed.toString()).contains("remote failure", "isError");
    exchanges.add(failed);
    JsonNode resourceList = process.call("tools/call", toolCall("mcp_read_resource"));
    assertThat(resourceList.toString()).contains("mem://note", "mem://{id}");
    exchanges.add(resourceList);
    JsonNode resource = process.call("tools/call",
        toolCall("mcp_read_resource", "uri", "mem://note"));
    assertThat(resource.toString()).contains("remote note body", "image/png");
    exchanges.add(resource);
    JsonNode promptList = process.call("tools/call", toolCall("mcp_get_prompt"));
    assertThat(promptList.toString()).contains("greet", "name (required)");
    exchanges.add(promptList);
    ObjectNode prompt = toolCall("mcp_get_prompt", "name", "greet");
    prompt.withObject("arguments").withObject("arguments").put("name", "Ada");
    JsonNode renderedPrompt = process.call("tools/call", prompt);
    assertThat(renderedPrompt.toString()).contains("Greeting prompt", "Hello Ada");
    exchanges.add(renderedPrompt);
    JsonNode changed = process.call("tools/call", toolCall("remote_change", "unused", true));
    assertThat(changed.toString()).containsIgnoringCase("timed out").contains("isError");
    exchanges.add(changed);
    JsonNode timeout = process.callThenCancel(
        "tools/call", toolCall("remote_slow", "unused", true));
    assertThat(timeout.toString()).containsIgnoringCase("timed out").contains("isError");
    exchanges.add(timeout);
    return new Transcript(List.copyOf(exchanges));
  }

  private static Transcript exerciseHttpDownstream(McpProcess process) throws Exception {
    var exchanges = new ArrayList<JsonNode>();
    exchanges.add(process.call("initialize", JSON.readTree("""
        {"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"ajent-parity","version":"1"}}
        """)));
    process.notify("notifications/initialized", JSON.createObjectNode());
    JsonNode listed = process.call("tools/list", JSON.createObjectNode());
    assertThat(listed.toString()).as(process.stderr())
        .contains("http_echo", "mcp_read_resource", "mcp_get_prompt");
    exchanges.add(listed);
    JsonNode echo = process.call("tools/call", toolCall("http_echo", "message", "hello"));
    assertThat(echo.toString()).contains("http:hello");
    exchanges.add(echo);
    JsonNode resource = process.call("tools/call",
        toolCall("mcp_read_resource", "uri", "mem://http-note"));
    assertThat(resource.toString()).contains("streamed resource");
    exchanges.add(resource);
    ObjectNode prompt = toolCall("mcp_get_prompt", "name", "http_greet");
    prompt.withObject("arguments").withObject("arguments").put("name", "Ada");
    JsonNode rendered = process.call("tools/call", prompt);
    assertThat(rendered.toString()).contains("HTTP greeting", "Hello over HTTP");
    exchanges.add(rendered);
    return new Transcript(List.copyOf(exchanges));
  }

  private static Path httpConfig(Path path, String endpoint) throws Exception {
    ObjectNode server = JSON.createObjectNode().put("type", "streamable-http")
        .put("url", endpoint);
    server.putObject("headers").put("X-Parity", "configured");
    ObjectNode document = JSON.createObjectNode();
    document.putObject("mcpServers").set("remote-http", server);
    Files.writeString(path, JSON.writeValueAsString(document), StandardCharsets.UTF_8);
    return path;
  }

  private static List<HttpRecord> canonicalHttpStartup(List<HttpRecord> records) {
    if (records.size() < 2) return records;
    var canonical = new ArrayList<>(records);
    var startupMethods = java.util.Set.of("notifications/initialized", "tools/list",
        "resources/list", "resources/templates/list", "prompts/list");
    int end = 1;
    while (end < canonical.size()
        && startupMethods.contains(canonical.get(end).body().path("method").asText())) end++;
    canonical.subList(1, end).sort(java.util.Comparator.comparing(
        record -> record.body().path("method").asText()));
    return List.copyOf(canonical);
  }

  private static ObjectNode toolCall(String name, String key, String value) {
    ObjectNode call = toolCall(name);
    call.withObject("arguments").put(key, value);
    return call;
  }

  private static ObjectNode toolCall(String name, String key, boolean value) {
    ObjectNode call = toolCall(name);
    call.withObject("arguments").put(key, value);
    return call;
  }

  private static ObjectNode toolCall(String name) {
    ObjectNode call = JSON.createObjectNode().put("name", name);
    call.putObject("arguments");
    return call;
  }

  private static Map<String, String> downstreamEnvironment(Path configuration) {
    return Map.of(
        "AGENTTY_MCP_CONFIG", configuration.toString(),
        "AGENTTY_MCP_TIMEOUT_MS", "200",
        "AGENTTY_MCP_CONNECT_TIMEOUT_MS", "10000");
  }

  private static Path downstreamConfig(
      Path path, Path fixture, Path marker, Path refreshMarker) throws Exception {
    String executable = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    ObjectNode server = JSON.createObjectNode().put("command", executable);
    server.putArray("args").add(fixture.toString()).add(marker.toString())
        .add(refreshMarker.toString());
    ObjectNode document = JSON.createObjectNode();
    document.putObject("mcpServers").set("remote", server);
    Files.writeString(path, JSON.writeValueAsString(document), StandardCharsets.UTF_8);
    return path;
  }

  private static void awaitNoMarker(Path marker) throws Exception {
    java.lang.Thread.sleep(250);
    assertThat(marker).doesNotExist();
  }

  private static void awaitMarker(Path marker, String content) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!Files.isRegularFile(marker) && System.nanoTime() < deadline) {
      java.lang.Thread.sleep(10);
    }
    assertThat(marker).isRegularFile();
    assertThat(Files.readString(marker)).contains(content);
  }

  private static String downstreamFixture() {
    return """
        import java.io.*;
        import java.nio.charset.StandardCharsets;
        import java.nio.file.*;
        import java.util.concurrent.*;
        import java.util.regex.*;

        public class McpParityServer {
          private static final Pattern ID = Pattern.compile("\\\"id\\\"\\\\s*:\\\\s*(\\\\d+)");
          private static final Pattern METHOD = Pattern.compile("\\\"method\\\"\\\\s*:\\\\s*\\\"([^\\\"]+)\\\"");
          private static final Pattern NAME = Pattern.compile("\\\"name\\\"\\\\s*:\\\\s*\\\"([^\\\"]+)\\\"");
          private static final Pattern REQUEST_ID = Pattern.compile("\\\"requestId\\\"\\\\s*:\\\\s*(\\\\d+)");
          private static final ConcurrentMap<Long, Thread> calls = new ConcurrentHashMap<>();
          private static Path marker;
          private static Path refreshMarker;
          private static volatile boolean changed;

          public static void main(String[] args) throws Exception {
            marker = Path.of(args[0]);
            refreshMarker = Path.of(args[1]);
            try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
              String line;
              while ((line = input.readLine()) != null) handle(line);
            }
          }

          private static void handle(String line) throws Exception {
            String method = match(METHOD, line);
            if ("notifications/cancelled".equals(method)) {
              long id = Long.parseLong(match(REQUEST_ID, line));
              Files.writeString(marker, "cancelled " + id, StandardCharsets.UTF_8);
              Thread worker = calls.remove(id);
              if (worker != null) worker.interrupt();
              return;
            }
            if (!line.contains("\\\"id\\\"")) return;
            long id = Long.parseLong(match(ID, line));
            switch (method) {
              case "initialize" -> reply(id, "{\\\"protocolVersion\\\":\\\"2025-11-25\\\",\\\"capabilities\\\":{" +
                  "\\\"tools\\\":{\\\"listChanged\\\":true},\\\"resources\\\":{\\\"listChanged\\\":true}," +
                  "\\\"prompts\\\":{\\\"listChanged\\\":true}}," +
                  "\\\"serverInfo\\\":{\\\"name\\\":\\\"parity-server\\\",\\\"version\\\":\\\"1\\\"}}");
              case "tools/list" -> {
                if (changed) Files.writeString(refreshMarker, "refreshed", StandardCharsets.UTF_8);
                reply(id, "{\\\"tools\\\":[" + tool("remote_echo", true) + "," +
                    tool("remote_rich", true) + "," + tool("remote_fail", false) + "," +
                    tool("remote_change", false) + "," + tool("remote_slow", false) +
                    (changed ? "," + tool("remote_added", true) : "") + "]}");
              }
              case "resources/list" -> reply(id, "{\\\"resources\\\":[{\\\"uri\\\":\\\"mem://note\\\"," +
                  "\\\"name\\\":\\\"note\\\",\\\"title\\\":\\\"Parity note\\\",\\\"mimeType\\\":\\\"text/plain\\\"}]}");
              case "resources/templates/list" -> reply(id, "{\\\"resourceTemplates\\\":[{" +
                  "\\\"uriTemplate\\\":\\\"mem://{id}\\\",\\\"name\\\":\\\"memory\\\"}]}");
              case "resources/read" -> reply(id, "{\\\"contents\\\":[{" +
                  "\\\"uri\\\":\\\"mem://note\\\",\\\"text\\\":\\\"remote note body\\\"},{" +
                  "\\\"uri\\\":\\\"mem://note\\\",\\\"blob\\\":\\\"abcdefgh\\\",\\\"mimeType\\\":\\\"image/png\\\"}]}");
              case "prompts/list" -> reply(id, "{\\\"prompts\\\":[{\\\"name\\\":\\\"greet\\\"," +
                  "\\\"title\\\":\\\"Greeting\\\",\\\"description\\\":\\\"Greeting prompt\\\"," +
                  "\\\"arguments\\\":[{\\\"name\\\":\\\"name\\\",\\\"required\\\":true}]}]}");
              case "prompts/get" -> reply(id, "{\\\"description\\\":\\\"Greeting prompt\\\",\\\"messages\\\":[{" +
                  "\\\"role\\\":\\\"user\\\",\\\"content\\\":{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"Hello Ada\\\"}}]}");
              case "tools/call" -> call(id, line);
              default -> error(id, -32601, "method not found");
            }
          }

          private static void call(long id, String line) {
            String name = match(NAME, line.substring(line.indexOf("\\\"params\\\"")));
            switch (name) {
              case "remote_echo" -> {
                notify("notifications/progress", "{\\\"progressToken\\\":\\\"parity\\\",\\\"progress\\\":1," +
                    "\\\"total\\\":1,\\\"message\\\":\\\"done\\\"}");
                reply(id, "{\\\"content\\\":[{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"remote:hello\\\"}]}");
              }
              case "remote_rich" -> reply(id, "{\\\"content\\\":[" +
                  "{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"rich\\\"}," +
                  "{\\\"type\\\":\\\"image\\\",\\\"mimeType\\\":\\\"image/png\\\",\\\"data\\\":\\\"AAAA\\\"}," +
                  "{\\\"type\\\":\\\"audio\\\",\\\"mimeType\\\":\\\"audio/wav\\\",\\\"data\\\":\\\"AA\\\"}]," +
                  "\\\"structuredContent\\\":{\\\"answer\\\":42}}");
              case "remote_fail" -> reply(id, "{\\\"content\\\":[{" +
                  "\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"remote failure\\\"}],\\\"isError\\\":true}");
              case "remote_change" -> {
                changed = true;
                notify("notifications/tools/list_changed", "{}");
                reply(id, "{\\\"content\\\":[{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"change requested\\\"}]}");
              }
              case "remote_slow" -> {
                Thread worker = Thread.ofVirtual().start(() -> {
                  try { Thread.sleep(30_000); reply(id, "{\\\"content\\\":[]}"); }
                  catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                  finally { calls.remove(id); }
                });
                calls.put(id, worker);
              }
              default -> error(id, -32602, "unknown tool");
            }
          }

          private static String tool(String name, boolean readOnly) {
            return "{\\\"name\\\":\\\"" + name + "\\\",\\\"description\\\":\\\"Parity " + name +
                "\\\",\\\"inputSchema\\\":{\\\"type\\\":\\\"object\\\",\\\"properties\\\":{}}," +
                "\\\"annotations\\\":{\\\"readOnlyHint\\\":" + readOnly + "}}";
          }

          private static String match(Pattern pattern, String value) {
            Matcher matcher = pattern.matcher(value);
            if (!matcher.find()) throw new IllegalArgumentException(value);
            return matcher.group(1);
          }

          private static synchronized void reply(long id, String result) {
            System.out.println("{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":" + id + ",\\\"result\\\":" + result + "}");
            System.out.flush();
          }

          private static synchronized void error(long id, int code, String message) {
            System.out.println("{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":" + id + ",\\\"error\\\":{" +
                "\\\"code\\\":" + code + ",\\\"message\\\":\\\"" + message + "\\\"}}");
            System.out.flush();
          }

          private static synchronized void notify(String method, String params) {
            System.out.println("{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"method\\\":\\\"" + method +
                "\\\",\\\"params\\\":" + params + "}");
            System.out.flush();
          }
        }
        """;
  }

  private static Transcript normalize(Transcript transcript, Path workspace, boolean nativeProgram) {
    return new Transcript(transcript.frames().stream()
        .map(frame -> normalize(frame, workspace.toString(), nativeProgram)).toList());
  }

  private static JsonNode normalize(JsonNode value, String workspace, boolean nativeProgram) {
    if (value.isObject()) {
      ObjectNode result = JSON.createObjectNode();
      value.properties().forEach(entry ->
          result.set(entry.getKey(), normalize(entry.getValue(), workspace, nativeProgram)));
      return result;
    }
    if (value.isArray()) {
      var result = JSON.createArrayNode();
      value.forEach(item -> result.add(normalize(item, workspace, nativeProgram)));
      return result;
    }
    if (value.isIntegralNumber()) return JSON.getNodeFactory().numberNode(value.longValue());
    if (!value.isTextual()) return value.deepCopy();
    String text = value.textValue().replace(workspace, "<WORKSPACE>");
    text = text.replaceAll("\\[elapsed: \\d+ ms]", "[elapsed: <MILLISECONDS>]");
    text = text.replaceAll("\\[[0-9a-fA-F]{8}]", "[<MEMORY-ID>]");
    text = text.replace(".agentty", ".<PROGRAM-DATA>");
    if (nativeProgram) text = text.replace("agentty", "ajent");
    text = text.replace(".<PROGRAM-DATA>", ".agentty");
    return JSON.getNodeFactory().textNode(text);
  }

  private static String firstDifference(Transcript actual, Transcript expected) {
    if (actual.frames().size() != expected.frames().size()) return "frame count";
    for (int index = 0; index < actual.frames().size(); index++) {
      String difference = jsonDifference(
          actual.frames().get(index), expected.frames().get(index), "frame[" + index + "]");
      if (!difference.isEmpty()) return difference;
    }
    return "";
  }

  private static String jsonDifference(JsonNode actual, JsonNode expected, String path) {
    if (actual.isNumber() && expected.isNumber()) {
      return actual.decimalValue().compareTo(expected.decimalValue()) == 0 ? ""
          : path + ": expected " + expected + " but was " + actual;
    }
    if (actual.isObject() && expected.isObject()) {
      var actualNames = new java.util.TreeSet<String>();
      var expectedNames = new java.util.TreeSet<String>();
      actual.fieldNames().forEachRemaining(actualNames::add);
      expected.fieldNames().forEachRemaining(expectedNames::add);
      if (!actualNames.equals(expectedNames)) {
        return path + ": expected fields " + expectedNames + " but was " + actualNames;
      }
      for (String name : actualNames) {
        String difference = jsonDifference(actual.get(name), expected.get(name), path + "." + name);
        if (!difference.isEmpty()) return difference;
      }
      return "";
    }
    if (actual.isArray() && expected.isArray()) {
      if (actual.size() != expected.size()) {
        return path + ": expected array size " + expected.size() + " but was " + actual.size();
      }
      for (int index = 0; index < actual.size(); index++) {
        String difference = jsonDifference(
            actual.get(index), expected.get(index), path + "[" + index + "]");
        if (!difference.isEmpty()) return difference;
      }
      return "";
    }
    return actual.equals(expected) ? ""
        : path + ": expected " + expected + " but was " + actual;
  }

  private static List<String> command(Path executable, Path workspace) {
    return List.of(executable.toString(), "mcp-serve", "--workspace", workspace.toString(),
        "--sandbox", "off", "--provider", "ollama", "--model", "qwen3:14b");
  }

  private static List<String> javaCommand(Path jar, Path workspace) {
    String executable = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    return List.of(executable, "-jar", jar.toString(), "mcp-serve", "--workspace",
        workspace.toString(), "--sandbox", "off", "--provider", "ollama", "--model",
        "qwen3:14b");
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name, "");
    if (value.isBlank()) throw new AssertionError("missing system property " + name);
    return value;
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("ajent-parity"))) return current;
      current = current.getParent();
    }
    throw new IllegalStateException("Ajent repository root not found");
  }

  private record Transcript(List<JsonNode> frames) {}

  private record KnowledgeCapture(
      JsonNode skill, JsonNode remember, JsonNode memoryRag, JsonNode docsRag,
      JsonNode forgetPreview, JsonNode forget, JsonNode wipe, JsonNode repoMap) {}

  private record HttpRecord(
      String method, String path, String accept, String contentType, String session,
      String protocolVersion, String customHeader, String userAgent, JsonNode body) {
    private HttpRecord {
      body = body.deepCopy();
    }
    @Override public JsonNode body() { return body.deepCopy(); }
  }

  private static final class HttpMcpFixture implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final CopyOnWriteArrayList<HttpRecord> records = new CopyOnWriteArrayList<>();

    private HttpMcpFixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/mcp", this::handle);
      server.setExecutor(executor);
      server.start();
    }

    private String endpoint() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    private List<HttpRecord> takeRecords(boolean nativeProgram) {
      var taken = new ArrayList<HttpRecord>();
      for (HttpRecord record : records) {
        taken.add(new HttpRecord(record.method(), record.path(), record.accept(),
            record.contentType(), record.session(), record.protocolVersion(),
            record.customHeader(), normalizeText(record.userAgent(), nativeProgram),
            normalize(record.body(), "<NO-WORKSPACE>", nativeProgram)));
      }
      records.clear();
      return List.copyOf(taken);
    }

    private void handle(HttpExchange exchange) throws IOException {
      byte[] bytes = exchange.getRequestBody().readAllBytes();
      JsonNode request;
      try {
        request = JSON.readTree(bytes);
      } catch (IOException failure) {
        respond(exchange, 400, "application/json", new byte[0]);
        return;
      }
      var headers = exchange.getRequestHeaders();
      records.add(new HttpRecord(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
          header(headers, "Accept"), header(headers, "Content-Type"),
          header(headers, "Mcp-Session-Id"), header(headers, "Mcp-Protocol-Version"),
          header(headers, "X-Parity"), header(headers, "User-Agent"), request));

      String method = request.path("method").asText();
      if (!request.has("id")) {
        exchange.getResponseHeaders().set("Mcp-Session-Id", "parity-session");
        respond(exchange, 202, "application/json", new byte[0]);
        return;
      }
      JsonNode result = switch (method) {
        case "initialize" -> json("""
            {"protocolVersion":"2025-11-25","capabilities":{
              "tools":{},"resources":{},"prompts":{}},
             "serverInfo":{"name":"http-parity","version":"1"}}
            """);
        case "tools/list" -> json("""
            {"tools":[{"name":"http_echo","description":"HTTP echo",
              "inputSchema":{"type":"object","properties":{"message":{"type":"string"}}},
              "annotations":{"readOnlyHint":true}}]}
            """);
        case "tools/call" -> json("""
            {"content":[{"type":"text","text":"http:hello"}]}
            """);
        case "resources/list" -> json("""
            {"resources":[{"uri":"mem://http-note","name":"http-note",
              "title":"HTTP note","mimeType":"text/plain"}]}
            """);
        case "resources/templates/list" -> json("""
            {"resourceTemplates":[]}
            """);
        case "resources/read" -> json("""
            {"contents":[{"uri":"mem://http-note","text":"streamed resource"}]}
            """);
        case "prompts/list" -> json("""
            {"prompts":[{"name":"http_greet","description":"HTTP greeting",
              "arguments":[{"name":"name","required":true}]}]}
            """);
        case "prompts/get" -> json("""
            {"description":"HTTP greeting","messages":[{"role":"user",
              "content":{"type":"text","text":"Hello over HTTP"}}]}
            """);
        default -> null;
      };
      ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
      response.set("id", request.path("id").deepCopy());
      if (result == null) {
        response.putObject("error").put("code", -32601).put("message", "method not found");
      } else {
        response.set("result", result);
      }
      exchange.getResponseHeaders().set("Mcp-Session-Id", "parity-session");
      byte[] payload = JSON.writeValueAsBytes(response);
      if ("resources/read".equals(method)) {
        byte[] sse = ("event: message\r\ndata: "
            + new String(payload, StandardCharsets.UTF_8) + "\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
        respond(exchange, 200, "text/event-stream", sse);
      } else {
        respond(exchange, 200, "application/json", payload);
      }
    }

    private static String header(com.sun.net.httpserver.Headers headers, String name) {
      String value = headers.getFirst(name);
      return value == null ? "" : value;
    }

    private static String normalizeText(String value, boolean nativeProgram) {
      return nativeProgram ? value.replace("agentty", "ajent") : value;
    }

    private static JsonNode json(String value) {
      try {
        return JSON.readTree(value);
      } catch (IOException failure) {
        throw new AssertionError(failure);
      }
    }

    private static void respond(
        HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
      exchange.getResponseHeaders().set("Content-Type", contentType);
      exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
      if (body.length != 0) exchange.getResponseBody().write(body);
      exchange.close();
    }

    @Override public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private static final class McpProcess implements AutoCloseable {
    private final Process process;
    private final BufferedReader stdout;
    private final BufferedWriter stdin;
    private final java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
    private final java.lang.Thread stderrReader;
    private int nextId;

    private McpProcess(Process process) {
      this.process = process;
      stdout = new BufferedReader(new InputStreamReader(
          process.getInputStream(), StandardCharsets.UTF_8));
      stdin = new BufferedWriter(new OutputStreamWriter(
          process.getOutputStream(), StandardCharsets.UTF_8));
      stderrReader = java.lang.Thread.ofVirtual().start(() -> {
        try {
          process.getErrorStream().transferTo(stderr);
        } catch (java.io.IOException ignored) {
          // The process exit closes this stream.
        }
      });
    }

    static McpProcess start(List<String> command, Path home, boolean javaProcess)
        throws Exception {
      return start(command, home, javaProcess, Map.of());
    }

    static McpProcess start(List<String> command, Path home, boolean javaProcess,
                            Map<String, String> environment) throws Exception {
      Files.createDirectories(home);
      var effective = new ArrayList<>(command);
      if (javaProcess) effective.add(1, "-Duser.home=" + home);
      var builder = new ProcessBuilder(effective).redirectErrorStream(false);
      int workspaceOption = effective.indexOf("--workspace");
      if (workspaceOption >= 0) {
        builder.directory(Path.of(effective.get(workspaceOption + 1)).toFile());
      }
      builder.environment().putAll(Map.of(
          "HOME", home.toString(), "USERPROFILE", home.toString(), "APPDATA", home.toString()));
      builder.environment().putAll(environment);
      return new McpProcess(builder.start());
    }

    JsonNode call(String method, JsonNode params) throws Exception {
      int id = ++nextId;
      ObjectNode request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id)
          .put("method", method);
      request.set("params", params);
      write(request);
      String line = stdout.readLine();
      if (line == null) throw new AssertionError("MCP process exited: " + stderr());
      JsonNode response = JSON.readTree(line);
      assertThat(response.path("id").asInt(-1)).as(response.toString()).isEqualTo(id);
      return response;
    }

    JsonNode callThenCancel(String method, JsonNode params) throws Exception {
      int id = ++nextId;
      ObjectNode request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id)
          .put("method", method);
      request.set("params", params);
      write(request);
      java.lang.Thread cancellation = java.lang.Thread.ofVirtual().start(() -> {
        try {
          java.lang.Thread.sleep(Duration.ofMillis(25));
          ObjectNode cancellationParams = JSON.createObjectNode().put("requestId", id)
              .put("reason", "parity cancellation");
          notify("notifications/cancelled", cancellationParams);
        } catch (Exception failure) {
          throw new AssertionError(failure);
        }
      });
      String line = stdout.readLine();
      cancellation.join(Duration.ofSeconds(2));
      assertThat(cancellation.isAlive()).isFalse();
      if (line == null) throw new AssertionError("MCP process exited: " + stderr());
      JsonNode response = JSON.readTree(line);
      assertThat(response.path("id").asInt(-1)).as(response.toString()).isEqualTo(id);
      return response;
    }

    void notify(String method, JsonNode params) throws Exception {
      ObjectNode request = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method);
      request.set("params", params);
      write(request);
    }

    private synchronized void write(JsonNode frame) throws Exception {
      stdin.write(JSON.writeValueAsString(frame));
      stdin.newLine();
      stdin.flush();
    }

    @Override public void close() throws IOException {
      stdin.close();
      try {
        if (!process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          throw new AssertionError("MCP process did not exit");
        }
        stderrReader.join(Duration.ofSeconds(2));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
        throw new AssertionError("Interrupted awaiting MCP process exit", interrupted);
      }
      assertThat(process.exitValue()).as(stderr()).isZero();
    }

    private String stderr() {
      return stderr.toString(StandardCharsets.UTF_8);
    }
  }
}
