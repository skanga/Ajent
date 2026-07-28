package com.github.skanga.ajent.core.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.domain.Attachment;
import com.github.skanga.ajent.domain.CheckpointId;
import com.github.skanga.ajent.domain.CompactionRecord;
import com.github.skanga.ajent.domain.ImageContent;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.MessageId;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThreadStoreTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void saveReportsFailureInsteadOfSilentlySucceedingWhenTargetIsUnwritable(
      @TempDir Path directory) throws Exception {
    // dataDirectory sits under a regular file, so the threads directory cannot be created.
    Path blocker = Files.createFile(directory.resolve("blocker"));
    var store = new ThreadStore(blocker.resolve("nested"));
    var thread = new Thread(new ThreadId("thread"), "Title",
        List.of(new Message(new MessageId("m"), Role.USER, "hi", List.of(), List.of(),
            "", "", List.of(), Instant.ofEpochSecond(1),
            Optional.empty(), Optional.empty(), false)),
        Instant.ofEpochSecond(1), Instant.ofEpochSecond(2), List.of());
    assertThat(store.save(thread)).isFalse();
  }

  @Test
  void savesAndLoadsEveryPersistedConversationField(@TempDir Path directory) throws Exception {
    var store = new ThreadStore(directory);
    var call = new ToolUse(new ToolCallId("call"), new ToolName("read"),
        Map.of("path", "a.txt"), new ToolStatus.Done("contents"));
    var message = new Message(
        new MessageId("message"), Role.ASSISTANT, "answer",
        List.of(new ImageContent("image/png", new byte[] {1, 2, 3})),
        List.of(new Attachment(Attachment.Kind.FILE_REF, new byte[] {0, -1},
            "a.txt", "text/plain", "a", 4, 5, 2)),
        "thinking", "signature", List.of(call), Instant.ofEpochSecond(12),
        Optional.of(new CheckpointId("checkpoint")), Optional.of("warning"), true);
    var thread = new Thread(new ThreadId("thread"), "Title", List.of(message),
        Instant.ofEpochSecond(1), Instant.ofEpochSecond(2),
        List.of(new CompactionRecord(1, "summary", Instant.ofEpochSecond(3))));

    assertThat(store.save(thread)).isTrue();
    Path file = directory.resolve("threads/thread.json");
    var raw = JSON.readTree(Files.readString(file));
    assertThat(raw.path("id").textValue()).isEqualTo("thread");
    assertThat(raw.path("created_at").longValue()).isEqualTo(1);
    assertThat(raw.at("/messages/0/id").textValue()).isEqualTo("message");
    assertThat(raw.at("/messages/0/images/0/data").textValue()).isEqualTo("AQID");
    assertThat(raw.at("/messages/0/attachments/0/body").textValue()).isEqualTo("AP8=");
    assertThat(raw.at("/messages/0/tool_calls/0/status").textValue()).isEqualTo("done");
    assertThat(raw.at("/compactions/0/up_to_index").intValue()).isEqualTo(1);
    assertThat(Files.exists(directory.resolve("threads/thread.json.tmp"))).isFalse();

    Thread loaded = ((ThreadLoadResult.Success) store.load(new ThreadId("thread"))).thread();
    assertThat(loaded.id()).isEqualTo(thread.id());
    assertThat(loaded.createdAt()).isEqualTo(thread.createdAt());
    Message restored = loaded.messages().getFirst();
    assertThat(restored.id()).isEqualTo(message.id());
    assertThat(restored.images().getFirst().bytes()).containsExactly(1, 2, 3);
    assertThat(restored.attachments().getFirst().body()).containsExactly(0, -1);
    assertThat(restored.thinking()).isEqualTo("thinking");
    assertThat(restored.thinkingSignature()).isEqualTo("signature");
    assertThat(restored.checkpointId()).contains(new CheckpointId("checkpoint"));
    assertThat(restored.error()).contains("warning");
    assertThat(restored.isCompactSummary()).isTrue();
    assertThat(restored.toolCalls().getFirst().status())
        .isEqualTo(new ToolStatus.Done("contents"));
    assertThat(loaded.compactions()).containsExactly(
        new CompactionRecord(1, "summary", Instant.ofEpochSecond(3)));
  }

  @Test
  void loadsLegacyStatusesAndCoercesInterruptedToolsToTerminalFailure(@TempDir Path directory)
      throws Exception {
    Path file = directory.resolve("legacy.json");
    Files.writeString(file, """
        {"id":"legacy","messages":[{"role":"assistant","text":"", "tool_calls":[
          {"id":"a","name":"read","args":{},"status":3,"output":"ok"},
          {"id":"b","name":"bash","args":{},"status":"running","output":""}
        ]}]}
        """);
    Thread loaded = ((ThreadLoadResult.Success) new ThreadStore(directory).load(file)).thread();
    assertThat(loaded.messages().getFirst().toolCalls()).extracting(ToolUse::status)
        .containsExactly(new ToolStatus.Done("ok"), new ToolStatus.Failed("interrupted"));
    assertThat(loaded.messages().getFirst().id().value()).hasSize(16);
  }

  @Test
  void returnsTypedErrorsAndMetadataWalkSkipsBadFilesAndSidecar(@TempDir Path directory)
      throws Exception {
    var store = new ThreadStore(directory);
    Path threads = Files.createDirectories(directory.resolve("threads"));
    Files.writeString(threads.resolve("new.json"),
        "{\"id\":\"new\",\"title\":\"N\",\"created_at\":1,\"updated_at\":20,"
            + "\"messages\":[{\"text\":\"large body\"}]}");
    Files.writeString(threads.resolve("old.json"),
        "{\"id\":\"old\",\"title\":\"O\",\"updated_at\":10,\"messages\":[]}");
    Files.writeString(threads.resolve("bad.json"), "{bad}");
    Files.writeString(threads.resolve("missing.json"), "{\"messages\":[]}");
    Files.writeString(threads.resolve("acp_sessions.json"), "{}");

    var bad = (ThreadLoadResult.Failure) store.load(threads.resolve("bad.json"));
    assertThat(bad.error().kind()).isEqualTo(DeserializeError.Kind.JSON_PARSE);
    var missing = (ThreadLoadResult.Failure) store.load(threads.resolve("missing.json"));
    assertThat(missing.error().kind()).isEqualTo(DeserializeError.Kind.MISSING_FIELD);
    assertThat(missing.error().field()).isEqualTo("id");
    assertThat(((ThreadLoadResult.Failure) store.load(directory.resolve("absent.json")))
        .error().kind()).isEqualTo(DeserializeError.Kind.IO);

    List<Thread> metadata = store.loadAllMetadata();
    assertThat(metadata).extracting(value -> value.id().value()).containsExactly("new", "old");
    assertThat(metadata).allSatisfy(value -> assertThat(value.messages()).isEmpty());
  }

  @Test
  void validatesVariantAndFieldTypesAndSkipsMalformedCompactions(@TempDir Path directory)
      throws Exception {
    var store = new ThreadStore(directory);
    Path invalidStatus = directory.resolve("status.json");
    Files.writeString(invalidStatus, "{\"id\":\"x\",\"messages\":[{\"tool_calls\":[{"
        + "\"status\":\"mystery\"}]}]}");
    var status = (ThreadLoadResult.Failure) store.load(invalidStatus);
    assertThat(status.error().kind()).isEqualTo(DeserializeError.Kind.INVALID_VARIANT_TAG);
    assertThat(status.error().field()).isEqualTo("tool_calls[*].status");

    Path invalidTimestamp = directory.resolve("timestamp.json");
    Files.writeString(invalidTimestamp,
        "{\"id\":\"x\",\"messages\":[{\"timestamp\":\"soon\"}]}");
    assertThat(((ThreadLoadResult.Failure) store.load(invalidTimestamp)).error().kind())
        .isEqualTo(DeserializeError.Kind.INVALID_VALUE);

    Path compactions = directory.resolve("compactions.json");
    Files.writeString(compactions, "{\"id\":\"x\",\"messages\":[{}],\"compactions\":["
        + "{\"up_to_index\":-1},{\"up_to_index\":2},{\"up_to_index\":1,"
        + "\"summary\":\"kept\",\"created_at\":3}]}");
    Thread loaded = ((ThreadLoadResult.Success) store.load(compactions)).thread();
    assertThat(loaded.compactions()).containsExactly(
        new CompactionRecord(1, "kept", Instant.ofEpochSecond(3)));
  }

  @Test
  void idsAndTitlesMatchReferenceShapeAndUtf8ByteLimit(@TempDir Path directory) {
    var store = new ThreadStore(directory);
    assertThat(store.newId().value()).matches("[0-9a-f]{16}");
    assertThat(store.titleFromFirstMessage("")).isEqualTo("New thread");
    assertThat(store.titleFromFirstMessage("one\ntwo\rthree")).isEqualTo("one two three");
    String title = store.titleFromFirstMessage("🙂".repeat(20));
    assertThat(title).endsWith("...");
    assertThat(title.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(60);
  }
}
