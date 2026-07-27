package com.github.skanga.ajent.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.skanga.ajent.tools.args.ArgReader;
import com.github.skanga.ajent.tools.runtime.ToolError;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolOutput;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** HTTPS-only fetch and multi-engine search tools with lexical SSRF protection. */
public final class WebTools {
  private static final int MAX_FETCH_CHARS = 64_000;
  private static final Pattern ANCHOR = Pattern.compile("(?is)<a\\s+[^>]*href\\s*=\\s*(['\"])(.*?)\\1[^>]*>(.*?)</a>");
  private static final Pattern TAGS = Pattern.compile("(?s)<[^>]+>");
  private static final Set<String> PRIVATE_NAMES = Set.of("localhost", "metadata",
      "metadata.google.internal", "0", "::1", "::");
  private final WebTransport transport;

  public WebTools(WebTransport transport) { this.transport = transport; }

  public ToolResult execute(String name, JsonNode arguments) {
    if (transport == null) return failure(ToolErrorKind.NOT_FOUND, "web transport unavailable");
    return switch (name) {
      case "web_fetch" -> fetch(arguments);
      case "web_search" -> search(arguments);
      default -> failure(ToolErrorKind.UNKNOWN, "unknown tool: " + name);
    };
  }

  private ToolResult fetch(JsonNode arguments) {
    var args = new ArgReader(arguments);
    String url = args.requiredString("url").orElse("");
    if (url.isEmpty()) return failure(ToolErrorKind.INVALID_ARGS, "url required");
    if (!url.startsWith("https://")) return failure(ToolErrorKind.INVALID_ARGS,
        "url must start with https:// (web_fetch is TLS-only)");
    URI uri;
    try { uri = URI.create(url); } catch (IllegalArgumentException exception) {
      return failure(ToolErrorKind.INVALID_ARGS, "invalid URL: " + exception.getMessage());
    }
    if (uri.getHost() == null || uri.getHost().isEmpty())
      return failure(ToolErrorKind.INVALID_ARGS, "invalid URL: empty host");
    if (isBlockedHost(uri.getHost())) return failure(ToolErrorKind.NETWORK,
        "SSRF protection: loopback, private, link-local, and metadata hosts are blocked");
    String method = args.string("method", "GET").toUpperCase(Locale.ROOT);
    if (!Set.of("GET", "HEAD", "POST").contains(method)) method = "GET";
    var headers = new LinkedHashMap<String, String>();
    JsonNode rawHeaders = args.raw("headers");
    if (rawHeaders != null && rawHeaders.isObject()) rawHeaders.properties().forEach(entry -> {
      String key = entry.getKey().toLowerCase(Locale.ROOT);
      if (!key.equals("x-no-jina") && !key.equals("x-ajent-no-jina"))
        headers.put(key, entry.getValue().isTextual() ? entry.getValue().textValue()
            : entry.getValue().toString());
    });
    WebTransport.Response response = transport.send(new WebTransport.Request(method, url, headers, ""));
    if (response.status() == 0) return failure(ToolErrorKind.NETWORK,
        response.error().isEmpty() ? "transport failure" : response.error());
    if (response.status() >= 400) return failure(ToolErrorKind.NETWORK,
        "web_fetch: HTTP " + response.status());
    String body = response.body() == null ? "" : response.body();
    if (response.header("content-type").toLowerCase(Locale.ROOT).contains("html")) body = htmlToText(body);
    if (body.length() > MAX_FETCH_CHARS) body = body.substring(0, MAX_FETCH_CHARS)
        + "\n[content truncated at 64000 characters]";
    String description = args.string("display_description", "");
    return success(description.isEmpty() ? body : description + '\n' + body);
  }

  private ToolResult search(JsonNode arguments) {
    var args = new ArgReader(arguments);
    String query = args.requiredString("query").orElse("");
    if (query.isEmpty()) return failure(ToolErrorKind.INVALID_ARGS, "query required");
    int count = Math.clamp(args.integer("count", 10), 1, 20);
    String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
    List<Engine> engines = List.of(
        new Engine("Brave", "GET", "https://search.brave.com/search?q=" + encoded + "&source=web", ""),
        new Engine("DuckDuckGo", "POST", "https://html.duckduckgo.com/html/", "q=" + encoded + "&kl=us-en"),
        new Engine("Startpage", "GET", "https://www.startpage.com/sp/search?query=" + encoded, ""));
    var diagnostics = new ArrayList<String>();
    for (Engine engine : engines) {
      var response = transport.send(new WebTransport.Request(engine.method(), engine.url(),
          Map.of("user-agent", "Mozilla/5.0", "accept", "text/html"), engine.body()));
      if (response.status() == 0) { diagnostics.add(engine.name() + ": transport: " + response.error()); continue; }
      if (response.status() >= 400) { diagnostics.add(engine.name() + ": HTTP " + response.status()); continue; }
      List<SearchHit> hits = switch (engine.name()) {
        case "Brave" -> parseBrave(response.body(), count);
        case "DuckDuckGo" -> parseDuckDuckGo(response.body(), count);
        default -> parseStartpage(response.body(), count);
      };
      if (hits.isEmpty()) { diagnostics.add(engine.name() + ": parser found 0 results"); continue; }
      var unique = new LinkedHashMap<String, SearchHit>();
      for (SearchHit hit : hits) unique.putIfAbsent(canonical(hit.url()), hit);
      var output = new StringBuilder("[via ").append(engine.name()).append("]\n\n");
      int index = 1;
      for (SearchHit hit : unique.values()) {
        output.append(index++).append(". ").append(hit.title()).append('\n')
            .append("   ").append(hit.url()).append('\n');
        if (!hit.snippet().isEmpty()) output.append("   ").append(hit.snippet()).append('\n');
        output.append('\n');
      }
      String description = args.string("display_description", "");
      return success(description.isEmpty() ? output.toString() : description + '\n' + output);
    }
    return success("search returned no results for: " + query + "\ntried " + diagnostics.size()
        + " engines:\n  - " + String.join("\n  - ", diagnostics));
  }

