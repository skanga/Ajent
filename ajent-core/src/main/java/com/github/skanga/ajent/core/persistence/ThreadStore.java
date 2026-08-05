package com.github.skanga.ajent.core.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.core.AjentDebugLog;
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
import java.io.IOException;
import java.io.Serial;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** Filesystem-backed Ajent-compatible thread persistence. */
public final class ThreadStore {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {};
  private final Path dataDirectory;
  private final Path threadsDirectory;

  public ThreadStore(Path dataDirectory) {
    this.dataDirectory = dataDirectory.toAbsolutePath();
    this.threadsDirectory = this.dataDirectory.resolve("threads");
  }

  public boolean save(Thread thread) {
    if (thread.id().value().isEmpty() || thread.messages().isEmpty()) return false;
    try {
      Files.createDirectories(threadsDirectory);
      ObjectNode root = JSON.createObjectNode();
      root.put("id", thread.id().value());
      root.put("title", thread.title());
      root.put("created_at", thread.createdAt().getEpochSecond());
      root.put("updated_at", thread.updatedAt().getEpochSecond());
      ArrayNode messages = root.putArray("messages");
      thread.messages().forEach(message -> messages.add(messageToJson(message)));
      if (!thread.compactions().isEmpty()) {
        ArrayNode compactions = root.putArray("compactions");
        for (CompactionRecord record : thread.compactions()) {
          ObjectNode value = compactions.addObject();
          value.put("up_to_index", record.upToIndex());
          value.put("summary", record.summary());
          value.put("created_at", record.createdAt().getEpochSecond());
        }
      }
      return writeJsonAtomic(
          threadsDirectory.resolve(thread.id().value() + ".json"),
          JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
    } catch (IOException | RuntimeException exception) {
      AjentDebugLog.log("persistence.save", exception);
      return false;
    }
  }

  public ThreadLoadResult load(Path path) {
    if (!Files.isRegularFile(path)) {
      return failure(DeserializeError.Kind.IO, "", "open failed: " + path);
    }
    JsonNode root;
    try {
      root = JSON.readTree(path.toFile());
    } catch (IOException exception) {
      return failure(DeserializeError.Kind.JSON_PARSE, "", "invalid JSON");
    }
    try {
      return new ThreadLoadResult.Success(parseThread(root));
    } catch (InvalidData exception) {
      return new ThreadLoadResult.Failure(exception.error);
    } catch (RuntimeException exception) {
      return failure(DeserializeError.Kind.INVALID_VALUE, "", "invalid thread value");
    }
  }

  /** Loads a saved conversation without exposing the persistence layout to callers. */
  public ThreadLoadResult load(ThreadId id) {
    return load(threadsDirectory.resolve(id.value() + ".json"));
  }

  public List<Thread> loadAllMetadata() {
    if (!Files.isDirectory(threadsDirectory)) return List.of();
    var result = new ArrayList<Thread>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(threadsDirectory, "*.json")) {
      for (Path file : files) {
        Path fileName = file.getFileName();
        if (fileName == null || "acp_sessions.json".equals(fileName.toString())) continue;
        readMetadata(file).ifPresent(result::add);
      }
    } catch (IOException exception) {
      AjentDebugLog.log("persistence.load_all", exception);
      return List.of();
    }
    result.sort(Comparator.comparing(Thread::updatedAt).reversed());
    return List.copyOf(result);
  }

  public boolean delete(ThreadId id) {
    try {
      return Files.deleteIfExists(threadsDirectory.resolve(id.value() + ".json"));
    } catch (IOException exception) {
      AjentDebugLog.log("persistence.delete", exception);
      return false;
    }
  }

  public ThreadId newId() {
    return new ThreadId("%016x".formatted(ThreadLocalRandom.current().nextLong()));
  }

  public String titleFromFirstMessage(String text) {
    return deriveTitleFromFirstMessage(text);
  }

  public static String deriveTitleFromFirstMessage(String text) {
    String title = Objects.requireNonNull(text, "text").replace('\n', ' ').replace('\r', ' ');
    if (title.getBytes(StandardCharsets.UTF_8).length > 60) {
      title = utf8Prefix(title, 57) + "...";
    }
    return title.isEmpty() ? "New thread" : title;
  }

