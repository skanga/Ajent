package com.github.skanga.ajent.tools.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.tools.host.HostServices.DocHit;
import com.github.skanga.ajent.tools.host.HostServices.DocResponse;
import com.github.skanga.ajent.tools.host.HostServices.SkillResolution;
import com.github.skanga.ajent.tools.host.HostServices.SubagentResponse;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HostToolsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void portsTodoSkillDocsAndTaskProtocolShells() {
    var captured = new ArrayList<HostServices.TodoItem>();
    var runner = new FakeRunner();
    var tools = new HostTools(captured::addAll,
        name -> new SkillResolution(Optional.of("instructions for " + name), ""),
        query -> new DocResponse(List.of(new DocHit("docs", "guide.md", 3, 5, .75,
            "use the guide")), "BM25", ""), runner);
    var todos = JSON.createArrayNode()
        .add(JSON.createObjectNode().put("content", "one").put("status", "pending"))
        .add(JSON.createObjectNode().put("content", "two").put("status", "in_progress"))
        .add(JSON.createObjectNode().put("content", "three").put("status", "completed"));
    assertThat(success(tools.execute("todo", JSON.createObjectNode().put("display_description", "Plan")
        .set("todos", todos)))).contains("Plan\n\n", "[ ] one", "[-] two", "[x] three");
    assertThat(captured).hasSize(3);
    assertThat(success(tools.execute("skill", JSON.createObjectNode().put("name", "java"))))
        .isEqualTo("instructions for java");
    assertThat(success(tools.execute("search_docs", JSON.createObjectNode().put("query", "guide")
        .put("k", 99).put("display_description", "Searching"))))
        .contains("Searching", "1 results (mode: BM25)", "docs:guide.md:3-5", "score 0.7500");
    assertThat(runner.limit).isZero();
    assertThat(success(tools.execute("task", JSON.createObjectNode().put("prompt", "inspect")
        .put("agent_type", "reviewer")))).isEqualTo("done");
    assertThat(runner.request.agentType()).isEqualTo("reviewer");
  }

  @Test
  void coversUnavailableAndErrorPaths() {
    var unavailable = new HostTools(null, null, null, null);
    assertThat(failure(unavailable.execute("skill", JSON.createObjectNode())).error().kind())
        .isEqualTo(ToolErrorKind.INVALID_ARGS);
    assertThat(failure(unavailable.execute("skill", JSON.createObjectNode().put("name", "x")))
        .error().detail()).contains("unknown skill");
    assertThat(failure(unavailable.execute("search_docs", JSON.createObjectNode())).error().kind())
        .isEqualTo(ToolErrorKind.INVALID_ARGS);
    assertThat(failure(unavailable.execute("search_docs", JSON.createObjectNode().put("query", "x")))
        .error().detail()).contains("unavailable");
    assertThat(failure(unavailable.execute("task", JSON.createObjectNode().put("prompt", "x")))
        .error().detail()).contains("subagent unavailable");
    assertThat(failure(unavailable.execute("missing", JSON.createObjectNode())).error().kind())
        .isEqualTo(ToolErrorKind.UNKNOWN);

    var runner = new FakeRunner();
    runner.error = true;
    var errors = new HostTools(null, name -> new SkillResolution(Optional.empty(), "bad skill"),
        query -> new DocResponse(List.of(), "", "index failed"), runner);
    assertThat(failure(errors.execute("skill", JSON.createObjectNode().put("name", "x")))
        .error().detail()).isEqualTo("bad skill");
    assertThat(failure(errors.execute("search_docs", JSON.createObjectNode().put("query", "x")))
        .error().detail()).contains("index failed");
    assertThat(failure(errors.execute("task", JSON.createObjectNode().put("prompt", "x")))
        .error().detail()).isEqualTo("done");
  }

  @Test
  void formatsEmptyDocsAndDefaultMode() {
    var tools = new HostTools(null, null,
        query -> new DocResponse(List.of(), "", ""), new FakeRunner());
    assertThat(success(tools.execute("search_docs", JSON.createObjectNode()
        .put("query", "missing").put("k", -1)))).isEqualTo("No matching documents for: missing");
  }

  private static final class FakeRunner implements HostServices.SubagentRunner {
    private HostServices.SubagentRequest request;
    private boolean error;
    private int limit;
    @Override public boolean available() { return true; }
    @Override public SubagentResponse run(HostServices.SubagentRequest value) {
      request = value;
      return new SubagentResponse("done", error);
    }
  }
  private static String success(ToolResult result) {
    return ((ToolResult.Success) result).output().text();
  }
  private static ToolResult.Failure failure(ToolResult result) { return (ToolResult.Failure) result; }
}
