package com.github.skanga.ajent.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class MemoryToolsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void portsRememberForgetAndWipeProtocol() {
    var store = new FakeStore();
    var tools = new MemoryTools(store);
    assertThat(success(tools.execute("remember", JSON.createObjectNode()
        .put("text", "  fact alpha  ").put("scope", "global").put("pin", true)
        .put("tags", "one, two").put("supersedes", "old"))))
        .isEqualTo("Remembered [id-1]. note (2 old record(s) rolled.)");
    assertThat(store.append.scope()).isEqualTo("user");
    assertThat(store.append.text()).isEqualTo("fact alpha");
    assertThat(store.append.pinned()).isTrue();
    assertThat(store.append.tags()).containsExactly("one", "two");
    assertThat(store.append.supersedesId()).isEqualTo("old");

    store.records.add(new MemoryStore.Record("id-1", "fact alpha"));
    assertThat(success(tools.execute("forget", JSON.createObjectNode()
        .put("substring", "alpha").put("dry_run", true)))).contains("Would remove 1", "fact alpha");
    assertThat(success(tools.execute("forget", JSON.createObjectNode().put("substring", "alpha"))))
        .isEqualTo("Forgot 1 record(s) matching \"alpha\".");
    assertThat(success(tools.execute("forget", JSON.createObjectNode().put("id", "missing"))))
        .isEqualTo("No record with id missing.");
    assertThat(success(tools.execute("wipe_memory", JSON.createObjectNode().put("scope", "user"))))
        .contains("confirm:true");
    assertThat(success(tools.execute("wipe_memory", JSON.createObjectNode().put("scope", "user")
        .put("confirm", true)))).isEqualTo("Wiped 3 record(s) from scope 'user'.");
  }

  @Test
  void coversDedupValidationEmptyPreviewsAndBackendErrors() {
    var store = new FakeStore();
    var tools = new MemoryTools(store);
    assertThat(failure(tools.execute("remember", JSON.createObjectNode().put("text", "  ")))
        .error().kind()).isEqualTo(ToolErrorKind.INVALID_ARGS);
    assertThat(failure(tools.execute("remember", JSON.createObjectNode().put("text", "x")
        .put("scope", "bad"))).error().detail()).contains("unknown scope");
    store.result = new MemoryStore.AppendResult("id-1", true, "", 0, "");
    assertThat(success(tools.execute("remember", JSON.createObjectNode().put("text", "x"))))
        .isEqualTo("Already knew that (refreshed id-1).");
    store.result = new MemoryStore.AppendResult("", false, "", 0, "disk full");
    assertThat(failure(tools.execute("remember", JSON.createObjectNode().put("text", "x")))
        .error().detail()).contains("disk full");
    assertThat(failure(tools.execute("forget", JSON.createObjectNode())).error().kind())
        .isEqualTo(ToolErrorKind.INVALID_ARGS);
    assertThat(success(tools.execute("forget", JSON.createObjectNode().put("substring", "none")
        .put("dry_run", true)))).startsWith("No records match");
    assertThat(failure(tools.execute("wipe_memory", JSON.createObjectNode().put("scope", "bad")))
        .error().detail()).contains("unknown scope");
    store.wipe = OptionalInt.empty();
    assertThat(failure(tools.execute("wipe_memory", JSON.createObjectNode().put("scope", "user")
        .put("confirm", true))).error().detail()).contains("unresolvable");
    assertThat(failure(tools.execute("other", JSON.createObjectNode())).error().kind())
        .isEqualTo(ToolErrorKind.UNKNOWN);
    assertThat(failure(new MemoryTools(null).execute("remember", JSON.createObjectNode()))
        .error().detail()).contains("unavailable");
  }

  @Test
  void parsesArrayAndWeakStringTags() {
    assertThat(MemoryTools.parseTags(null)).isEmpty();
    assertThat(MemoryTools.parseTags(JSON.createArrayNode().add("a").add(1).add("b")))
        .containsExactly("a", "b");
    assertThat(MemoryTools.parseTags(JSON.getNodeFactory().textNode(" a, ,b,")))
        .containsExactly("a", "b");
  }

  private static final class FakeStore implements MemoryStore {
    private final List<Record> records = new ArrayList<>();
    private AppendRequest append;
    private AppendResult result = new AppendResult("id-1", false, "note", 2, "");
    private OptionalInt wipe = OptionalInt.of(3);
    @Override public List<String> scopes() { return List.of("project", "user"); }
    @Override public AppendResult append(AppendRequest request) { append = request; return result; }
    @Override public int forgetById(String id) { return id.equals("id-1") ? 1 : 0; }
    @Override public int forgetBySubstring(String substring) { return substring.equals("alpha") ? 1 : 0; }
    @Override public List<Record> previewForget(String substring) {
      return records.stream().filter(record -> record.text().contains(substring)).toList();
    }
    @Override public OptionalInt wipe(String scope) { return wipe; }
  }
  private static String success(ToolResult result) {
    return ((ToolResult.Success) result).output().text();
  }
  private static ToolResult.Failure failure(ToolResult result) { return (ToolResult.Failure) result; }
}