  static boolean isBlockedHost(String host) {
    String value = host.toLowerCase(Locale.ROOT);
    if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
    if (PRIVATE_NAMES.contains(value) || value.endsWith(".localhost")) return true;
    if (value.startsWith("fc") || value.startsWith("fd") || value.startsWith("fe8")
        || value.startsWith("fe9") || value.startsWith("fea") || value.startsWith("feb")) return true;
    String[] parts = value.split("\\.");
    if (parts.length != 4) return false;
    int[] octets = new int[4];
    try {
      for (int index = 0; index < 4; index++) {
        octets[index] = Integer.parseInt(parts[index]);
        if (octets[index] < 0 || octets[index] > 255) return false;
      }
    } catch (NumberFormatException exception) { return false; }
    int first = octets[0];
    int second = octets[1];
    return first == 0 || first == 10 || first == 127 || first >= 224
        || first == 169 && second == 254 || first == 172 && second >= 16 && second <= 31
        || first == 192 && second == 168 || first == 100 && second >= 64 && second <= 127;
  }

  static String htmlToText(String html) {
    String text = html;
    for (String region : List.of("script", "style", "nav", "header", "footer", "form", "svg"))
      text = text.replaceAll("(?is)<" + region + "(?:\\s[^>]*)?>.*?</" + region + ">", "");
    var matcher = ANCHOR.matcher(text);
    var linked = new StringBuffer();
    while (matcher.find()) {
      String label = clean(matcher.group(3));
      matcher.appendReplacement(linked, java.util.regex.Matcher.quoteReplacement(
          label.isEmpty() ? "" : "[" + label + "](" + matcher.group(2) + ")"));
    }
    matcher.appendTail(linked);
    text = linked.toString().replaceAll("(?is)</?(?:p|div|main|article|section|h[1-6]|li|br|tr)[^>]*>", "\n");
    text = decode(TAGS.matcher(text).replaceAll("")).replace("\r", "").replaceAll("[ \\t]+", " ");
    return text.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n[ \\t]+", "\n")
        .replaceAll("\\n{3,}", "\n\n").strip();
  }

  private static List<SearchHit> parseBrave(String html, int count) {
    return parseBlocks(html, "class=\"snippet ", count, Pattern.compile(
        "(?is)<a\\s+href=['\"](https?://[^'\"]+)['\"][^>]*>.*?class=\"title[^\"]*\"[^>]*>(.*?)<.*?class=\"generic-snippet[^\"]*\"[^>]*>(.*?)</div"));
  }
  private static List<SearchHit> parseDuckDuckGo(String html, int count) {
    var hits = new ArrayList<SearchHit>();
    var matcher = Pattern.compile("(?is)<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?class=\"result__snippet\"[^>]*>(.*?)</a>").matcher(html);
    while (matcher.find() && hits.size() < count)
      hits.add(new SearchHit(clean(matcher.group(2)), matcher.group(1), clean(matcher.group(3))));
    return hits;
  }
  private static List<SearchHit> parseStartpage(String html, int count) {
    var hits = new ArrayList<SearchHit>();
    var matcher = Pattern.compile("(?is)<a[^>]*href=\"(https?://[^\"]+)\"[^>]*class=\"(?:result-title|w-gl__result-title)[^\"]*\"[^>]*>(.*?)</a>").matcher(html);
    while (matcher.find() && hits.size() < count)
      hits.add(new SearchHit(clean(matcher.group(2)), matcher.group(1), ""));
    return hits;
  }
  private static List<SearchHit> parseBlocks(String html, String marker, int count, Pattern pattern) {
    var hits = new ArrayList<SearchHit>();
    var matcher = pattern.matcher(html);
    while (matcher.find() && hits.size() < count) {
      String url = matcher.group(1);
      if (!url.contains("brave.com")) hits.add(new SearchHit(clean(matcher.group(2)), url,
          clean(matcher.group(3))));
    }
    return hits;
  }
  private static String clean(String value) {
    return decode(TAGS.matcher(value).replaceAll(""))
        .replaceAll("\\s+", " ").strip();
  }
  private static String decode(String value) {
    return value.replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&nbsp;", " ");
  }
  private static String canonical(String url) {
    String value = url.toLowerCase(Locale.ROOT).replaceFirst("^https?://(?:www\\.|m\\.|amp\\.)?", "");
    value = value.replaceFirst("[?#].*$", "");
    return value.replaceFirst("/+$", "");
  }

  private record Engine(String name, String method, String url, String body) {}
  private record SearchHit(String title, String url, String snippet) {}
  private static ToolResult success(String text) { return new ToolResult.Success(new ToolOutput(text)); }
  private static ToolResult failure(ToolErrorKind kind, String detail) {
    return new ToolResult.Failure(new ToolError(kind, detail));
  }
}
