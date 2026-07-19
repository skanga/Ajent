package com.github.skanga.ajent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.domain.ModelCapabilities;
import com.github.skanga.ajent.provider.ProviderHttpTransport;
import com.github.skanga.ajent.provider.EnvironmentHttpClient;
import com.github.skanga.ajent.provider.anthropic.AnthropicRequest;
import com.github.skanga.ajent.provider.auth.Credential;
import com.github.skanga.ajent.provider.auth.CredentialResolver;
import com.github.skanga.ajent.provider.auth.CredentialStore;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import com.github.skanga.ajent.terminal.render.MarkdownTerminalRenderer;
import com.github.skanga.ajent.terminal.render.StreamingMarkdown;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Capture/replay port of anthropic_md_stream.cpp. Run this class from benchmarks.jar's classpath. */
public final class AnthropicMarkdownStream {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String DEFAULT_MODEL = "claude-sonnet-4-5";
  private static final String DEFAULT_PROMPT =
      "Write a richly-formatted markdown response (about 800-1200 words) that exercises every "
          + "shape: an H1 title, two H2 subsections, a bulleted list, a numbered list, an inline "
          + "code span, a fenced code block (~15 lines of C++), a blockquote, a table with 3 "
          + "columns and 5 rows, and a closing paragraph with **bold** and *italic*. Topic: "
          + "'a brief tour of CommonMark for terminal renderers'. Plain prose, no preamble.";
  private static final String SYSTEM_PROMPT =
      "You are Ajent, a terminal coding assistant. Return the requested Markdown directly.";

  private AnthropicMarkdownStream() {}

  record Delta(long tMs, String text) {
    Delta {
      if (tMs < 0) throw new IllegalArgumentException("negative delta timestamp");
      if (text == null) throw new NullPointerException("text");
    }
  }

  record ReplayOptions(boolean realtime, int width, boolean revealEffects,
                       double floorCps, double drainSeconds, double feedCps, boolean trace) {
    ReplayOptions {
      if (width <= 0 || floorCps <= 0 || drainSeconds <= 0 || feedCps < 0) {
        throw new IllegalArgumentException("invalid replay pacing or width");
      }
    }
  }

  @FunctionalInterface
  interface Sleeper { void sleep(Duration duration) throws InterruptedException; }

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(String[] args, PrintStream output, PrintStream error) {
    try {
      if (args.length < 2) {
        usage(error);
        return 1;
      }
      return switch (args[0]) {
        case "capture" -> capture(args, output, error);
        case "replay" -> replay(args, output, error);
        default -> {
          usage(error);
          yield 1;
        }
      };
    } catch (IllegalArgumentException | IOException exception) {
      error.println("anthropic_md_stream: " + exception.getMessage());
      return 4;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      error.println("anthropic_md_stream: interrupted");
      return 5;
    }
  }

