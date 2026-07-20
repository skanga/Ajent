package com.github.skanga.ajent.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

final class EnvironmentHttpClientTest {
  @Test void leavesTheJdkClientUnproxiedWhenTheContractVariableIsAbsent() {
    var selector = EnvironmentHttpClient.create(Map.of()).proxy().orElseThrow();
    assertThat(selector.select(URI.create("https://example.test")))
        .containsExactly(Proxy.NO_PROXY);
  }

  @Test void routesHttpThroughTheConfiguredSocks5TunnelWithoutLocalDns() throws Exception {
    HttpServer origin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    origin.createContext("/through", exchange -> {
      byte[] body = "proxied".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    origin.start();
    try (var socks = new FakeSocks(origin.getAddress().getPort())) {
      var client = EnvironmentHttpClient.create(Map.of(
          "AGENTTY_SOCKS_PROXY", "127.0.0.1:" + socks.port()));
      HttpResponse<String> response = client.send(HttpRequest.newBuilder(
          URI.create("http://unresolvable.invalid:" + origin.getAddress().getPort() + "/through"))
          .build(), HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).isEqualTo("proxied");
      assertThat(socks.requestedHost().get(5, TimeUnit.SECONDS))
          .isEqualTo("unresolvable.invalid");
    } finally {
      origin.stop(0);
    }
  }

  @Test void matchesNativeDialOverrideParsingAndSilentlyIgnoresMalformedValues() {
    assertThat(EnvironmentHttpClient.parseDialAddress("proxy")).contains(
        new EnvironmentHttpClient.HostPort("proxy", 443));
    assertThat(EnvironmentHttpClient.parseDialAddress("proxy:1080")).contains(
        new EnvironmentHttpClient.HostPort("proxy", 1080));
    for (String invalid : Arrays.asList(null, "", ":443", "proxy:", "proxy:nope",
        "proxy:0", "proxy:70000", "host:1:2")) {
      assertThat(EnvironmentHttpClient.parseDialAddress(invalid)).as(String.valueOf(invalid))
          .isEmpty();
    }
  }

  @Test void providerAndOauthOverridesChangeOnlyTheDialDestination() throws Exception {
    var apiHost = new CompletableFuture<String>();
    var oauthHost = new CompletableFuture<String>();
    HttpServer api = origin("api", apiHost);
    HttpServer oauth = origin("oauth", oauthHost);
    try {
      var client = EnvironmentHttpClient.createProvider(Map.of(
          "AGENTTY_API_HOST", "127.0.0.1:" + api.getAddress().getPort(),
          "AGENTTY_OAUTH_HOST", "127.0.0.1:" + oauth.getAddress().getPort()));

      assertThat(client.send(HttpRequest.newBuilder(
              URI.create("http://provider.invalid/v1/messages")).build(),
          HttpResponse.BodyHandlers.ofString()).body()).isEqualTo("api");
      assertThat(client.send(HttpRequest.newBuilder(
              URI.create("http://platform.claude.com/v1/oauth/token")).build(),
          HttpResponse.BodyHandlers.ofString()).body()).isEqualTo("oauth");
      assertThat(apiHost.get(5, TimeUnit.SECONDS)).isEqualTo("provider.invalid");
      assertThat(oauthHost.get(5, TimeUnit.SECONDS)).isEqualTo("platform.claude.com");
    } finally {
      api.stop(0);
      oauth.stop(0);
    }
  }

  @Test void dialOverridesPreserveTheJdkHttp2PreferenceForHostedTls() {
    var client = EnvironmentHttpClient.createProvider(Map.of(
        "AGENTTY_API_HOST", "127.0.0.1:9443"));

    assertThat(client.version()).isEqualTo(java.net.http.HttpClient.Version.HTTP_2);
  }

  @Test void routedClientPreservesAsyncRequestResponseAndLifecycleContracts() throws Exception {
    var method = new AtomicReference<String>();
    var requestBody = new AtomicReference<String>();
    var requestHeader = new AtomicReference<String>();
    HttpServer origin = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    origin.createContext("/upload", exchange -> {
      method.set(exchange.getRequestMethod());
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      requestHeader.set(exchange.getRequestHeaders().getFirst("X-Probe"));
      byte[] body = "accepted".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("X-Reply", "yes");
      exchange.sendResponseHeaders(201, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    origin.start();
    HttpClient client = EnvironmentHttpClient.createProvider(Map.of(
        "AGENTTY_API_HOST", "127.0.0.1:" + origin.getAddress().getPort()));
    try {
      HttpResponse<String> response = client.sendAsync(HttpRequest.newBuilder(
              URI.create("http://logical-provider.invalid/upload"))
          .header("content-type", "text/plain").header("x-probe", "present")
          .POST(HttpRequest.BodyPublishers.ofString("payload")).build(),
          HttpResponse.BodyHandlers.ofString()).get(5, TimeUnit.SECONDS);

      assertThat(method.get()).isEqualTo("POST");
      assertThat(requestBody.get()).isEqualTo("payload");
      assertThat(requestHeader.get()).isEqualTo("present");
      assertThat(response.statusCode()).isEqualTo(201);
      assertThat(response.body()).isEqualTo("accepted");
      assertThat(response.headers().firstValue("x-reply")).contains("yes");
      assertThat(response.request().method()).isEqualTo("POST");
      assertThat(response.uri()).isEqualTo(URI.create("http://logical-provider.invalid/upload"));
      assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
      assertThat(response.previousResponse()).isEmpty();
      assertThat(response.sslSession()).isEmpty();
      assertThat(client.cookieHandler()).isEmpty();
      assertThat(client.authenticator()).isEmpty();
      assertThat(client.connectTimeout()).isEmpty();
      assertThat(client.executor()).isEmpty();
      assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
      assertThat(client.proxy()).isPresent();
      assertThat(client.sslContext()).isNotNull();
      assertThat(client.sslParameters()).isNotNull();

      var streamed = client.sendAsync(HttpRequest.newBuilder(
              URI.create("http://logical-provider.invalid/upload")).build(),
          HttpResponse.BodyHandlers.ofInputStream());
      HttpResponse<java.io.InputStream> streamingResponse = streamed.get(5, TimeUnit.SECONDS);
      assertThat(streamed.cancel(true)).isFalse();
      streamingResponse.body().close();
    } finally {
      client.shutdown();
      assertThat(client.awaitTermination(Duration.ofSeconds(2))).isTrue();
      assertThat(client.isTerminated()).isTrue();
      origin.stop(0);
    }
  }

  @Test void routedClientCoversConfiguredRedirectTimeoutAndInsecureTls() throws Exception {
    HttpClient configuration = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(1)).build();
    try (var client = new RoutedHttpClient(configuration,
        new EnvironmentHttpClient.HostPort("127.0.0.1", 9443), null, null, true)) {
      assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NORMAL);
      assertThat(client.connectTimeout()).contains(Duration.ofSeconds(1));
      client.shutdownNow();
    }
  }

  @Test void routedProviderUsesSocksWithoutResolvingTheLogicalHostLocally() throws Exception {
    HttpServer origin = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    origin.createContext("/", exchange -> {
      byte[] body = "routed-socks".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    origin.start();
    try (var socks = new FakeSocks(origin.getAddress().getPort());
         var client = EnvironmentHttpClient.createProvider(Map.of(
             "AGENTTY_SOCKS_PROXY", "127.0.0.1:" + socks.port()))) {
      HttpResponse<String> response = client.send(HttpRequest.newBuilder(
              URI.create("http://provider-through-socks.invalid/")).build(),
          HttpResponse.BodyHandlers.ofString());

      assertThat(response.body()).isEqualTo("routed-socks");
      assertThat(socks.requestedHost().get(5, TimeUnit.SECONDS))
          .isEqualTo("provider-through-socks.invalid");
    } finally {
      origin.stop(0);
    }
  }

  @Test void routedClientPropagatesBodyPublisherAndHandlerFailures() throws Exception {
    HttpServer origin = origin("unused", new CompletableFuture<>());
    try (var client = EnvironmentHttpClient.createProvider(Map.of(
        "AGENTTY_API_HOST", "127.0.0.1:" + origin.getAddress().getPort()))) {
      HttpRequest.BodyPublisher brokenBody = new HttpRequest.BodyPublisher() {
        @Override public long contentLength() { return -1; }
        @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
          subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long count) {
              subscriber.onError(new IOException("publisher failed"));
            }
            @Override public void cancel() { }
          });
        }
      };
      var brokenRequest = HttpRequest.newBuilder(URI.create("http://provider.invalid/"))
          .POST(brokenBody).build();
      assertThatThrownBy(() -> client.sendAsync(
              brokenRequest, HttpResponse.BodyHandlers.discarding()).join())
          .hasRootCauseInstanceOf(IOException.class)
          .hasRootCauseMessage("publisher failed");

      HttpRequest.BodyPublisher nonIoFailure = new HttpRequest.BodyPublisher() {
        @Override public long contentLength() { return -1; }
        @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
          subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long count) {
              subscriber.onError(new IllegalStateException("non-io publisher failure"));
            }
            @Override public void cancel() { }
          });
        }
      };
      assertThatThrownBy(() -> client.sendAsync(HttpRequest.newBuilder(
              URI.create("http://provider.invalid/")).POST(nonIoFailure).build(),
          HttpResponse.BodyHandlers.discarding()).join())
          .isInstanceOf(java.util.concurrent.CompletionException.class)
          .hasCauseInstanceOf(IOException.class)
          .hasRootCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("non-io publisher failure");

      var get = HttpRequest.newBuilder(URI.create("http://provider.invalid/")).build();
      assertThatThrownBy(() -> client.send(get, info -> {
        throw new IllegalStateException("handler failed");
      })).isInstanceOf(IllegalStateException.class).hasMessage("handler failed");

      HttpResponse.BodyHandler<Void> nonPositiveDemand = info ->
          new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<Void> body = new CompletableFuture<>();
            @Override public java.util.concurrent.CompletionStage<Void> getBody() { return body; }
            @Override public void onSubscribe(Flow.Subscription subscription) {
              subscription.request(0);
            }
            @Override public void onNext(List<ByteBuffer> item) { }
            @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
            @Override public void onComplete() { body.complete(null); }
          };
      assertThatThrownBy(() -> client.send(get, nonPositiveDemand))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("non-positive response demand");
    } finally {
      origin.stop(0);
    }
  }

  @Test void routedClientHonorsFiniteResponseDemandAcrossChunks() throws Exception {
    byte[] payload = new byte[40 * 1024];
    Arrays.fill(payload, (byte) 'x');
    HttpServer origin = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    origin.createContext("/", exchange -> {
      exchange.sendResponseHeaders(200, payload.length);
      exchange.getResponseBody().write(payload);
      exchange.close();
    });
    origin.start();
    try (var client = EnvironmentHttpClient.createProvider(Map.of(
        "AGENTTY_API_HOST", "127.0.0.1:" + origin.getAddress().getPort()))) {
      HttpResponse<Integer> response = client.send(HttpRequest.newBuilder(
              URI.create("http://provider.invalid/")).build(), info ->
          new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<Integer> body = new CompletableFuture<>();
            private Flow.Subscription subscription;
            private int bytes;
            @Override public java.util.concurrent.CompletionStage<Integer> getBody() { return body; }
            @Override public void onSubscribe(Flow.Subscription value) {
              subscription = value;
              subscription.request(1);
            }
            @Override public void onNext(List<ByteBuffer> items) {
              items.forEach(item -> bytes += item.remaining());
              subscription.request(1);
            }
            @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
            @Override public void onComplete() { body.complete(bytes); }
          });
      assertThat(response.body()).isEqualTo(payload.length);
    } finally {
      origin.stop(0);
    }
  }

  @Test void routedSynchronousSendPropagatesConnectionFailures() throws Exception {
    int unusedPort;
    try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      unusedPort = socket.getLocalPort();
    }
    try (var client = EnvironmentHttpClient.createProvider(Map.of(
        "AGENTTY_API_HOST", "127.0.0.1:" + unusedPort))) {
      assertThatThrownBy(() -> client.send(HttpRequest.newBuilder(
              URI.create("http://provider.invalid/")).build(),
          HttpResponse.BodyHandlers.discarding())).isInstanceOf(IOException.class);
    }
  }

  @Test void routedClientCancelsBeforeHeadersAndHonorsSubscriberCancellation() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    HttpServer origin = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    origin.createContext("/stall", exchange -> {
      entered.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      exchange.close();
    });
    origin.createContext("/cancel-body", exchange -> {
      byte[] body = "ignored".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    origin.start();
    try (var client = EnvironmentHttpClient.createProvider(Map.of(
        "AGENTTY_API_HOST", "127.0.0.1:" + origin.getAddress().getPort()))) {
      var pending = client.sendAsync(HttpRequest.newBuilder(
              URI.create("http://provider.invalid/stall")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(pending.cancel(true)).isTrue();
      assertThat(pending).isCancelled();
      release.countDown();

      HttpResponse<String> cancelledBody = client.send(HttpRequest.newBuilder(
              URI.create("http://provider.invalid/cancel-body")).build(), info ->
          new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<String> body = new CompletableFuture<>();
            @Override public java.util.concurrent.CompletionStage<String> getBody() { return body; }
            @Override public void onSubscribe(Flow.Subscription subscription) {
              subscription.cancel();
              body.complete("cancelled");
            }
            @Override public void onNext(List<ByteBuffer> item) { }
            @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
            @Override public void onComplete() { }
          });
      assertThat(cancelledBody.body()).isEqualTo("cancelled");
    } finally {
      release.countDown();
      origin.stop(0);
    }
  }

  @Test void oauthAndGeneralClientsApplyOnlyTheirOwnOverrideScope() throws Exception {
    var seenHost = new CompletableFuture<String>();
    HttpServer oauth = origin("oauth", seenHost);
    try {
      var oauthClient = EnvironmentHttpClient.createOAuth(Map.of(
          "AGENTTY_API_HOST", "bad.invalid:1",
          "AGENTTY_OAUTH_HOST", "127.0.0.1:" + oauth.getAddress().getPort()));
      assertThat(oauthClient.send(HttpRequest.newBuilder(
              URI.create("http://custom-oauth.invalid/token")).build(),
          HttpResponse.BodyHandlers.ofString()).body()).isEqualTo("oauth");
      assertThat(seenHost.get(5, TimeUnit.SECONDS)).isEqualTo("custom-oauth.invalid");

      var generalSelector = EnvironmentHttpClient.create(Map.of(
          "AGENTTY_API_HOST", "127.0.0.1:" + oauth.getAddress().getPort()))
          .proxy().orElseThrow();
      assertThat(generalSelector.select(URI.create("http://provider.invalid")))
          .containsExactly(Proxy.NO_PROXY);
    } finally {
      oauth.stop(0);
    }
  }

  @Test void insecureTlsIsEnabledOnlyByTheExactNativeFlag() {
    var normal = EnvironmentHttpClient.create(Map.of());
    var zero = EnvironmentHttpClient.create(Map.of("AGENTTY_INSECURE", "0"));
    var insecure = EnvironmentHttpClient.create(Map.of("AGENTTY_INSECURE", "1"));

    assertThat(zero.sslContext()).isSameAs(normal.sslContext());
    assertThat(insecure.sslContext()).isNotSameAs(normal.sslContext());
    assertThat(insecure.sslParameters().getEndpointIdentificationAlgorithm()).isEmpty();
  }

  @Test void insecureTlsAcceptsAnUntrustedCertificateWithTheWrongHostname() throws Exception {
    HttpsServer server = tlsOrigin();
    String override = "127.0.0.1:" + server.getAddress().getPort();
    HttpRequest request = HttpRequest.newBuilder(
        URI.create("https://certificate-name.invalid/")).build();
    try {
      assertThatThrownBy(() -> EnvironmentHttpClient.createProvider(Map.of(
              "AGENTTY_API_HOST", override)).send(request,
          HttpResponse.BodyHandlers.discarding())).isInstanceOf(IOException.class);

      Process probe = new ProcessBuilder(
          Path.of(System.getProperty("java.home"), "bin",
              System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
          "-cp", System.getProperty("java.class.path"), InsecureTlsProbe.class.getName(), override)
          .redirectErrorStream(true).start();
      assertThat(probe.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("tls");
      assertThat(probe.exitValue()).isZero();
    } finally {
      server.stop(0);
    }
  }

  @Test void sendsHttpsAsConnectAndResolvesItsHostThroughSocks() throws Exception {
    try (var sink = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
         var socks = new FakeSocks(sink.getLocalPort())) {
      Thread.startVirtualThread(() -> {
        try (Socket accepted = sink.accept()) {
          accepted.getInputStream().readNBytes(8);
        } catch (IOException ignored) { }
      });
      var client = EnvironmentHttpClient.builder(Map.of(
              "AGENTTY_SOCKS_PROXY", "127.0.0.1:" + socks.port()))
          .connectTimeout(Duration.ofSeconds(2)).build();

      assertThatThrownBy(() -> client.send(HttpRequest.newBuilder(
              URI.create("https://tls-only.invalid/")).build(),
          HttpResponse.BodyHandlers.discarding())).isInstanceOf(IOException.class);
      assertThat(socks.requestedHost().get(5, TimeUnit.SECONDS)).isEqualTo("tls-only.invalid");
    }
  }

  @Test void rewritesAbsoluteProxyRequestsAndParsesConnectAuthorities() throws Exception {
    var connect = EnvironmentHttpClient.RequestHead.parse(ascii(
        "CONNECT example.test:8443 HTTP/1.1\r\nHost: example.test\r\n\r\n"));
    assertThat(connect.connect()).isTrue();
    assertThat(connect.host()).isEqualTo("example.test");
    assertThat(connect.port()).isEqualTo(8443);
    assertThat(connect.forwardHeader()).isEmpty();

    var defaultConnect = EnvironmentHttpClient.RequestHead.parse(ascii(
        "CONNECT example.test HTTP/1.1\r\n\r\n"));
    assertThat(defaultConnect.port()).isEqualTo(443);

    var request = EnvironmentHttpClient.RequestHead.parse(ascii(
        "GET http://example.test:8080/path?q=x HTTP/1.1\r\n"
            + "Host: example.test\r\nProxy-Connection: keep-alive\r\nX-Test: yes\r\n\r\n"));
    assertThat(request.connect()).isFalse();
    assertThat(request.host()).isEqualTo("example.test");
    assertThat(request.port()).isEqualTo(8080);
    assertThat(new String(request.forwardHeader(), StandardCharsets.ISO_8859_1))
        .isEqualTo("GET /path?q=x HTTP/1.1\r\nHost: example.test\r\nX-Test: yes\r\n"
            + "Connection: close\r\n\r\n");

    assertThat(EnvironmentHttpClient.RequestHead.parse(ascii(
        "GET https://example.test HTTP/1.1\r\n\r\n")).port()).isEqualTo(443);
    assertThat(new String(EnvironmentHttpClient.RequestHead.parse(ascii(
        "GET http://example.test HTTP/1.1\r\n\r\n")).forwardHeader(),
        StandardCharsets.ISO_8859_1)).startsWith("GET / HTTP/1.1");
  }

  @Test void rejectsMalformedProxyRequestsAndBoundsHeaders() throws Exception {
    for (String invalid : Arrays.asList(
        "broken\r\n\r\n",
        "GET /relative HTTP/1.1\r\n\r\n",
        "GET http://[bad HTTP/1.1\r\n\r\n",
        "CONNECT :443 HTTP/1.1\r\n\r\n")) {
      assertThatThrownBy(() -> EnvironmentHttpClient.RequestHead.parse(ascii(invalid)))
          .as(invalid).isInstanceOf(IOException.class);
    }
    assertThatThrownBy(() -> EnvironmentHttpClient.SocksBridge.readHeader(
        new ByteArrayInputStream(ascii("GET / HTTP/1.1\r\nHost: x"))))
        .isInstanceOf(IOException.class).hasMessageContaining("ended before headers");
    assertThatThrownBy(() -> EnvironmentHttpClient.SocksBridge.readHeader(
        new ByteArrayInputStream(new byte[64 * 1024])))
        .isInstanceOf(IOException.class).hasMessageContaining("exceeded 64 KiB");
    assertThat(EnvironmentHttpClient.SocksBridge.readHeader(
        new ByteArrayInputStream(ascii("\r\n\r\n")))).isEqualTo(ascii("\r\n\r\n"));
  }

  @Test void returnsBadGatewayWhenTheConfiguredSocksProxyCannotBeReached() throws Exception {
    int unusedPort;
    try (var unused = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      unusedPort = unused.getLocalPort();
    }
    var client = EnvironmentHttpClient.create(Map.of(
        "AGENTTY_SOCKS_PROXY", "127.0.0.1:" + unusedPort));
    HttpResponse<Void> response = client.send(HttpRequest.newBuilder(
            URI.create("http://unreachable.invalid/")).timeout(Duration.ofSeconds(2)).build(),
        HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(502);
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static HttpServer origin(String body, CompletableFuture<String> host) throws IOException {
    HttpServer server = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", exchange -> {
      host.complete(exchange.getRequestHeaders().getFirst("Host"));
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    return server;
  }

  private static HttpsServer tlsOrigin() throws Exception {
    byte[] encoded;
    try (var resource = EnvironmentHttpClientTest.class.getResourceAsStream(
        "/insecure-test.p12.b64")) {
      encoded = Base64.getMimeDecoder().decode(resource.readAllBytes());
    }
    char[] password = "changeit".toCharArray();
    KeyStore keys = KeyStore.getInstance("PKCS12");
    keys.load(new ByteArrayInputStream(encoded), password);
    KeyManagerFactory managers = KeyManagerFactory.getInstance(
        KeyManagerFactory.getDefaultAlgorithm());
    managers.init(keys, password);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(managers.getKeyManagers(), null, null);

    HttpsServer server = HttpsServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(context));
    server.createContext("/", exchange -> {
      byte[] body = "tls".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    return server;
  }

  public static final class InsecureTlsProbe {
    private InsecureTlsProbe() { }

    public static void main(String[] arguments) throws Exception {
      HttpResponse<String> response = EnvironmentHttpClient.createProvider(Map.of(
              "AGENTTY_API_HOST", arguments[0], "AGENTTY_INSECURE", "1"))
          .send(HttpRequest.newBuilder(URI.create("https://certificate-name.invalid/")).build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) System.exit(2);
      System.out.print(response.body());
    }
  }

  private static final class FakeSocks implements AutoCloseable {
    private final ServerSocket listener;
    private final int originPort;
    private final CompletableFuture<String> requestedHost = new CompletableFuture<>();

    private FakeSocks(int originPort) throws IOException {
      this.originPort = originPort;
      listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
      Thread.startVirtualThread(this::serve);
    }

    int port() { return listener.getLocalPort(); }
    CompletableFuture<String> requestedHost() { return requestedHost; }

    private void serve() {
      try (Socket client = listener.accept()) {
        var input = client.getInputStream();
        var output = client.getOutputStream();
        if (input.read() != 5) throw new IOException("not SOCKS5");
        int methods = input.read();
        input.readNBytes(methods);
        output.write(new byte[] {5, 0});
        output.flush();
        if (input.read() != 5 || input.read() != 1) throw new IOException("not CONNECT");
        input.read();
        int addressType = input.read();
        String host = switch (addressType) {
          case 1 -> InetAddress.getByAddress(input.readNBytes(4)).getHostAddress();
          case 3 -> new String(input.readNBytes(input.read()), StandardCharsets.US_ASCII);
          case 4 -> InetAddress.getByAddress(input.readNBytes(16)).getHostAddress();
          default -> throw new IOException("unsupported address");
        };
        input.readNBytes(2);
        requestedHost.complete(host);
        try (Socket origin = new Socket(InetAddress.getLoopbackAddress(), originPort)) {
          output.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
          output.flush();
          Thread toOrigin = Thread.startVirtualThread(() -> copy(client, origin));
          origin.getInputStream().transferTo(output);
          output.flush();
          toOrigin.interrupt();
        }
      } catch (Exception exception) {
        requestedHost.completeExceptionally(exception);
      }
    }

    private static void copy(Socket source, Socket target) {
      try {
        source.getInputStream().transferTo(target.getOutputStream());
        target.getOutputStream().flush();
      } catch (IOException ignored) { }
    }

    @Override public void close() throws IOException { listener.close(); }
  }
}
