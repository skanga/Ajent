package com.github.skanga.ajent.core.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.domain.ModelId;
import com.github.skanga.ajent.domain.Profile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsStoreTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void missingAndMalformedFilesReturnAgenTTYDefaults(@TempDir Path directory) throws Exception {
    var store = new SettingsStore(directory);

    assertThat(store.load()).isEqualTo(Settings.defaults());

    Files.writeString(directory.resolve("settings.json"), "not json");
    assertThat(store.load()).isEqualTo(Settings.defaults());
  }

  @Test
  void savesAndLoadsEveryPersistedSetting(@TempDir Path directory) throws Exception {
    var settings = new Settings(
        new ModelId("claude-opus-4-6"), Profile.MINIMAL,
        List.of(new ModelId("claude-sonnet-4-6"), new ModelId("gpt-5.4")),
        "openai", Map.of("openai", "secret"),
        Map.of("anthropic", "claude-opus-4-6", "openai", "gpt-5.4"),
        "high", List.of("bash", "write"));
    var store = new SettingsStore(directory);

    assertThat(store.save(settings)).isTrue();
    assertThat(store.load()).isEqualTo(settings);
    assertThat(Files.exists(directory.resolve("settings.json.tmp"))).isFalse();

    var raw = JSON.readTree(Files.readString(directory.resolve("settings.json")));
    assertThat(raw.path("profile").intValue()).isEqualTo(2);
    assertThat(raw.path("favorite_models").findValuesAsText("ignored"))
        .isEmpty();
    assertThat(raw.path("favorite_models").get(1).textValue()).isEqualTo("gpt-5.4");
    assertThat(raw.at("/provider_keys/openai").textValue()).isEqualTo("secret");
    assertThat(raw.at("/provider_models/anthropic").textValue())
        .isEqualTo("claude-opus-4-6");
  }

  @Test
  void saveOmitsOptionalEmptyFieldsLikeAgenTTY(@TempDir Path directory) throws Exception {
    var store = new SettingsStore(directory);

    assertThat(store.save(Settings.defaults())).isTrue();

    var raw = JSON.readTree(Files.readString(directory.resolve("settings.json")));
    assertThat(raw.fieldNames()).toIterable()
        .containsExactlyInAnyOrder("model_id", "profile", "favorite_models");
    assertThat(raw.path("profile").intValue()).isZero();
    assertThat(raw.path("favorite_models").isArray()).isTrue();
  }

  @Test
  void loadFiltersNonStringMapEntriesAndUsesSafeProfileFallback(@TempDir Path directory)
      throws Exception {
    Files.writeString(directory.resolve("settings.json"), """
        {
          "model_id":"m", "profile":99, "favorite_models":["a"],
          "provider_keys":{"good":"key","bad":4},
          "provider_models":{"good":"model","bad":false},
          "always_allow_tools":["read"]
        }
        """);

    Settings loaded = new SettingsStore(directory).load();

    assertThat(loaded.modelId()).isEqualTo(new ModelId("m"));
    assertThat(loaded.profile()).isEqualTo(Profile.WRITE);
    assertThat(loaded.favoriteModels()).containsExactly(new ModelId("a"));
    assertThat(loaded.providerKeys()).containsExactlyEntriesOf(Map.of("good", "key"));
    assertThat(loaded.providerModels()).containsExactlyEntriesOf(Map.of("good", "model"));
    assertThat(loaded.alwaysAllowTools()).containsExactly("read");
  }

  @Test
  void settingsCollectionsAreImmutable() {
    var settings = new Settings(new ModelId("m"), Profile.ASK,
        List.of(new ModelId("a")), "", Map.of("p", "k"), Map.of(), "", List.of("read"));

    assertThat(settings.favoriteModels()).isUnmodifiable();
    assertThat(settings.providerKeys()).isUnmodifiable();
    assertThat(settings.alwaysAllowTools()).isUnmodifiable();
  }
}