  private Thread parseThread(JsonNode root) {
    if (!root.isObject()) invalid(DeserializeError.Kind.INVALID_VALUE, "", "expected object");
    String id = root.path("id").asText();
    if (id.isEmpty()) invalid(DeserializeError.Kind.MISSING_FIELD, "id", "thread JSON has no id");
    Instant created = integerInstant(root, "created_at", Instant.now());
    Instant updated = integerInstant(root, "updated_at", Instant.now());
    var messages = new ArrayList<Message>();
    JsonNode messageValues = root.path("messages");
    if (!messageValues.isMissingNode() && !messageValues.isArray()) {
      invalid(DeserializeError.Kind.INVALID_VALUE, "messages", "expected array");
    }
    messageValues.forEach(value -> messages.add(parseMessage(value)));
    var compactions = new ArrayList<CompactionRecord>();
    JsonNode compactionValues = root.path("compactions");
    if (compactionValues.isArray()) {
      for (JsonNode value : compactionValues) {
        if (!value.isObject() || !value.path("up_to_index").canConvertToInt()) continue;
        int boundary = value.path("up_to_index").intValue();
        if (boundary < 0 || boundary > messages.size()) continue;
        compactions.add(new CompactionRecord(boundary, value.path("summary").asText(),
            integerInstant(value, "created_at", Instant.now())));
      }
    }
    return new Thread(new ThreadId(id), root.path("title").asText(), messages,
        created, updated, compactions);
  }

  private Message parseMessage(JsonNode value) {
    if (!value.isObject()) {
      invalid(DeserializeError.Kind.INVALID_VALUE, "messages[*]", "expected object");
    }
    Instant timestamp = integerInstant(value, "timestamp", Instant.now());
    var tools = new ArrayList<ToolUse>();
    JsonNode toolValues = value.path("tool_calls");
    if (!toolValues.isMissingNode() && !toolValues.isArray()) {
      invalid(DeserializeError.Kind.INVALID_VALUE, "messages[*].tool_calls", "expected array");
    }
    for (JsonNode tool : toolValues) tools.add(parseTool(tool));
    var images = new ArrayList<ImageContent>();
    JsonNode imageValues = value.path("images");
    if (!imageValues.isMissingNode() && !imageValues.isArray()) {
      invalid(DeserializeError.Kind.INVALID_VALUE, "messages[*].images", "expected array");
    }
    for (JsonNode image : imageValues) {
      if (!image.isObject()) continue;
      byte[] data = decode(image.path("data").asText());
      if (data.length > 0) images.add(new ImageContent(
          image.path("media_type").asText("image/png"), data));
    }
    var attachments = new ArrayList<Attachment>();
    JsonNode attachmentValues = value.path("attachments");
    if (!attachmentValues.isMissingNode() && !attachmentValues.isArray()) {
      invalid(DeserializeError.Kind.INVALID_VALUE, "messages[*].attachments", "expected array");
    }
    for (JsonNode attachment : attachmentValues) {
      if (attachment.isObject()) attachments.add(parseAttachment(attachment));
    }
    Optional<CheckpointId> checkpoint = Optional.empty();
    if (value.has("checkpoint_id")) {
      if (!value.path("checkpoint_id").isTextual()) {
        invalid(DeserializeError.Kind.INVALID_VALUE, "messages[*].checkpoint_id", "expected string");
      }
      checkpoint = Optional.of(new CheckpointId(value.path("checkpoint_id").textValue()));
    }
    Optional<String> error = value.path("error").isTextual()
        && !value.path("error").textValue().isEmpty()
        ? Optional.of(value.path("error").textValue()) : Optional.empty();
    String messageId = value.path("id").asText();
    return new Message(messageId.isEmpty() ? MessageId.random() : new MessageId(messageId),
        role(value.path("role").asText("user")), value.path("text").asText(), images,
        attachments, value.path("thinking").asText(), value.path("thinking_signature").asText(),
        tools, timestamp, checkpoint, error, value.path("is_compact_summary").asBoolean(false));
  }

