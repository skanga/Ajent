package com.github.skanga.ajent.core.persistence;

import com.github.skanga.ajent.core.AgenttyDebugLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.domain.ModelId;
import com.github.skanga.ajent.domain.Profile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** AgenTTY-compatible atomic persistence for {@code settings.json}. */
public final class SettingsStore {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Path dataDirectory;
  private final Path settingsFile;

  public SettingsStore(Path dataDirectory) {
    this.dataDirectory = dataDirectory.toAbsolutePath();
    this.settingsFile = this.dataDirectory.resolve("settings.json");
  }

  public Settings load() {
    if (!Files.isRegularFile(settingsFile)) return Settings.defaults();
    try {
      JsonNode root = JSON.readTree(settingsFile.toFile());
      if (root == null || !root.isObject()) return Settings.defaults();
      var favorites = new ArrayList<ModelId>();
      JsonNode favoriteValues = root.path("favorite_models");
      if (favoriteValues.isArray()) {
        for (JsonNode value : favoriteValues) {
          if (value.isTextual()) favorites.add(new ModelId(value.textValue()));
        }
      }
      var grants = new ArrayList<String>();
      JsonNode grantValues = root.path("always_allow_tools");
      if (grantValues.isArray()) {
        for (JsonNode value : grantValues) {
          if (value.isTextual()) grants.add(value.textValue());
        }
      }
      int persistedProfile = root.path("profile").isIntegralNumber()
          ? root.path("profile").intValue() : 0;
      return new Settings(
          new ModelId(text(root, "model_id")),
          Profile.fromPersistedOrdinal(persistedProfile),
          favorites,
          text(root, "provider"),
          stringMap(root.path("provider_keys")),
          stringMap(root.path("provider_models")),
          text(root, "effort"),
          grants);
    } catch (IOException | RuntimeException exception) {
      AgenttyDebugLog.log("persistence.load_settings", exception);
      return Settings.defaults();
    }
  }

  public boolean save(Settings settings) {
    try {
      Files.createDirectories(dataDirectory);
      ObjectNode root = JSON.createObjectNode();
      root.put("model_id", settings.modelId().value());
      root.put("profile", settings.profile().ordinal());
      ArrayNode favorites = root.putArray("favorite_models");
      settings.favoriteModels().forEach(model -> favorites.add(model.value()));
      if (!settings.provider().isEmpty()) root.put("provider", settings.provider());
      addMap(root, "provider_keys", settings.providerKeys());
      addMap(root, "provider_models", settings.providerModels());
      if (!settings.effort().isEmpty()) root.put("effort", settings.effort());
      if (!settings.alwaysAllowTools().isEmpty()) {
        ArrayNode grants = root.putArray("always_allow_tools");
        settings.alwaysAllowTools().forEach(grants::add);
      }
      return writeAtomic(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
    } catch (IOException | RuntimeException exception) {
      return false;
    }
  }

  private static String text(JsonNode root, String field) {
    JsonNode value = root.path(field);
    return value.isTextual() ? value.textValue() : "";
  }

  private static Map<String, String> stringMap(JsonNode value) {
    if (!value.isObject()) return Map.of();
    var result = new LinkedHashMap<String, String>();
    value.properties().forEach(entry -> {
      if (entry.getValue().isTextual()) result.put(entry.getKey(), entry.getValue().textValue());
    });
    return result;
  }

  private static void addMap(ObjectNode root, String field, Map<String, String> values) {
    if (values.isEmpty()) return;
    ObjectNode object = root.putObject(field);
    new TreeMap<>(values).forEach(object::put);
  }

  private boolean writeAtomic(byte[] content) throws IOException {
    Path temporary = settingsFile.resolveSibling(settingsFile.getFileName() + ".tmp");
    try {
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      try {
        Files.move(temporary, settingsFile,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
      }
      return true;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