  private static int capture(String[] args, PrintStream output, PrintStream error)
      throws IOException {
    String prompt = DEFAULT_PROMPT;
    String model = DEFAULT_MODEL;
    for (int index = 2; index < args.length; index++) {
      switch (args[index]) {
        case "--prompt" -> prompt = requiredValue(args, ++index, "--prompt");
        case "--model" -> model = requiredValue(args, ++index, "--model");
        default -> throw new IllegalArgumentException("unknown capture option: " + args[index]);
      }
    }
    ProviderAuth auth = auth();
    if (auth.isEmpty()) {
      error.println("anthropic_md_stream: no credentials — set ANTHROPIC_API_KEY or run `ajent login`");
      return 3;
    }
    Path path = Path.of(args[1]);
    error.println("→ capturing from api.anthropic.com (model=" + model + ") ...");
    var request = new AnthropicRequest(model, SYSTEM_PROMPT,
        List.of(BenchmarkUiSupport.user(prompt)), List.of(),
        ModelCapabilities.maxOutputTokensFor(model), auth, 0, "");
    var firstNanos = new AtomicLong(-1);
    var count = new AtomicLong();
    var bytes = new AtomicLong();
    var streamError = new AtomicReference<String>();
    try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
      new ProviderHttpTransport(EnvironmentHttpClient.createProvider(System.getenv()))
          .streamAnthropic(request, event -> {
        if (event instanceof StreamEvent.TextDelta delta) {
          long now = System.nanoTime();
          firstNanos.compareAndSet(-1, now);
          ObjectNode line = JSON.createObjectNode();
          line.put("t_ms", (now - firstNanos.get()) / 1_000_000);
          line.put("delta", delta.text());
          try {
            writer.write(JSON.writeValueAsString(line));
            writer.newLine();
            writer.flush();
          } catch (IOException exception) {
            throw new UncheckedIOException(exception);
          }
          count.incrementAndGet();
          bytes.addAndGet(delta.text().getBytes(StandardCharsets.UTF_8).length);
          error.print('.');
        } else if (event instanceof StreamEvent.Error failed) {
          streamError.set(failed.message());
        }
      }, () -> false);
    } catch (UncheckedIOException exception) {
      throw exception.getCause();
    }
    error.println();
    if (streamError.get() != null) {
      error.println("stream error: " + streamError.get());
      return 5;
    }
    error.println("captured " + count + " deltas, " + bytes + " bytes total → " + path);
    return 0;
  }

  private static int replay(String[] args, PrintStream output, PrintStream error)
      throws IOException, InterruptedException {
    boolean realtime = false;
    boolean effects = true;
    boolean trace = false;
    int width = 100;
    double cps = 120;
    double drain = 0.8;
    double feedCps = 0;
    for (int index = 2; index < args.length; index++) {
      switch (args[index]) {
        case "--realtime" -> realtime = true;
        case "--no-fx" -> effects = false;
        case "--trace" -> trace = true;
        case "--width" -> width = Integer.parseInt(requiredValue(args, ++index, "--width"));
        case "--cps" -> cps = Double.parseDouble(requiredValue(args, ++index, "--cps"));
        case "--drain" -> drain = Double.parseDouble(requiredValue(args, ++index, "--drain"));
        case "--feed-cps" ->
            feedCps = Double.parseDouble(requiredValue(args, ++index, "--feed-cps"));
        default -> throw new IllegalArgumentException("unknown replay option: " + args[index]);
      }
    }
    ReplayOptions options = new ReplayOptions(realtime, width, effects, cps, drain, feedCps, trace);
    List<Delta> deltas = loadFixture(Path.of(args[1]));
    error.printf("→ replay %d deltas (realtime=%s, width=%d, fx=%s, cps=%.1f, "
            + "drain=%.1fs, feed_cps=%s, trace=%s)%n", deltas.size(), realtime, width,
        effects, cps, drain, feedCps > 0 ? Double.toString(feedCps) : "∞", trace);
    replay(deltas, options, output, error, duration -> Thread.sleep(duration));
    error.println("→ replay done (" + deltas.size() + " deltas)");
    return 0;
  }

  static List<Delta> loadFixture(Path path) throws IOException {
    if (!Files.isRegularFile(path)) throw new IOException("cannot open fixture: " + path);
    var deltas = new ArrayList<Delta>();
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      for (String line; (line = reader.readLine()) != null;) {
        if (line.isBlank()) continue;
        try {
          var value = JSON.readTree(line);
          deltas.add(new Delta(value.path("t_ms").longValue(), value.path("delta").textValue()));
        } catch (RuntimeException exception) {
          throw new IOException("bad fixture line: " + exception.getMessage(), exception);
        }
      }
    }
    if (deltas.isEmpty()) throw new IOException("fixture has no deltas");
    return List.copyOf(deltas);
  }

  static void replay(List<Delta> deltas, ReplayOptions options, PrintStream output,
      PrintStream error, Sleeper sleeper) throws InterruptedException {
    var markdown = new StreamingMarkdown();
    markdown.setLive(true);
    markdown.setRevealEffects(options.revealEffects());
    markdown.setRevealPacing(options.floorCps(), options.drainSeconds());
    long now = 0;
    int frame = 0;
    int lastVisible = 0;
    int lastRows = 0;
    if (options.feedCps() > 0) {
      String body = deltas.stream().map(Delta::text).reduce("", String::concat);
      long step = Math.max(1, (long) (1_000_000_000d / options.feedCps()));
      for (int offset = 0; offset < body.length();) {
        int codePoint = body.codePointAt(offset);
        markdown.append(new String(Character.toChars(codePoint)));
        offset += Character.charCount(codePoint);
        now += step;
        sleeper.sleep(Duration.ofNanos(step));
        int[] observed = render(markdown, options, output, error, now, frame++, lastVisible, lastRows);
        lastVisible = observed[0];
        lastRows = observed[1];
      }
    } else {
      long previous = 0;
      for (Delta delta : deltas) {
        long delayMillis = options.realtime() ? Math.max(0, delta.tMs() - previous) : 16;
        sleeper.sleep(Duration.ofMillis(delayMillis));
        now += Duration.ofMillis(delayMillis).toNanos();
        markdown.append(delta.text());
        int[] observed = render(markdown, options, output, error, now, frame++, lastVisible, lastRows);
        lastVisible = observed[0];
        lastRows = observed[1];
        previous = delta.tMs();
      }
    }
    markdown.finish();
    for (int drainFrame = 0; drainFrame < 240 && !markdown.settled(); drainFrame++) {
      now += 16_000_000;
      sleeper.sleep(Duration.ofMillis(16));
      int[] observed = render(markdown, options, output, error, now, frame++, lastVisible, lastRows);
      lastVisible = observed[0];
      lastRows = observed[1];
    }
  }

  private static int[] render(StreamingMarkdown markdown, ReplayOptions options,
      PrintStream output, PrintStream error, long now, int frame, int lastVisible, int lastRows) {
    List<MarkdownTerminalRenderer.Line> lines = markdown.render(options.width(), now);
    int visible = lines.stream().map(MarkdownTerminalRenderer.Line::text)
        .flatMapToInt(String::chars).map(value -> Character.isWhitespace(value) ? 0 : 1).sum();
    if (options.trace()) {
      error.printf("[%6d ms] frame=%4d rows=%4d Δrows=%+3d visible=%5d Δ=%+4d%n",
          now / 1_000_000, frame, lines.size(), lines.size() - lastRows,
          visible, visible - lastVisible);
    } else {
      output.print("\u001b[H\u001b[2J");
      lines.forEach(line -> output.println(line.text()));
      output.flush();
    }
    return new int[] {visible, lines.size()};
  }

  private static ProviderAuth auth() {
    Credential credential = CredentialResolver.resolve("", System.getenv(),
        CredentialStore.systemDefault().load(), System.currentTimeMillis()).credential();
    return switch (credential) {
      case Credential.None ignored -> new ProviderAuth.Empty();
      case Credential.ApiKey key -> new ProviderAuth.ApiKey(key.key());
      case Credential.OAuth oauth -> new ProviderAuth.Bearer(oauth.accessToken());
    };
  }

  private static String requiredValue(String[] args, int index, String option) {
    if (index >= args.length) throw new IllegalArgumentException(option + " requires a value");
    return args[index];
  }

  private static void usage(PrintStream error) {
    error.println("""
        anthropic_md_stream — capture/replay real Anthropic SSE for Markdown tests

          capture <out.jsonl> [--prompt "..."] [--model claude-...]
          replay  <in.jsonl>  [--realtime | --feed-cps N]
                              [--width N] [--no-fx] [--trace]
                              [--cps N] [--drain SECS]
        """);
  }
}