  private ToolUse parseTool(JsonNode value) {
    String status = "pending";
    JsonNode persistedStatus = value.path("status");
    if (persistedStatus.isTextual()) status = persistedStatus.textValue();
    else if (persistedStatus.isNumber()) {
      String[] legacy = {"pending", "approved", "running", "done", "failed", "rejected"};
      int index = persistedStatus.intValue();
      status = index >= 0 && index < legacy.length ? legacy[index] : "pending";
    }
    String output = value.path("output").asText();
    ToolStatus toolStatus = switch (status) {
      case "done" -> new ToolStatus.Done(output);
      case "failed", "error" -> new ToolStatus.Failed(output);
      case "rejected" -> new ToolStatus.Rejected();
      case "running", "approved", "pending" ->
          new ToolStatus.Failed(output.isEmpty() ? "interrupted" : output);
      default -> throw new InvalidData(new DeserializeError(
          DeserializeError.Kind.INVALID_VARIANT_TAG, "tool_calls[*].status",
          "unknown status tag: " + status));
    };
    Map<String, Object> arguments = value.path("args").isObject()
        ? JSON.convertValue(value.path("args"), ARGUMENTS) : Map.of();
    return new ToolUse(new ToolCallId(value.path("id").asText()),
        new ToolName(value.path("name").asText()), arguments, toolStatus);
  }

