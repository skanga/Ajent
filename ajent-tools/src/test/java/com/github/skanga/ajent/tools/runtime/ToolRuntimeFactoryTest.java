package com.github.skanga.ajent.tools.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.tools.catalog.NativeToolWireCatalog;
import com.github.skanga.ajent.tools.catalog.ToolCatalog;
import com.github.skanga.ajent.tools.host.HostServices;
import com.github.skanga.ajent.tools.web.WebTransport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolRuntimeFactoryTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void composesConcreteStandaloneBackendsForEveryHostCoupledFamily(@TempDir Path root)
      throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path home = Files.createDirectories(root.resolve("home"));
    Path docs = Files.createDirectories(workspace.resolve("docs"));
    Files.writeString(docs.resolve("guide.md"), "# Ajent Guide\nThe parity token is cedar.");
    Path skill = Files.createDirectories(workspace.resolve(".agents/skills/demo"));
    Files.writeString(skill.resolve("SKILL.md"),
        "---\nname: demo\ndescription: Demo skill\n---\nFollow the demo instructions.");
    var todos = new AtomicReference<List<HostServices.TodoItem>>();
    WebTransport web = request -> new WebTransport.Response(
        200, Map.of("content-type", List.of("text/plain")), "network body", "");
    var subagent = new HostServices.SubagentRunner() {
      @Override public boolean available() { return true; }
      @Override public HostServices.SubagentResponse run(HostServices.SubagentRequest request) {
        return new HostServices.SubagentResponse("delegated:" + request.prompt(), false);
      }
    };
    ToolDispatcher dispatcher = ToolRuntimeFactory.create(new ToolRuntimeFactory.Configuration(
        workspace, workspace, home, docs, web, todos::set, subagent));

    assertSuccess(dispatcher.execute("write", JSON.createObjectNode()
        .put("path", "sample.txt").put("content", "value")), "Created");
    assertSuccess(dispatcher.execute("remember", JSON.createObjectNode()
        .put("text", "persistent fact").put("scope", "project")), "Remembered");
    assertSuccess(dispatcher.execute("skill", JSON.createObjectNode().put("name", "demo")),
        "Follow the demo instructions");
    assertSuccess(dispatcher.execute("search_docs", JSON.createObjectNode()
        .put("query", "cedar").put("k", 3)), "cedar");
    assertSuccess(dispatcher.execute("web_fetch", JSON.createObjectNode()
        .put("url", "https://example.test")), "network body");
    assertSuccess(dispatcher.execute("task", JSON.createObjectNode()
        .put("prompt", "inspect")), "delegated:inspect");
    var todoArguments = JSON.createObjectNode();
    todoArguments.putArray("todos").addObject()
        .put("content", "ship").put("status", "completed");
    assertSuccess(dispatcher.execute("todo", todoArguments), "[x] ship");
    assertThat(todos.get()).containsExactly(new HostServices.TodoItem("ship", "completed"));
  }

  @Test
  void defaultsUseWorkspaceRootsRealWebTransportAndUnavailableSubagents(@TempDir Path root)
      throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path home = Files.createDirectories(root.resolve("home"));
    ToolDispatcher dispatcher = ToolRuntimeFactory.create(
        ToolRuntimeFactory.Configuration.standalone(workspace, home));

    assertSuccess(dispatcher.execute("write", JSON.createObjectNode()
        .put("path", "created.txt").put("content", "ok")), "Created");
    assertThat(Files.readString(workspace.resolve("created.txt"))).isEqualTo("ok");
    assertThat(dispatcher.execute("task", JSON.createObjectNode().put("prompt", "x")))
        .isInstanceOf(ToolResult.Failure.class);
  }

  @Test
  void portsThePinnedProductionToolsetEndToEnd(@TempDir Path root) throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace/src")).getParent();
    Path docs = Files.createDirectories(workspace.resolve("docs"));
    Files.writeString(docs.resolve("zebra.md"),
        "# Zebra habits\nThe zebra quagga migrates every solstice season.\n");
    Files.writeString(docs.resolve("other.md"), "# Unrelated\nDatabase notes.\n");
    var todos = new AtomicReference<List<HostServices.TodoItem>>();
    WebTransport offline = request -> {
      throw new AssertionError("invalid/offline web inputs must not reach transport");
    };
    ToolDispatcher dispatcher = ToolRuntimeFactory.create(new ToolRuntimeFactory.Configuration(
        workspace, workspace, workspace, docs, offline, todos::set, null));

    assertThat(ToolCatalog.all()).hasSize(23);
    assertThat(ToolCatalog.all().getFirst().name()).isEqualTo("read");
    assertThat(ToolCatalog.all()).allSatisfy(spec -> {
      assertThat(NativeToolWireCatalog.byName(spec.name()).orElseThrow().inputSchema().isObject())
          .as(spec.name()).isTrue();
      assertThat(ToolDispatcher.family(spec.kind())).as(spec.name()).isNotNull();
    });

    Path text = workspace.resolve("src/hello.txt");
    ToolResult.Success written = success(dispatcher.execute("write", object()
        .put("file_path", text.toString()).put("content", "alpha\nbeta\ngamma\n")));
    assertThat(written.output().change()).isPresent().get().satisfies(change ->
        assertThat(change.added()).isEqualTo(3));
    assertSuccess(dispatcher.execute("read", object().put("path", text.toString())), "beta");
    ObjectNode edit = object().put("old_text", "beta").put("new_text", "BETA-EDITED");
    ToolResult.Success edited = success(dispatcher.execute("edit", object()
        .put("path", text.toString()).set("edits", JSON.createArrayNode().add(edit))));
    assertThat(edited.output().change()).isPresent().get().satisfies(change ->
        assertThat(change.hunks()).isNotEmpty());
    assertThat(Files.readString(text)).contains("BETA-EDITED");

    Path code = Files.writeString(workspace.resolve("src/code.cpp"),
        "int answer() { return 42; }\nint other() { return 7; }\n");
    assertSuccess(dispatcher.execute("grep", object().put("pattern", "BETA-EDITED")
        .put("path", workspace.toString())), "hello.txt");
    assertSuccess(dispatcher.execute("glob", object().put("pattern", "*.cpp")
        .put("path", workspace.toString())), "code.cpp");
    assertSuccess(dispatcher.execute("list_dir", object().put("path", code.getParent().toString())),
        "hello.txt", "code.cpp");
    assertSuccess(dispatcher.execute("find_definition", object().put("symbol", "answer")
        .put("path", workspace.toString())), "code.cpp");
    assertSuccess(dispatcher.execute("repo_map", object().put("path", workspace.toString())),
        "code.cpp", "answer");
    assertSuccess(dispatcher.execute("bash", object().put("command", "echo e2e-bash-ok")
        .put("cd", workspace.toString())), "e2e-bash-ok");
    assertThat(dispatcher.execute("read", object().put("path",
        root.resolve("outside.txt").toString()))).isInstanceOf(ToolResult.Failure.class);

    ObjectNode todo = object();
    todo.putArray("todos").addObject().put("content", "prove the tools")
        .put("status", "in_progress");
    assertSuccess(dispatcher.execute("todo", todo), "prove the tools");
    assertThat(todos.get()).containsExactly(
        new HostServices.TodoItem("prove the tools", "in_progress"));

    assertSuccess(dispatcher.execute("remember", object().put("text", "e2e sentinel fact alpha")
        .put("scope", "user")), "Remembered");
    assertThat(workspace.resolve(".agentty/memory.jsonl")).isRegularFile();
    assertSuccess(dispatcher.execute("forget", object().put("substring", "sentinel fact")
        .put("dry_run", true)), "alpha");
    success(dispatcher.execute("forget", object().put("substring", "sentinel fact")));
    success(dispatcher.execute("remember", object().put("text", "wipe me").put("scope", "user")));
    success(dispatcher.execute("wipe_memory", object().put("scope", "user").put("confirm", true)));
    assertThat(dispatcher.execute("skill", object().put("name", "no-such-skill-xyz")))
        .isInstanceOfSatisfying(ToolResult.Failure.class, failure ->
            assertThat(failure.error().detail()).contains("no skill named"));

    assertSuccess(dispatcher.execute("search_docs", object().put("query", "zebra quagga migration")),
        "zebra", "BM25");
    success(dispatcher.execute("remember", object()
        .put("text", "the flux capacitor requires gigawatt plutonium calibration")
        .put("scope", "user")));
    assertSuccess(dispatcher.execute("search_docs", object()
        .put("query", "flux capacitor plutonium")), "flux capacitor", "memory");
    success(dispatcher.execute("wipe_memory", object().put("scope", "user").put("confirm", true)));
    assertThat(dispatcher.execute("task", object().put("prompt", "explore the codebase")))
        .isInstanceOfSatisfying(ToolResult.Failure.class, failure ->
            assertThat(failure.error().detail()).isNotEmpty());
    assertThat(dispatcher.execute("web_fetch", object().put("url", "http://example.com")))
        .isInstanceOf(ToolResult.Failure.class);
    assertThat(dispatcher.execute("web_fetch", object().put("url", "not a url")))
        .isInstanceOf(ToolResult.Failure.class);

    run(workspace, "init", "-q");
    run(workspace, "config", "user.email", "e2e@test");
    run(workspace, "config", "user.name", "e2e");
    run(workspace, "add", "-A");
    run(workspace, "commit", "-qm", "seed");
    Files.writeString(text, "changed content\n");
    assertSuccess(dispatcher.execute("git_status", object().put("path", workspace.toString())),
        "hello.txt");
    assertSuccess(dispatcher.execute("git_diff", object().put("path", workspace.toString())),
        "changed content");
    success(dispatcher.execute("git_commit", object().put("message", "e2e commit")
        .put("stage_all", true).put("path", workspace.toString())));
    assertSuccess(dispatcher.execute("git_log", object().put("path", workspace.toString())
        .put("count", 5)), "e2e commit");
    ToolResult diagnostics = dispatcher.execute("diagnostics", object());
    assertThat(switch (diagnostics) {
      case ToolResult.Success success -> success.output().text();
      case ToolResult.Failure failure -> failure.error().detail();
    }).isNotEmpty();
  }

  private static void assertSuccess(ToolResult result, String fragment) {
    assertThat(result).isInstanceOfSatisfying(ToolResult.Success.class,
        success -> assertThat(success.output().text()).contains(fragment));
  }

  private static void assertSuccess(ToolResult result, String... fragments) {
    assertThat(success(result).output().text()).contains(fragments);
  }

  private static ToolResult.Success success(ToolResult result) {
    assertThat(result).isInstanceOf(ToolResult.Success.class);
    return (ToolResult.Success) result;
  }

  private static ObjectNode object() {
    return JSON.createObjectNode();
  }

  private static void run(Path root, String... arguments) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).directory(root.toFile())
        .redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(),
        java.nio.charset.StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(output).isZero();
  }
}
