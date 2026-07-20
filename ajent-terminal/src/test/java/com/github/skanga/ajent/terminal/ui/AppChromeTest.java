package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.skanga.ajent.domain.Profile;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AppChromeTest {
  @Test
  void rendersNativeWelcomeIdentityStartersAndResponsiveHints() {
    List<AppChrome.Row> roomy = AppChrome.welcome(new AppChrome.Welcome(
        "claude-opus-4-6", Profile.WRITE, true, 100, 29));

    assertThat(text(roomy))
        .contains("a calm middleware between you and the model")
        .contains("● Opus")
        .contains("▌ Write ▐")
        .contains("NEW HERE? TRY ONE OF THESE")
        .contains("Explain what this project does and how it's structured")
        .contains("Find and fix the bug in <file> — it <symptom>")
        .contains("Add a <feature> and run the tests")
        .contains("^K palette")
        .contains("^C quit");
    assertThat(roomy.stream().filter(row -> row.tone() == AppChrome.Tone.BRAND)).isNotEmpty();

    List<AppChrome.Row> narrow = AppChrome.welcome(new AppChrome.Welcome(
        "openai/gpt-5.4", Profile.ASK, false, 36, 4));
    assertThat(narrow).extracting(row -> row.text().strip()).contains("» A G E N T T Y");
    assertThat(text(narrow)).contains("● GPT", "▌ Ask ▐", "^K", "^J", "^C")
        .doesNotContain("palette", "NEW HERE");
    assertThat(narrow).hasSize(6);
  }

  @Test
  void rendersNativeSixRowIdleComposerAndThreeRowStatusPanel() {
    List<AppChrome.Row> composer = AppChrome.composer(new AppChrome.Composer(
        "", 0, Profile.WRITE, AppChrome.Phase.IDLE, 0, false, 78));

    assertThat(composer).hasSize(6);
    assertThat(composer.getFirst().text()).startsWith("╭").endsWith("╮")
        .hasSize(78);
    assertThat(composer.get(1).text()).startsWith("│ ❯ █type a message…")
        .endsWith(" │").hasSize(78);
    assertThat(composer.get(2).text()).isEqualTo("│" + " ".repeat(76) + "│");
    assertThat(composer.get(3).text()).startsWith("│   ─").endsWith(" │")
        .hasSize(78);
    assertThat(composer.get(4).text())
        .contains("↵ send", "⇧↵ / ⌥↵ newline", "^E expand", "▎ W R I T E")
        .hasSize(78);
    assertThat(composer.getLast().text()).startsWith("╰").endsWith("╯")
        .hasSize(78);

    List<AppChrome.Row> multiline = AppChrome.composer(new AppChrome.Composer(
        "first\nsecond", 12, Profile.WRITE, AppChrome.Phase.IDLE, 0, true, 78));
    assertThat(multiline).hasSize(6);
    assertThat(multiline.get(1).text()).startsWith("│ ❯ first");
    assertThat(multiline.get(2).text()).startsWith("│ ┊ second█");
    assertThat(multiline.getLast().text()).endsWith(" 2 lines ╯").hasSize(78);

    String wrappingText = "0123456789".repeat(7) + "中é";
    List<AppChrome.Row> wrapped = AppChrome.composer(new AppChrome.Composer(
        wrappingText, wrappingText.length(), Profile.WRITE,
        AppChrome.Phase.IDLE, 0, false, 78));
    assertThat(wrapped).hasSize(7);
    assertThat(wrapped.get(3).text()).isEqualTo("│" + " ".repeat(76) + "│");

    List<AppChrome.Row> status = AppChrome.statusPanel(new AppChrome.Status(
        "", "Ollama", AppChrome.Phase.IDLE, "", 0, 32_768, 0, "", 78));
    assertThat(status).hasSize(3);
    assertThat(status.getFirst().text()).isEqualTo("─".repeat(78));
    assertThat(status.get(1).text())
        .startsWith(" ▌ ● Ready")
        .contains("● Ollama · CTX", "░".repeat(10), "———%")
        .hasSize(78);
    assertThat(status.getLast().text()).isEqualTo("─".repeat(78));

    List<AppChrome.Row> titled = AppChrome.statusPanel(new AppChrome.Status(
        "say hello", "OpenAI", AppChrome.Phase.IDLE, "", 0, 128_000, 0, "", 78));
    assertThat(titled.get(1).text()).startsWith(" ▎ say hello   ·   ▌ ● Ready");
  }

  @Test
  void rendersPhaseBreadcrumbProviderAndContextGaugeWithWidthDegradation() {
    var streaming = new AppChrome.Status(
        "Implement native chrome", "Anthropic", AppChrome.Phase.STREAMING, "",
        37_500, 200_000, 0, "", 100);
    List<AppChrome.Row> wide = AppChrome.status(streaming);

    assertThat(wide).hasSize(1);
    assertThat(wide.getFirst().text())
        .contains("Streaming")
        .contains("Implement native chrome")
        .contains("Anthropic")
        .contains("ctx ██░░░░░░░░ 19%");

    var approval = new AppChrome.Status(
        "", "OpenAI", AppChrome.Phase.AWAITING_PERMISSION, "bash",
        1_000, 0, 2, "awaiting approval", 34);
    List<AppChrome.Row> narrow = AppChrome.status(approval);
    assertThat(narrow.getFirst().text()).isEqualTo("⚠ approve b…  ·  OpenAI");
    assertThat(narrow.getLast().text()).startsWith("⚠  awaiting approval").hasSize(34);
    assertThat(narrow.getLast().tone()).isEqualTo(AppChrome.Tone.WARNING);
  }

  @Test
  void rendersTheNativePermissionCard() {
    List<AppChrome.Row> rows = AppChrome.permission(new AppChrome.Permission(
        "write", "parity.txt", true, 76));

    assertThat(rows).hasSize(6).allSatisfy(row -> assertThat(row.text()).hasSize(76));
    assertThat(rows.getFirst().text()).startsWith("╭ ⚠ Permission Required ").endsWith("╮");
    assertThat(rows.get(1).text()).startsWith("│ write wants to edit:");
    assertThat(rows.get(2).text()).startsWith("│ parity.txt");
    assertThat(rows.get(3).text()).isEqualTo("│" + " ".repeat(74) + "│");
    assertThat(rows.get(4).text()).contains("[y] allow  [n] deny  [a] always");
    assertThat(rows.getLast().text()).isEqualTo("╰" + "─".repeat(74) + "╯");
  }

  @Test
  void activeStatusUsesFixedVerbAndElapsedSlotsBeforeBreadcrumb() {
    AppChrome.Status status = new AppChrome.Status("test permission", "127.0.0.1:10501",
        AppChrome.Phase.AWAITING_PERMISSION, "write", 200, 0, 200_000, 0, "", 78);

    String row = AppChrome.statusPanel(status).get(1).text();
    assertThat(row).startsWith(" ▌ ⚠ approve w…  0.2s")
        .doesNotContain("test permission")
        .contains("● 127.0.0.1:10501 · CTX");
  }

  @Test
  void rendersPendingChangesAndShedsHintsBeforeFileFacts() {
    List<AppChrome.Change> changes = List.of(
        new AppChrome.Change("src/Main.java", false, 12, 3),
        new AppChrome.Change("README.md", true, 8, 0));

    List<AppChrome.Row> wide = AppChrome.changes(changes, 80);
    assertThat(wide).hasSize(5).allSatisfy(row -> assertThat(row.text()).hasSize(80));
    assertThat(wide.getFirst().text())
        .isEqualTo("╭──────────────────────────────────────────────────────────────────────────────╮");
    assertThat(wide.get(1).text().strip())
        .isEqualTo("│ Changes (2 files)                          Ctrl+R review  A accept  X reject │");
    assertThat(wide.get(2).text()).startsWith("│ M src/Main.java  +12 -3").endsWith(" │");
    assertThat(wide.get(3).text()).startsWith("│ A README.md  +8").endsWith(" │");
    assertThat(wide.getLast().text())
        .isEqualTo("╰──────────────────────────────────────────────────────────────────────────────╯");
    assertThat(wide.getFirst().tone()).isEqualTo(AppChrome.Tone.WARNING);

    List<AppChrome.Row> narrow = AppChrome.changes(changes, 30);
    assertThat(narrow).hasSize(5).allSatisfy(row -> assertThat(row.text()).hasSize(30));
    assertThat(narrow.get(1).text()).startsWith("│ Changes (2 files)")
        .doesNotContain("review", "A accept", "X reject");
    assertThat(narrow.get(2).text()).startsWith("│ M src/Main.java  +12 -3");
    assertThat(narrow.get(3).text()).startsWith("│ A README.md  +8");
    assertThat(AppChrome.changes(changes, 46).get(1).text())
        .contains("A accept", "X reject").doesNotContain("Ctrl+R");
    assertThat(AppChrome.changes(changes, 34).get(1).text())
        .contains("A accept").doesNotContain("Ctrl+R", "X reject");
    assertThat(AppChrome.changes(changes, 26).get(1).text())
        .doesNotContain("Ctrl+R", "A accept", "X reject");
    assertThat(AppChrome.changes(List.of(), 80)).isEmpty();
  }

  @Test
  void coversEveryPhaseBannerSeverityAndBoundary() {
    assertThat(activity(AppChrome.Phase.IDLE, "", 3)).contains("+3 queued");
    assertThat(activity(AppChrome.Phase.IDLE, "", 0)).contains("Ready");
    assertThat(activity(AppChrome.Phase.COMPACTING, "", 0)).contains("compacting");
    assertThat(activity(AppChrome.Phase.RETRYING, "", 0)).contains("retrying");
    assertThat(activity(AppChrome.Phase.STALLED, "", 0)).contains("stalled");
    assertThat(activity(AppChrome.Phase.EXECUTING_TOOL, "bash", 0)).contains("bash");
    assertThat(activity(AppChrome.Phase.EXECUTING_TOOL, "", 0)).contains("running");
    assertThat(activity(AppChrome.Phase.AUTHENTICATING, "", 0)).contains("auth…");
    assertThat(activity(AppChrome.Phase.LOADING, "", 0)).contains("loading…");

    assertThat(status("error: failed").getLast().tone()).isEqualTo(AppChrome.Tone.DANGER);
    assertThat(status("rate limit — retrying").getLast().tone())
        .isEqualTo(AppChrome.Tone.WARNING);
    assertThat(status("context compacted").getLast().tone()).isEqualTo(AppChrome.Tone.ACCENT);
    assertThat(status("ready")).hasSize(1);
    assertThat(AppChrome.status(new AppChrome.Status("", "OpenAI", AppChrome.Phase.IDLE, "",
        200, 100, 0, "", 80)).getFirst().text()).contains("100%");
    assertThat(AppChrome.status(new AppChrome.Status("a very long breadcrumb title", "OpenAI",
        AppChrome.Phase.IDLE, "", 200, 100, 0, "", 24)).getFirst().text())
        .hasSizeLessThanOrEqualTo(24);

    assertThatThrownBy(() -> new AppChrome.Welcome("model", Profile.ASK, false, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AppChrome.Status("", "", AppChrome.Phase.IDLE, "",
        -1, 0, 0, "", 80)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AppChrome.Change("file", false, -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AppChrome.Composer("x", 2, Profile.WRITE,
        AppChrome.Phase.IDLE, 0, false, 80)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AppChrome.changes(
        List.of(new AppChrome.Change("file", true, 0, 0)), 3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String activity(AppChrome.Phase phase, String detail, int queued) {
    return AppChrome.status(new AppChrome.Status(
        "", "Anthropic", phase, detail, 0, 200_000, queued, "", 80)).getFirst().text();
  }

  private static List<AppChrome.Row> status(String banner) {
    return AppChrome.status(new AppChrome.Status(
        "", "Anthropic", AppChrome.Phase.IDLE, "", 0, 200_000, 0, banner, 80));
  }

  private static String text(List<AppChrome.Row> rows) {
    return String.join("\n", rows.stream().map(AppChrome.Row::text).toList());
  }
}
