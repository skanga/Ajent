package com.github.skanga.ajent.tools.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.domain.CancellationSignal;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.tools.catalog.ToolCatalog;
import com.github.skanga.ajent.tools.catalog.ToolKind;
import com.github.skanga.ajent.tools.fs.FileTools;
import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import com.github.skanga.ajent.tools.git.GitTools;
import com.github.skanga.ajent.tools.host.HostTools;
import com.github.skanga.ajent.tools.memory.MemoryStore;
import com.github.skanga.ajent.tools.memory.MemoryTools;
import com.github.skanga.ajent.tools.process.ProcessTools;
import com.github.skanga.ajent.tools.policy.Effect;
import com.github.skanga.ajent.tools.policy.EffectSet;
import com.github.skanga.ajent.tools.search.RepoMapTools;
import com.github.skanga.ajent.tools.search.SearchTools;
import com.github.skanga.ajent.tools.web.WebTools;
import com.github.skanga.ajent.tools.web.WebTransport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolDispatcherTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void mapsEveryClosedCatalogKindToExactlyOneFamily() {
    assertThat(ToolKind.values()).allSatisfy(kind -> assertThat(ToolDispatcher.family(kind)).isNotNull());
    assertThat(ToolCatalog.all()).extracting(spec -> ToolDispatcher.family(spec.kind()))
        .containsExactly(ToolFamily.FILESYSTEM, ToolFamily.FILESYSTEM, ToolFamily.FILESYSTEM,
            ToolFamily.PROCESS, ToolFamily.SEARCH, ToolFamily.SEARCH, ToolFamily.FILESYSTEM,
            ToolFamily.HOST, ToolFamily.WEB, ToolFamily.WEB, ToolFamily.SEARCH, ToolFamily.PROCESS,
            ToolFamily.GIT, ToolFamily.GIT, ToolFamily.GIT, ToolFamily.GIT, ToolFamily.MEMORY,
            ToolFamily.MEMORY, ToolFamily.MEMORY, ToolFamily.HOST, ToolFamily.HOST,
            ToolFamily.HOST, ToolFamily.REPOSITORY_MAP);
  }

  @Test
  void dispatchesRepresentativeCallsAcrossAllFamilies(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("sample.txt"), "needle");
    Files.writeString(root.resolve("sample.java"), "class Sample {}\n");
    var dispatcher = dispatcher(root);
    assertSuccess(dispatcher.execute("read", JSON.createObjectNode().put("path", "sample.txt")));
    assertSuccess(dispatcher.execute("bash", JSON.createObjectNode().put("command", "echo ok")));
    var processProgress = new ArrayList<String>();
    assertSuccess(dispatcher.execute("bash",
        JSON.createObjectNode().put("command", "echo progress_probe"), new CancellationSignal(),
        processProgress::add));
    assertThat(processProgress).isNotEmpty();
    assertThat(processProgress.getLast()).contains("progress_probe");
    assertSuccess(dispatcher.execute("grep", JSON.createObjectNode().put("pattern", "needle")));
    assertSuccess(dispatcher.execute("repo_map", JSON.createObjectNode()));
    assertThat(dispatcher.execute("git_status", JSON.createObjectNode())).isInstanceOf(ToolResult.Failure.class);
    assertSuccess(dispatcher.execute("todo", JSON.createObjectNode()));
    assertSuccess(dispatcher.execute("remember", JSON.createObjectNode().put("text", "fact")));
    assertSuccess(dispatcher.execute("web_fetch", JSON.createObjectNode().put("url", "https://example.test")));
    assertThat(((ToolResult.Failure) dispatcher.execute("nope", JSON.createObjectNode())).error().kind())
        .isEqualTo(ToolErrorKind.NOT_FOUND);
  }

  @Test
  void normalizesNonObjectArgumentsAndContainsToolCrashes(@TempDir Path root) {
    var dispatcher = dispatcher(root);
    ToolResult.Failure normalized = (ToolResult.Failure) dispatcher.execute(
        "read", JSON.createArrayNode().add("not an object"));
    assertThat(normalized.error()).isEqualTo(
        new ToolError(ToolErrorKind.UNKNOWN, "[invalid args] path required"));
    ToolResult.Failure unavailable = (ToolResult.Failure) dispatcher.execute(
        "task", JSON.createObjectNode());
    assertThat(unavailable.error()).isEqualTo(new ToolError(ToolErrorKind.UNKNOWN,
        "task: subagent unavailable (not configured, or max nesting depth reached)."));
    ToolResult.Failure missingQuery = (ToolResult.Failure) dispatcher.execute(
        "search_docs", JSON.createObjectNode());
    assertThat(missingQuery.error()).isEqualTo(new ToolError(ToolErrorKind.UNKNOWN,
        "search_docs: `query` is required."));

    var broken = new ToolDispatcher(null, null, null, null, null, null, null, null);
    ToolResult.Failure crashed = (ToolResult.Failure) broken.execute(
        "read", JSON.createObjectNode().put("path", "sample.txt"));
    assertThat(crashed.error().kind()).isEqualTo(ToolErrorKind.UNKNOWN);
    assertThat(crashed.error().detail()).startsWith("tool crashed: ");
  }

  @Test
  void dispatchesDynamicToolsAndContainsTheirFailures(@TempDir Path root) {
    var calls = new ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
    var external = new ExternalToolRuntime() {
      @Override public List<ToolSpecification> specifications() {
        return List.of(new ToolSpecification("remote", "remote tool",
            JSON.createObjectNode().put("type", "object"), false));
      }

      @Override public Optional<EffectSet> effects(String name) {
        return "remote".equals(name)
            ? Optional.of(EffectSet.of(Effect.NET)) : Optional.empty();
      }

      @Override public ToolResult execute(
          String name, com.fasterxml.jackson.databind.node.ObjectNode arguments) {
        calls.add(arguments.deepCopy());
        if (arguments.path("crash").asBoolean()) throw new IllegalStateException("remote broke");
        return new ToolResult.Success(new ToolOutput(arguments.toString(), Optional.empty()));
      }
    };
    var dispatcher = new ToolDispatcher(baseFiles(root), null, null, null, null, null, null, null,
        external);

    assertSuccess(dispatcher.execute("remote", JSON.createObjectNode().put("value", 7)));
    assertSuccess(dispatcher.execute("remote", JSON.createArrayNode().add("ignored")));
    assertThat(calls).extracting(Object::toString).containsExactly("{\"value\":7}", "{}");
    assertThat(dispatcher.execute("remote", JSON.createObjectNode().put("crash", true)))
        .isInstanceOfSatisfying(ToolResult.Failure.class,
            failure -> assertThat(failure.error().detail()).isEqualTo("tool crashed: remote broke"));
    assertThat(dispatcher.execute("absent", JSON.createObjectNode()))
        .isInstanceOfSatisfying(ToolResult.Failure.class,
            failure -> assertThat(failure.error().kind()).isEqualTo(ToolErrorKind.NOT_FOUND));
  }

  @Test
  void enforcesCatalogBudgetsAtTheRealDispatchBoundary(@TempDir Path root) throws Exception {
    String content = "HEAD_SENTINEL" + "x".repeat(85_000) + "TAIL_SENTINEL";
    Files.writeString(root.resolve("oversized.txt"), content);

    ToolDispatcher dispatcher = dispatcher(root);
    ToolResult.Success result = (ToolResult.Success) dispatcher.execute(
        "read", JSON.createObjectNode().put("path", "oversized.txt")
            .put("offset", 1).put("limit", 2_000));

    assertThat(result.output().text()).startsWith("HEAD_SENTINEL")
        .contains("[... 29 chars elided", "output exceeded tool's budget")
        .doesNotContain("TAIL_SENTINEL");
    assertThat(result.output().text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isLessThan(81_000);
  }

  @Test
  void budgetingPreservesStructuredChangesAndNeverTruncatesTypedErrors() {
    var change = new FileChange("sample.java", 1, 1, "before", "after");
    var success = new ToolResult.Success(
        new ToolOutput("z".repeat(50_000), Optional.of(change)));

    ToolResult.Success bounded = (ToolResult.Success) ToolDispatcher.applyBudget(
        ToolCatalog.byName("write").orElseThrow(), success);

    assertThat(bounded.output().text()).contains("output exceeded tool's budget");
    assertThat(bounded.output().change().orElseThrow()).isSameAs(change);

    var failure = new ToolResult.Failure(
        new ToolError(ToolErrorKind.UNKNOWN, "e".repeat(50_000)));
    assertThat(ToolDispatcher.applyBudget(
        ToolCatalog.byName("write").orElseThrow(), failure)).isSameAs(failure);
  }

  private static ToolDispatcher dispatcher(Path root) {
    var sandbox = new WorkspaceSandbox(root, root, root);
    var store = new MemoryStore() {
      @Override public List<String> scopes() { return List.of("user"); }
      @Override public AppendResult append(AppendRequest request) {
        return new AppendResult("1", false, "", 0, "");
      }
      @Override public int forgetById(String id) { return 0; }
      @Override public int forgetBySubstring(String substring) { return 0; }
      @Override public List<Record> previewForget(String substring) { return List.of(); }
      @Override public OptionalInt wipe(String scope) { return OptionalInt.of(0); }
    };
    var transport = (WebTransport) request -> new WebTransport.Response(200,
        Map.of("content-type", List.of("text/plain")), "web", "");
    return new ToolDispatcher(new FileTools(sandbox), new ProcessTools(sandbox),
        new SearchTools(sandbox), new RepoMapTools(sandbox), new GitTools(sandbox),
        new HostTools(null, null, null, null), new MemoryTools(store), new WebTools(transport));
  }

  private static FileTools baseFiles(Path root) {
    return new FileTools(new WorkspaceSandbox(root, root, root));
  }

  private static void assertSuccess(ToolResult result) {
    assertThat(result).isInstanceOf(ToolResult.Success.class);
  }
}
