package com.github.skanga.ajent.tools.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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

  private static void assertSuccess(ToolResult result, String fragment) {
    assertThat(result).isInstanceOfSatisfying(ToolResult.Success.class,
        success -> assertThat(success.output().text()).contains(fragment));
  }
}