  private Optional<Thread> readMetadata(Path file) {
    try (JsonParser parser = JSON.createParser(file.toFile())) {
      if (parser.nextToken() != JsonToken.START_OBJECT) return Optional.empty();
      String id = "";
      String title = "";
      Instant created = Instant.now();
      Instant updated = Instant.now();
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        String field = parser.currentName();
        JsonToken token = parser.nextToken();
        if ("id".equals(field) && token == JsonToken.VALUE_STRING) id = parser.getValueAsString();
        else if ("title".equals(field) && token == JsonToken.VALUE_STRING) title = parser.getValueAsString();
        else if ("created_at".equals(field) && token.isNumeric()) created = Instant.ofEpochSecond(parser.getLongValue());
        else if ("updated_at".equals(field) && token.isNumeric()) updated = Instant.ofEpochSecond(parser.getLongValue());
        else parser.skipChildren();
      }
      return id.isEmpty() ? Optional.empty() : Optional.of(new Thread(
          new ThreadId(id), title, List.of(), created, updated, List.of()));
    } catch (IOException | RuntimeException exception) {
      AjentDebugLog.log("persistence.read_metadata", exception);
      return Optional.empty();
    }
  }

  private static ObjectNode messageToJson(Message message) {
    ObjectNode value = JSON.createObjectNode();
    value.put("id", message.id().value());
    value.put("role", message.role().name().toLowerCase(java.util.Locale.ROOT));
    value.put("text", message.text());
    value.put("timestamp", message.timestamp().getEpochSecond());
    ArrayNode tools = value.putArray("tool_calls");
    for (ToolUse tool : message.toolCalls()) {
      ObjectNode item = tools.addObject();
      item.put("id", tool.id().value());
      item.put("name", tool.name().value());
      item.set("args", JSON.valueToTree(tool.arguments()));
      item.put("output", tool.status().output());
      item.put("status", statusName(tool.status()));
    }
    if (!message.images().isEmpty()) {
      ArrayNode images = value.putArray("images");
      for (ImageContent image : message.images()) {
        ObjectNode item = images.addObject();
        item.put("media_type", image.mediaType());
        item.put("data", Base64.getEncoder().encodeToString(image.bytes()));
      }
    }
    message.checkpointId().ifPresent(id -> value.put("checkpoint_id", id.value()));
    message.error().ifPresent(error -> value.put("error", error));
    if (message.isCompactSummary()) value.put("is_compact_summary", true);
    if (!message.thinking().isEmpty()) value.put("thinking", message.thinking());
    if (!message.thinkingSignature().isEmpty()) {
      value.put("thinking_signature", message.thinkingSignature());
    }
    if (!message.attachments().isEmpty()) {
      ArrayNode attachments = value.putArray("attachments");
      for (Attachment attachment : message.attachments()) {
        ObjectNode item = attachments.addObject();
        item.put("kind", attachmentKind(attachment.kind()));
        item.put("body", Base64.getEncoder().encodeToString(attachment.body()));
        if (!attachment.path().isEmpty()) item.put("path", attachment.path());
        if (!attachment.mediaType().isEmpty()) item.put("media_type", attachment.mediaType());
        if (!attachment.name().isEmpty()) item.put("name", attachment.name());
        if (attachment.lineNumber() > 0) item.put("line_number", attachment.lineNumber());
        item.put("line_count", attachment.lineCount());
        item.put("byte_count", attachment.byteCount());
      }
    }
    return value;
  }

  private static Attachment parseAttachment(JsonNode value) {
    Attachment.Kind kind = switch (value.path("kind").asText("paste")) {
      case "fileref" -> Attachment.Kind.FILE_REF;
      case "symbol" -> Attachment.Kind.SYMBOL;
      case "image" -> Attachment.Kind.IMAGE;
      case "output" -> Attachment.Kind.OUTPUT;
      default -> Attachment.Kind.PASTE;
    };
    return new Attachment(kind, decode(value.path("body").asText()),
        value.path("path").asText(), value.path("media_type").asText(),
        value.path("name").asText(), Math.max(0, value.path("line_number").asInt()),
        Math.max(0, value.path("line_count").asInt()),
        Math.max(0, value.path("byte_count").asLong()));
  }

  private static Instant integerInstant(JsonNode root, String field, Instant fallback) {
    if (!root.has(field)) return fallback;
    if (!root.path(field).isIntegralNumber()) {
      invalid(DeserializeError.Kind.INVALID_VALUE, field.startsWith("created")
          || field.startsWith("updated") ? field : "messages[*]." + field,
          "expected integer seconds-since-epoch");
    }
    return Instant.ofEpochSecond(root.path(field).longValue());
  }

  private static boolean writeJsonAtomic(Path target, byte[] content) throws IOException {
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
    try {
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      try {
        Files.move(temporary, target,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
      return true;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static String utf8Prefix(String value, int maximumBytes) {
    int bytes = 0;
    int end = 0;
    while (end < value.length()) {
      int codePoint = value.codePointAt(end);
      int count = new String(Character.toChars(codePoint))
          .getBytes(StandardCharsets.UTF_8).length;
      if (bytes + count > maximumBytes) break;
      bytes += count;
      end += Character.charCount(codePoint);
    }
    return value.substring(0, end);
  }

  private static byte[] decode(String value) {
    try { return Base64.getDecoder().decode(value); }
    catch (IllegalArgumentException exception) { return new byte[0]; }
  }

  private static Role role(String value) {
    return switch (value) {
      case "assistant" -> Role.ASSISTANT;
      case "system" -> Role.SYSTEM;
      default -> Role.USER;
    };
  }

  private static String statusName(ToolStatus status) {
    return switch (status) {
      case ToolStatus.Pending ignored -> "pending";
      case ToolStatus.Approved ignored -> "approved";
      case ToolStatus.Running ignored -> "running";
      case ToolStatus.Done ignored -> "done";
      case ToolStatus.Failed ignored -> "failed";
      case ToolStatus.Rejected ignored -> "rejected";
    };
  }

  private static String attachmentKind(Attachment.Kind kind) {
    return switch (kind) {
      case PASTE -> "paste";
      case FILE_REF -> "fileref";
      case SYMBOL -> "symbol";
      case IMAGE -> "image";
      case OUTPUT -> "output";
    };
  }

  private static ThreadLoadResult.Failure failure(
      DeserializeError.Kind kind, String field, String detail) {
    return new ThreadLoadResult.Failure(new DeserializeError(kind, field, detail));
  }

  private static void invalid(DeserializeError.Kind kind, String field, String detail) {
    throw new InvalidData(new DeserializeError(kind, field, detail));
  }

  @SuppressWarnings("serial")
  private static final class InvalidData extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final transient DeserializeError error;
    private InvalidData(DeserializeError error) { this.error = error; }
  }
}
