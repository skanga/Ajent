package com.github.skanga.ajent.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebToolsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void portsPinnedFetchExtractionSsrfAndBraveSearch() {
    var transport = new FakeTransport();
    transport.rule("example.com", html("<html><body><main><h1>Hello World</h1><p>This is prose with "
        + "<a href=\"https://dest.test/x\">link text</a> in it.</p>"
        + "<nav>Home About Pricing Contact</nav></main></body></html>"));
    transport.rule("search.brave.com", html("<div class=\"snippet svelte-x\">"
        + "<a href=\"https://result.test/page\" class=\"l1\">x</a>"
        + "<div class=\"title svelte-y\">Result Title</div>"
        + "<div class=\"generic-snippet svelte-z\">A useful snippet.</div></div>"));
    var tools = new WebTools(transport);
    assertThat(success(tools.execute("web_fetch", JSON.createObjectNode()
        .put("url", "https://example.com/page"))))
        .contains("Hello World", "[link text](https://dest.test/x)").doesNotContain("Home About");
    assertThat(failure(tools.execute("web_fetch", JSON.createObjectNode()
        .put("url", "https://127.0.0.1/secret"))).error().detail()).contains("SSRF");
    assertThat(failure(tools.execute("web_fetch", JSON.createObjectNode()
        .put("url", "http://example.com"))).error().kind()).isEqualTo(ToolErrorKind.INVALID_ARGS);
    assertThat(success(tools.execute("web_search", JSON.createObjectNode()
        .put("query", "test query")))).contains("[via Brave]", "Result Title",
            "https://result.test/page", "A useful snippet");
  }

  @Test
  void coversTransportErrorsFallbacksHeadersAndPlainText() {
    var transport = new FakeTransport();
    transport.rule("plain.test", new WebTransport.Response(200,
        Map.of("Content-Type", List.of("text/plain")), "plain", ""));
    transport.rule("search.brave.com", new WebTransport.Response(503, Map.of(), "", ""));
    transport.rule("html.duckduckgo.com", html("<a class=\"result__a\" href=\"https://ddg.test/x\">DDG Title</a>"
        + "<a class=\"result__snippet\">DDG snippet</a>"));
    var tools = new WebTools(transport);
    assertThat(success(tools.execute("web_fetch", JSON.createObjectNode().put("url", "https://plain.test")
        .put("method", "weird").put("display_description", "Fetching")
        .set("headers", JSON.createObjectNode().put("X-Test", "yes").put("x-no-jina", "1")))))
        .isEqualTo("Fetching\nplain");
    assertThat(transport.seen.getFirst().method()).isEqualTo("GET");
    assertThat(transport.seen.getFirst().headers()).containsEntry("x-test", "yes")
        .doesNotContainKey("x-no-jina");
    assertThat(success(tools.execute("web_search", JSON.createObjectNode().put("query", "fallback"))))
        .contains("[via DuckDuckGo]", "DDG Title");
    assertThat(failure(new WebTools(null).execute("web_fetch", JSON.createObjectNode()))
        .error().detail()).contains("unavailable");
    assertThat(failure(tools.execute("web_fetch", JSON.createObjectNode())).error().kind())
        .isEqualTo(ToolErrorKind.INVALID_ARGS);
    assertThat(failure(tools.execute("web_search", JSON.createObjectNode())).error().kind())
        .isEqualTo(ToolErrorKind.INVALID_ARGS);
    assertThat(failure(tools.execute("missing", JSON.createObjectNode())).error().kind())
        .isEqualTo(ToolErrorKind.UNKNOWN);
  }

  @Test
  void blocksPinnedPrivateAddressFamiliesAndCleansEntities() {
    for (String host : List.of("localhost", "x.localhost", "metadata.google.internal", "0.0.0.0",
        "10.1.2.3", "127.1.2.3", "169.254.1.1", "172.16.0.1", "192.168.1.1",
        "100.64.1.1", "224.0.0.1", "::1", "fd00::1", "fe80::1"))
      assertThat(WebTools.isBlockedHost(host)).as(host).isTrue();
    assertThat(WebTools.isBlockedHost("8.8.8.8")).isFalse();
    assertThat(WebTools.isBlockedHost("example.com")).isFalse();
    assertThat(WebTools.htmlToText("<h1>A &amp; B</h1><script>bad</script><p>x&nbsp;y</p>"))
        .isEqualTo("A & B\n\nx y");
  }

  @Test
  void coversFetchFailuresMethodsAndContentCap() {
    var transport = new FakeTransport();
    transport.rule("broken.test", new WebTransport.Response(0, Map.of(), "", "offline"));
    transport.rule("error.test", new WebTransport.Response(404, Map.of(), "missing", ""));
    transport.rule("large.test", new WebTransport.Response(200, Map.of(), "x".repeat(65_000), ""));
    transport.rule("post.test", new WebTransport.Response(200, Map.of(), "posted", ""));
    var tools = new WebTools(transport);
    assertThat(failure(tools.execute("web_fetch", JSON.createObjectNode()
        .put("url", "https://broken.test"))).error().detail()).isEqualTo("offline");
    assertThat(failure(tools.execute("web_fetch", JSON.createObjectNode()
        .put("url", "https://error.test"))).error().detail()).contains("HTTP 404");
    assertThat(success(tools.execute("web_fetch", JSON.createObjectNode()
        .put("url", "https://large.test")))).hasSizeGreaterThan(64_000).endsWith("characters]");
    assertThat(success(tools.execute("web_fetch", JSON.createObjectNode()
        .put("url", "https://post.test").put("method", "POST")))).isEqualTo("posted");
    assertThat(transport.seen.getLast().method()).isEqualTo("POST");
    assertThat(failure(tools.execute("web_fetch", JSON.createObjectNode().put("url", "https:///x")))
        .error().detail()).contains("empty host");
  }

  @Test
  void fallsThroughAllSearchEnginesAndParsesStartpage() {
    var none = new FakeTransport();
    none.rule("search.brave.com", html("no results"));
    none.rule("html.duckduckgo.com", new WebTransport.Response(0, Map.of(), "", "offline"));
    none.rule("www.startpage.com", new WebTransport.Response(429, Map.of(), "", ""));
    assertThat(success(new WebTools(none).execute("web_search", JSON.createObjectNode()
        .put("query", "nothing")))).contains("search returned no results", "tried 3 engines",
            "Brave", "DuckDuckGo", "Startpage");

    var startpage = new FakeTransport();
    startpage.rule("search.brave.com", html("none"));
    startpage.rule("html.duckduckgo.com", html("none"));
    startpage.rule("www.startpage.com", html("<a href=\"https://start.test/x\" "
        + "class=\"result-title abc\">Start Result</a>"));
    assertThat(success(new WebTools(startpage).execute("web_search", JSON.createObjectNode()
        .put("query", "start").put("count", -2).put("display_description", "Searching"))))
        .startsWith("Searching\n[via Startpage]").contains("Start Result");
    assertThat(WebTools.isBlockedHost("999.1.1.1")).isFalse();
    assertThat(WebTools.isBlockedHost("x.1.1.1")).isFalse();
  }

  private static WebTransport.Response html(String body) {
    return new WebTransport.Response(200, Map.of("content-type", List.of("text/html")), body, "");
  }
  private static final class FakeTransport implements WebTransport {
    private final Map<String, Response> rules = new LinkedHashMap<>();
    private final List<Request> seen = new ArrayList<>();
    private void rule(String needle, Response response) { rules.put(needle, response); }
    @Override public Response send(Request request) {
      seen.add(request);
      return rules.entrySet().stream().filter(entry -> request.url().contains(entry.getKey()))
          .map(Map.Entry::getValue).findFirst()
          .orElse(new Response(0, Map.of(), "", "no rule for " + request.url()));
    }
  }
  private static String success(ToolResult result) {
    return ((ToolResult.Success) result).output().text();
  }
  private static ToolResult.Failure failure(ToolResult result) { return (ToolResult.Failure) result; }
}
