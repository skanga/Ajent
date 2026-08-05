package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.skanga.ajent.domain.CheckpointId;
import com.github.skanga.ajent.domain.Profile;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AppChromeTest {
  @Test
  void rendersAjentWelcomeIdentityStartersAndResponsiveHints() {
    List<AppChrome.Row> roomy = AppChrome.welcome(new AppChrome.Welcome(
        "claude-opus-4-6", Profile.WRITE, true, 100, 29));

    assertThat(text(roomy))
        .contains("a calm middleware between you and the model")
        .contains("N E W   H E R E ?   T R Y   O N E   O F   T H E S E")
        .contains("Explain what this project does and how it's structured")
        .contains("Find and fix the bug in <file> — it <symptom>")
        .contains("Add a <feature> and run the tests")
        .contains("Navigate:", "Actions:", "Session:")
        .contains("Ctrl+←/→ Threads")
        .contains("Ctrl+K Commands")
        .contains("Ctrl+C Quit")
        .doesNotContain("● Claude", "Write ▐", "▌ Write", "·  Write");
    int starter = text(roomy).indexOf("╭" + "─".repeat(60) + "╮");
    assertThat(starter).isGreaterThanOrEqualTo(0);
    assertThat(roomy.stream().map(AppChrome.Row::text).toList()).containsSubsequence(
        " ".repeat(19) + "╭" + "─".repeat(60) + "╮",
        " ".repeat(19) + "│   N E W   H E R E ?   T R Y   O N E   O F   T H E S E      │",
        " ".repeat(19) + "│" + " ".repeat(60) + "│",
        " ".repeat(19) + "│  • Explain what this project does and how it's structured  │",
        " ".repeat(19) + "╰" + "─".repeat(60) + "╯");
    assertThat(roomy.stream().filter(row -> row.tone() == AppChrome.Tone.BRAND)).isNotEmpty();

    List<AppChrome.Row> narrow = AppChrome.welcome(new AppChrome.Welcome(
        "openai/gpt-5.4", Profile.ASK, false, 36, 4));
    assertThat(narrow).extracting(row -> row.text().strip()).contains("AJENT");
    assertThat(text(narrow)).doesNotContain("type to begin");
    assertThat(text(narrow)).contains("Ctrl+K", "Ctrl+J", "Ctrl+C")
        .doesNotContain("● GPT", "·  Ask", "Commands", "NEW HERE");
  }

  @Test
  void rendersAStaticAjentPixelWordmark() {
    List<String> first = brandRows();
    List<String> second = brandRows();

    assertThat(first).anyMatch(line -> line.contains("█"));
    assertThat(second).isEqualTo(first);
    assertThat(first).hasSize(3).allMatch(line -> line.strip().length() <= 29);
  }

  @Test
  void rendersNativeSixRowIdleComposerAndThreeRowStatusPanel() {
    List<AppChrome.Row> composer = AppChrome.composer(new AppChrome.Composer(
        "", 0, Profile.WRITE, AppChrome.Phase.IDLE, 0, false, 78));

    assertThat(composer).hasSize(6);
    assertThat(composer.getFirst().text()).startsWith("╭").endsWith("╮")
        .hasSize(78);
    assertThat(composer.get(1).text())
        .as("cursor and placeholder should be visually separated")
        .startsWith("│ ❯ █  type a message…").endsWith(" │").hasSize(78);
    assertThat(composer.get(2).text()).isEqualTo("│" + " ".repeat(76) + "│");
    assertThat(composer.get(3).text()).startsWith("│   ─").endsWith(" │")
        .hasSize(78);
    assertThat(composer.get(4).text())
        .contains("Enter send", "Shift+Enter newline", "Ctrl+E expand", "Write")
        .doesNotContain("W R I T E")
        .hasSize(78);
    assertThat(composer.getFirst().tone()).isEqualTo(AppChrome.Tone.DIM);
    assertThat(composer.getLast().text()).startsWith("╰").endsWith("╯")
        .hasSize(78);

    List<AppChrome.Row> queued = AppChrome.composer(new AppChrome.Composer(
        "", 0, Profile.ASK, AppChrome.Phase.STREAMING, 1, false, 78));
    assertThat(queued.get(1).text())
        .contains("press ↑ to edit queued — type to queue another…");
    assertThat(queued.get(4).text()).contains("❚  1 queued", "Ask")
        .doesNotContain("A S K");

    assertThat(AppChrome.status(new AppChrome.Status(
        "", "OpenAI", AppChrome.Phase.STREAMING, "", 300,
        0, 128_000, 0, "", 78)).getFirst().text()).contains("⠸ Streaming");
    assertThat(AppChrome.statusPanel(new AppChrome.Status(
        "", "Ollama", AppChrome.ProviderAvailability.UNVERIFIED,
        AppChrome.Phase.IDLE, "", 0, 200_000, 0, "", 94)).get(1).text())
        .startsWith(" ● Ready")
        .contains("Connection not checked")
        .doesNotContain("▌", "Selected · Ollama")
        .doesNotContain("● Ollama")
        .doesNotContain("t/s", "CTX", "——", "—%");

    List<AppChrome.Row> identified = AppChrome.composer(new AppChrome.Composer(
        "", 0, Profile.WRITE, AppChrome.Phase.IDLE, 0, false,
        "Ollama", "qwen2.5-coder:7b", AppChrome.ProviderAvailability.UNVERIFIED, 120));
    assertThat(identified.get(4).text())
        .contains("Ollama · qwen2.5-coder:7b")
        .doesNotContain("(selected)", "Write");

    List<AppChrome.Row> unavailable = AppChrome.statusPanel(new AppChrome.Status(
        "", "Ollama", AppChrome.ProviderAvailability.UNAVAILABLE,
        AppChrome.Phase.IDLE, "", 0, 0, 0, "", 94));
    assertThat(unavailable.get(1).text())
        .contains("Provider unavailable · Ollama")
        .doesNotContain("● Ollama");

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
        .startsWith(" ● Ready")
        .contains("Connection not checked")
        .doesNotContain("▌", "Selected · Ollama")
        .doesNotContain("CTX", "░".repeat(10), "———%")
        .hasSize(78);
    assertThat(status.getLast().text()).isEqualTo("─".repeat(78));

    List<AppChrome.Row> titled = AppChrome.statusPanel(new AppChrome.Status(
        "say hello", "OpenAI", AppChrome.Phase.IDLE, "", 0, 128_000, 0, "", 78));
    assertThat(titled.get(1).text()).startsWith(" ▎ say hello  ·  ● Ready");

    List<AppChrome.Row> banner = AppChrome.statusPanel(new AppChrome.Status(
        "", "OpenAI", AppChrome.Phase.IDLE, "", 0, 128_000, 0,
        "rewound · files restored · backup at refs/ajent/rewind-backups", 78));
    assertThat(banner.get(1).text())
        .startsWith("▎ ▶  rewound · files restored · backup at refs/ajent/rewind-backups")
        .doesNotContain("…")
        .hasSize(78);
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
  void rendersTheNativeProviderPickerChromeAndProtectedTrailingColumn() {
    List<AppChrome.PickerRow> providers = List.of(
        new AppChrome.PickerRow("Anthropic  Claude — OAuth (Pro/Max) or API key",
            "✓ login", true, false),
        new AppChrome.PickerRow("OpenAI  GPT — api.openai.com",
            "⚠ OPENAI_API_KEY", false, false),
        new AppChrome.PickerRow("Custom host…  any OpenAI-compatible server (host:port)",
            "✎ edit", false, false));

    List<AppChrome.Row> rows = AppChrome.providerPicker(providers, 78);

    assertThat(rows).hasSize(10).allSatisfy(row -> assertThat(row.text()).hasSize(78));
    assertThat(rows.getFirst().text())
        .isEqualTo("  ╭" + "─".repeat(31) + " Providers " + "─".repeat(32) + "╮");
    assertThat(rows.get(2).text()).contains("▎ Anthropic", "✓ login", "┃  │");
    assertThat(rows.get(3).text()).contains("OpenAI", "⚠ OPENAI_API_KEY", "┃  │");
    assertThat(rows.get(6).text()).contains("✓ ready  ⚠ set the named key first");
    assertThat(rows.get(7).text()).contains("↑↓ move   Enter switch   Esc close");
    assertThat(rows.getLast().text()).isEqualTo("  ╰" + "─".repeat(74) + "╯");
  }

  @Test
  void rendersTheNativeSearchableCommandPalette() {
    List<AppChrome.PickerRow> commands = List.of(
        new AppChrome.PickerRow("New thread", "Start a fresh conversation", true, false),
        new AppChrome.PickerRow("Switch provider",
            "Choose the LLM backend (Anthropic, OpenAI, …)", false, false));

    List<AppChrome.Row> rows = AppChrome.commandPalette("", commands, 78, 14);

    assertThat(rows).hasSize(8).allSatisfy(row -> assertThat(row.text()).hasSize(78));
    assertThat(rows.getFirst().text()).contains(" Command Palette ");
    assertThat(rows.get(2).text()).contains("› type to filter…");
    assertThat(rows.get(3).text()).contains("─".repeat(70));
    assertThat(rows.get(4).text()).contains("▎ New thread", "Start a fresh conversation", "┃");
    assertThat(AppChrome.commandPalette("provider", commands.subList(1, 2), 78, 14).get(2)
        .text()).contains("› provider");
  }

  @Test
  void rendersTheNativeSavedThreadPicker() {
    List<AppChrome.Row> rows = AppChrome.threadPicker(List.of(
        new AppChrome.PickerRow("● test permission", "Jul 20 10:10", false, true),
        new AppChrome.PickerRow("  saved history", "Jul 19 09:00", true, false)),
        "2/2", 78, 14);

    assertThat(rows).hasSize(9).allSatisfy(row -> assertThat(row.text()).hasSize(78));
    assertThat(rows.getFirst().text()).contains(" Threads ");
    assertThat(rows.get(2).text())
        .contains("● test permission", "Jul 20 10:10", "┃")
        .doesNotContain("▎");
    assertThat(rows.get(3).text()).contains("▎   saved history", "Jul 19 09:00", "┃");
    assertThat(rows.get(5).text()).contains("  2/2");
    assertThat(rows.get(6).text())
        .contains("↑↓ move   PgUp/PgDn page   Enter open   N new   Esc close");
  }

  @Test
  void rendersNativeCheckpointPickerFactsFooterAndWarningFrame() {
    Instant now = Instant.parse("2026-07-23T12:00:00Z");
    var open = new CheckpointPicker.Open(List.of(
        checkpoint("one", 1, "first prompt", now.minusSeconds(20),
            CheckpointPicker.DiffState.READY, 1, 3, 2, false),
        checkpoint("two", 2, "clean prompt", now.minusSeconds(120),
            CheckpointPicker.DiffState.READY, 0, 0, 0, true),
        checkpoint("three", 3, "loading prompt", Instant.EPOCH,
            CheckpointPicker.DiffState.LOADING, 0, 0, 0, false),
        checkpoint("four", 4, "failed prompt", now.minusSeconds(90_000),
            CheckpointPicker.DiffState.FAILED, 0, 0, 0, false)), 2);

    List<AppChrome.Row> rows = AppChrome.checkpointPicker(open, now, 78, 4);
    String rendered = text(rows);

    assertThat(rows).hasSize(11).allSatisfy(row -> assertThat(row.text()).hasSize(78));
    assertThat(rows.getFirst().text()).contains(" Rewind to Checkpoint ");
    assertThat(rows.getFirst().tone()).isEqualTo(AppChrome.Tone.WARNING);
    assertThat(rows.getLast().tone()).isEqualTo(AppChrome.Tone.WARNING);
    assertThat(rendered)
        .contains("#1  first prompt", "just now · 1 file +3 −2")
        .contains("#2  clean prompt", "2m ago · no changes")
        .contains("#3  loading prompt", "…")
        .contains("#4  failed prompt", "1d ago")
        .contains("  Restores files and rewinds the transcript here.")
        .contains("↑↓ move   Enter rewind   Esc cancel");
    assertThat(rendered).doesNotContain("1970");

    var beforeEpoch = new CheckpointPicker.Open(List.of(
        checkpoint("old", 1, "old", Instant.ofEpochMilli(-1),
            CheckpointPicker.DiffState.FAILED, 0, 0, 0, false)), 0);
    assertThat(text(AppChrome.checkpointPicker(beforeEpoch, now, 52, 1)))
        .doesNotContain("ago");
    assertThatThrownBy(() -> AppChrome.checkpointPicker(open, now, 51, 4))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AppChrome.checkpointPicker(open, now, 78, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rendersTheNativeSearchableModelPicker() {
    List<AppChrome.Row> rows = AppChrome.modelPicker("", List.of(
        new AppChrome.PickerRow("parity-model", "", true, true)), "", 78, 14);

    assertThat(rows).hasSize(9).allSatisfy(row -> assertThat(row.text()).hasSize(78));
    assertThat(rows.getFirst().text()).contains(" Models ");
    assertThat(rows.get(2).text()).contains("🔍 type to filter models…");
    assertThat(rows.get(3).text()).contains("─".repeat(70));
    assertThat(rows.get(4).text()).contains("▎ parity-model", "┃");
    assertThat(rows.get(6).text())
        .contains("↑↓ move   type filter   Enter select   F favorite   Esc close");
    assertThat(AppChrome.modelPicker("opus", List.of(
        new AppChrome.PickerRow("Opus", "◇ high", true, true)),
        "←→ reasoning effort: high", 78, 14)).hasSize(10);
  }

  @Test
  void rendersNativeMentionAndSymbolPickers() {
    List<AppChrome.Row> mentions = AppChrome.mentionPicker("", List.of(
        new AppChrome.PickerRow("ParityFile.java", "src", true, false)), "", 78, 14);
    assertThat(mentions).hasSize(7).allSatisfy(row -> assertThat(row.text()).hasSize(78));
    assertThat(mentions.getFirst().text()).contains(" Mention File ");
    assertThat(mentions.get(2).text()).contains("@ type to filter files…");
    assertThat(mentions.get(4).text()).contains("▎ ParityFile.java", "src", "┃");

    List<AppChrome.Row> symbols = AppChrome.symbolPicker("Parity", List.of(
        new AppChrome.PickerRow("ParitySymbol  ParityFile.java:1", "src", true, false)),
        "15/20", 78, 14);
    assertThat(symbols).hasSize(8).allSatisfy(row -> assertThat(row.text()).hasSize(78));
    assertThat(symbols.getFirst().text()).contains(" Symbol ");
    assertThat(symbols.get(2).text()).contains("# Parity");
    assertThat(symbols.get(5).text()).contains("15/20");
  }

  @Test
  void rendersNativeCodeBlockPickerAndRunResult() {
    List<AppChrome.Row> picker = AppChrome.codeBlockPicker(List.of(
        new AppChrome.PickerRow("1  echo parity", "powershell · 1 line", true, false)),
        78, 14);
    assertThat(picker).hasSize(7).allSatisfy(row -> assertThat(row.text()).hasSize(78));
    assertThat(picker.getFirst().text()).contains(" Run Code Block ");
    assertThat(picker.get(2).text()).contains("▎ 1  echo parity", "powershell · 1 line", "┃");
    assertThat(picker.get(4).text())
        .contains("↑↓ move", "Enter/1-9 run", "e edit", "y copy", "Esc close");

    List<AppChrome.Row> result = AppChrome.codeBlockResult("echo parity",
        "exit 0 · 1 lines · 7 B", List.of("parity"), true, 78, 14);
    assertThat(result).hasSize(10).allSatisfy(row -> assertThat(row.text()).hasSize(78));
    assertThat(result.getFirst().text()).contains(" Run Result ");
    assertThat(result.get(2).text()).contains("$ echo parity");
    assertThat(result.get(3).text()).contains("exit 0 · 1 lines · 7 B");
    assertThat(result.get(5).text()).contains("parity").endsWith("┃  │");
    assertThat(result.get(7).text()).contains("a attach to composer", "y copy", "Esc discard");
  }

  @Test
  void rendersNativeDiffReviewPanel() {
    var file = new DiffReview.File("src/Main.java", 1, 1, List.of(
        new DiffReview.Hunk(4, 1, 4, 1, "-old\n+new", DiffReview.Status.PENDING)));

    List<AppChrome.Row> review = AppChrome.diffReview(file, 0, 2, 0, 78);

    assertThat(review).hasSize(15).allSatisfy(row -> assertThat(row.text()).hasSize(78));
    assertThat(review.getFirst().text()).contains(" Review Changes ");
    assertThat(review.get(2).text()).contains("src/Main.java", "+1", "-1", "file 1/2");
    assertThat(review.get(4).text()).contains("› ", "@@ -4,1 +4,1", "[ pending ]");
    assertThat(review.get(5).text()).contains(" src/Main.java ");
    assertThat(review.get(6).text()).contains("     -old");
    assertThat(review.get(7).text()).contains("   0 +new");
    assertThat(review.get(12).text()).contains(
        "↑↓ hunk", "←→ file", "Y accept", "N reject", "A all", "X none", "Esc close");
  }

  @Test
  void rendersAllDiffReviewStatusesAndValidatesCoordinates() {
    var file = new DiffReview.File("a/very/long/path/Main.java", 2, 1, List.of(
        new DiffReview.Hunk(2, 2, 2, 1, " context\n\n-removed",
            DiffReview.Status.ACCEPTED),
        new DiffReview.Hunk(8, 0, 7, 2, "+added\n plain",
            DiffReview.Status.REJECTED)));

    List<AppChrome.Row> review = AppChrome.diffReview(file, 0, 1, 1, 40);

    assertThat(review).extracting(AppChrome.Row::text)
        .anyMatch(line -> line.contains("[✓ accepted]"))
        .anyMatch(line -> line.contains("[✗ rejected]"))
        .anyMatch(line -> line.contains(" context"))
        .anyMatch(line -> line.contains("+added"));
    assertThatThrownBy(() -> AppChrome.diffReview(file, 0, 1, 0, 39))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AppChrome.diffReview(file, -1, 1, 0, 40))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AppChrome.diffReview(file, 1, 1, 0, 40))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AppChrome.diffReview(file, 0, 1, -1, 40))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AppChrome.diffReview(file, 0, 1, 2, 40))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rendersNativeLoginAndOAuthPanelsWithoutExposingSecrets() {
    List<AppChrome.Row> picking = AppChrome.login(new LoginModal.Picking(), 78);
    assertThat(picking).allSatisfy(row -> assertThat(row.text()).hasSize(78));
    assertThat(picking.getFirst().text()).contains(" Sign in to Ajent ");
    assertThat(picking).extracting(AppChrome.Row::text)
        .anyMatch(line -> line.contains("Authenticate with Claude"))
        .anyMatch(line -> line.contains("1) OAuth via claude.ai"))
        .anyMatch(line -> line.contains("2) Paste an Anthropic API key"))
        .anyMatch(line -> line.contains("1/2 choose") && line.contains("Esc close"));

    var oauth = new LoginModal.OAuthCode("verifier", "state",
        java.net.URI.create("https://claude.ai/oauth/authorize?code=true"),
        new Utf8Editor("secret", 6));
    List<AppChrome.Row> code = AppChrome.login(oauth, 78);
    assertThat(code).extracting(AppChrome.Row::text)
        .anyMatch(line -> line.contains("OAuth via claude.ai"))
        .anyMatch(line -> line.contains("https://claude.ai/oauth/authorize?code=true"))
        .anyMatch(line -> line.contains("› ******"))
        .anyMatch(line -> line.contains("c copy URL") && line.contains("o open browser"))
        .noneMatch(line -> line.contains("secret"));

    assertThat(AppChrome.login(new LoginModal.OAuthExchanging(), 78))
        .extracting(AppChrome.Row::text)
        .anyMatch(line -> line.contains("Exchanging authorization code…"))
        .anyMatch(line -> line.contains("platform.claude.com"));
    assertThat(AppChrome.login(new LoginModal.ApiKeyInput(
        new Utf8Editor("sk-ant-test", 11), "", ""), 78))
        .extracting(AppChrome.Row::text)
        .anyMatch(line -> line.contains("Anthropic API key"))
        .anyMatch(line -> line.contains("› ***********"))
        .noneMatch(line -> line.contains("sk-ant-test"));
    assertThat(AppChrome.login(new LoginModal.CustomHostInput(
        new Utf8Editor("localhost:8080", 14)), 78))
        .extracting(AppChrome.Row::text)
        .anyMatch(line -> line.contains("Custom OpenAI-compatible host"))
        .anyMatch(line -> line.contains("› localhost:8080"));
    assertThat(AppChrome.login(new LoginModal.Failed("exchange failed"), 78))
        .extracting(AppChrome.Row::text)
        .anyMatch(line -> line.contains("⚠ exchange failed"));
  }

  @Test
  void activeStatusUsesFixedVerbAndElapsedSlotsBeforeBreadcrumb() {
    AppChrome.Status status = new AppChrome.Status("test permission", "127.0.0.1:10501",
        AppChrome.Phase.AWAITING_PERMISSION, "write", 200, 0, 200_000, 0, "", 78);

    String row = AppChrome.statusPanel(status).get(1).text();
    assertThat(row).startsWith(" ⚠ approve w…  0.2s")
        .doesNotContain("test permission")
        .contains("Connection not checked")
        .doesNotContain("CTX");
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
        .isEqualTo("│ Changes (2 files)              Ctrl+R review  Shift+A accept  Shift+X reject │");
    assertThat(wide.get(2).text()).startsWith("│ M src/Main.java  +12 -3").endsWith(" │");
    assertThat(wide.get(3).text()).startsWith("│ A README.md  +8").endsWith(" │");
    assertThat(wide.getLast().text())
        .isEqualTo("╰──────────────────────────────────────────────────────────────────────────────╯");
    assertThat(wide.getFirst().tone()).isEqualTo(AppChrome.Tone.WARNING);

    List<AppChrome.Row> narrow = AppChrome.changes(changes, 30);
    assertThat(narrow).hasSize(5).allSatisfy(row -> assertThat(row.text()).hasSize(30));
    assertThat(narrow.get(1).text()).startsWith("│ Changes (2 files)")
        .doesNotContain("review", "Shift+A accept", "Shift+X reject");
    assertThat(narrow.get(2).text()).startsWith("│ M src/Main.java  +12 -3");
    assertThat(narrow.get(3).text()).startsWith("│ A README.md  +8");
    assertThat(AppChrome.changes(changes, 52).get(1).text())
        .contains("Shift+A accept", "Shift+X reject").doesNotContain("Ctrl+R");
    assertThat(AppChrome.changes(changes, 36).get(1).text())
        .contains("Shift+A accept").doesNotContain("Ctrl+R", "Shift+X reject");
    assertThat(AppChrome.changes(changes, 26).get(1).text())
        .doesNotContain("Ctrl+R", "Shift+A accept", "Shift+X reject");
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
    AppChrome.Row rewound =
        status("rewound · files restored · backup at refs/ajent/rewind-backups").getLast();
    assertThat(rewound.text())
        .startsWith("▶  rewound · files restored · backup at refs/ajent/rewind-backups")
        .hasSize(80);
    assertThat(rewound.tone()).isEqualTo(AppChrome.Tone.ACCENT);
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

  private static CheckpointPicker.Entry checkpoint(String id, int turn, String preview,
      Instant timestamp, CheckpointPicker.DiffState state, int files, int added, int removed,
      boolean clean) {
    return new CheckpointPicker.Entry(new CheckpointId(id), turn, preview, timestamp,
        state, files, added, removed, clean);
  }

  private static List<AppChrome.Row> status(String banner) {
    return AppChrome.status(new AppChrome.Status(
        "", "Anthropic", AppChrome.Phase.IDLE, "", 0, 200_000, 0, banner, 80));
  }

  private static String text(List<AppChrome.Row> rows) {
    return String.join("\n", rows.stream().map(AppChrome.Row::text).toList());
  }

  private static List<String> brandRows() {
    return AppChrome.welcome(new AppChrome.Welcome(
            "model", Profile.WRITE, false, 100, 20)).stream()
        .filter(row -> row.tone() == AppChrome.Tone.BRAND)
        .map(AppChrome.Row::text)
        .toList();
  }
}
