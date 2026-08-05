package com.github.skanga.ajent.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.X509TrustManager;

/** JDK HTTP clients honoring Ajent's process-wide SOCKS5 air-gap contract. */
public final class EnvironmentHttpClient {
  private static final System.Logger LOGGER =
      System.getLogger(EnvironmentHttpClient.class.getName());
  private static final int DEFAULT_DIAL_PORT = 443;
  private static final int MAX_PROXY_HEADER = 64 * 1024;
  private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
  private static final String DISABLE_HOSTNAME_VERIFICATION =
      "jdk.internal.httpclient.disableHostnameVerification";
  private static final Map<BridgeConfig, SocksBridge> BRIDGES = new ConcurrentHashMap<>();
  private static final ProxySelector DIRECT = new ProxySelector() {
    @Override public List<Proxy> select(URI uri) {
      Objects.requireNonNull(uri, "uri");
      return List.of(Proxy.NO_PROXY);
    }

    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
  };

  private EnvironmentHttpClient() {}

  public static HttpClient create(Map<String, String> environment) {
    return builder(environment, Route.GENERAL).build();
  }

  public static HttpClient createProvider(Map<String, String> environment) {
    Objects.requireNonNull(environment, "environment");
    HostPort socks = parseDialAddress(environment.get("AJENT_SOCKS_PROXY")).orElse(null);
    HostPort api = parseDialAddress(environment.get("AJENT_API_HOST")).orElse(null);
    if (socks == null && api == null) return builder(environment, Route.PROVIDER).build();
    HostPort oauth = parseDialAddress(environment.get("AJENT_OAUTH_HOST")).orElse(null);
    var directEnvironment = new HashMap<>(environment);
    directEnvironment.remove("AJENT_SOCKS_PROXY");
    directEnvironment.remove("AJENT_API_HOST");
    directEnvironment.remove("AJENT_OAUTH_HOST");
    HttpClient configuration = builder(directEnvironment, Route.PROVIDER).build();
    return new RoutedHttpClient(configuration, api, oauth, socks,
        "1".equals(environment.get("AJENT_INSECURE")));
  }

  public static HttpClient createOAuth(Map<String, String> environment) {
    return builder(environment, Route.OAUTH).build();
  }

  public static HttpClient.Builder builder(Map<String, String> environment) {
    return builder(environment, Route.GENERAL);
  }

  public static HttpClient.Builder providerBuilder(Map<String, String> environment) {
    return builder(environment, Route.PROVIDER);
  }

  public static HttpClient.Builder oauthBuilder(Map<String, String> environment) {
    return builder(environment, Route.OAUTH);
  }

  private static HttpClient.Builder builder(Map<String, String> environment, Route route) {
    Objects.requireNonNull(environment, "environment");
    boolean insecure = "1".equals(environment.get("AJENT_INSECURE"));
    // JDK HttpClient otherwise re-enables endpoint identification internally. This property is
    // Intentionally process-wide because the insecure TLS context is configured once per process.
    if (insecure) System.setProperty(DISABLE_HOSTNAME_VERIFICATION, "true");
    HttpClient.Builder builder = HttpClient.newBuilder().proxy(DIRECT);
    HostPort socks = parseDialAddress(environment.get("AJENT_SOCKS_PROXY")).orElse(null);
    HostPort api = route == Route.PROVIDER
        ? parseDialAddress(environment.get("AJENT_API_HOST")).orElse(null) : null;
    HostPort oauth = route != Route.GENERAL
        ? parseDialAddress(environment.get("AJENT_OAUTH_HOST")).orElse(null) : null;
    var configuration = new BridgeConfig(socks, api, oauth, route);
    if (configuration.active()) {
      SocksBridge bridge = BRIDGES.computeIfAbsent(configuration, SocksBridge::open);
      builder.proxy(ProxySelector.of(bridge.address()));
    }
    if (insecure) configureInsecureTls(builder);
    return builder;
  }

  static Optional<HostPort> parseDialAddress(String specification) {
    if (specification == null || specification.isEmpty()) return Optional.empty();
    int colon = specification.indexOf(':');
    if (colon < 0) return Optional.of(new HostPort(specification, DEFAULT_DIAL_PORT));
    String host = specification.substring(0, colon);
    String portText = specification.substring(colon + 1);
    if (host.isEmpty() || portText.isEmpty()) return Optional.empty();
    try {
      int port = Integer.parseInt(portText);
      return port > 0 && port <= 65_535
          ? Optional.of(new HostPort(host, port)) : Optional.empty();
    } catch (NumberFormatException exception) {
      return Optional.empty();
    }
  }

  private static void configureInsecureTls(HttpClient.Builder builder) {
    try {
      var trustAll = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
      };
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new X509TrustManager[] {trustAll}, new SecureRandom());
      SSLParameters parameters = new SSLParameters();
      parameters.setEndpointIdentificationAlgorithm("");
      builder.sslContext(context).sslParameters(parameters);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("unable to configure insecure TLS", exception);
    }
  }

  static final class SocksBridge {
    private final BridgeConfig configuration;
    private final ServerSocket listener;

    private SocksBridge(BridgeConfig configuration, ServerSocket listener) {
      this.configuration = configuration;
      this.listener = listener;
    }

    static SocksBridge open(BridgeConfig configuration) {
      try {
        var listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32);
        var bridge = new SocksBridge(configuration, listener);
        Thread.startVirtualThread(bridge::accept);
        return bridge;
      } catch (IOException exception) {
        throw new IllegalStateException("unable to create local SOCKS bridge", exception);
      }
    }

    InetSocketAddress address() {
      return new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort());
    }

    private void accept() {
      while (!listener.isClosed()) {
        try {
          Socket client = listener.accept();
          Thread.startVirtualThread(() -> handle(client));
        } catch (IOException exception) {
          if (!listener.isClosed()) continue;
          return;
        }
      }
    }

    private void handle(Socket client) {
      try (client) {
        try {
          client.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
          byte[] header = readHeader(client.getInputStream());
          RequestHead request = RequestHead.parse(header);
          HostPort destination = configuration.destination(request);
          try (Socket upstream = configuration.upstreamSocket()) {
            upstream.connect(configuration.socketAddress(destination), CONNECT_TIMEOUT_MILLIS);
            upstream.setSoTimeout(0);
            client.setSoTimeout(0);
            if (request.connect()) {
              client.getOutputStream().write(
                  "HTTP/1.1 200 Connection Established\r\n\r\n"
                      .getBytes(StandardCharsets.US_ASCII));
            } else {
              upstream.getOutputStream().write(request.forwardHeader());
              upstream.getOutputStream().flush();
            }
            InputStream clientInput = client.getInputStream();
            OutputStream upstreamOutput = upstream.getOutputStream();
            Thread upstreamWriter = Thread.startVirtualThread(() ->
                copyQuietly(client, clientInput, upstream, upstreamOutput));
            copyQuietly(upstream, upstream.getInputStream(), client, client.getOutputStream());
            upstreamWriter.interrupt();
          }
        } catch (IOException | RuntimeException exception) {
          sendBadGateway(client);
        }
      } catch (IOException ignored) { }
    }

    static byte[] readHeader(InputStream input) throws IOException {
      var header = new ByteArrayOutputStream();
      int state = 0;
      while (header.size() < MAX_PROXY_HEADER) {
        int value = input.read();
        if (value < 0) throw new IOException("proxy request ended before headers");
        header.write(value);
        state = switch (state) {
          case 0 -> value == '\r' ? 1 : 0;
          case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
          case 2 -> value == '\r' ? 3 : 0;
          case 3 -> value == '\n' ? 4 : 0;
          default -> 4;
        };
        if (state == 4) return header.toByteArray();
      }
      throw new IOException("proxy request headers exceeded 64 KiB");
    }

    private static void copyQuietly(
        Socket sourceSocket, InputStream source, Socket targetSocket, OutputStream target) {
      try {
        source.transferTo(target);
        target.flush();
        targetSocket.shutdownOutput();
      } catch (IOException ignored) {
        try { sourceSocket.shutdownInput(); } catch (IOException ignoredAgain) { }
      }
    }

    private static void sendBadGateway(Socket client) {
      try {
        client.getOutputStream().write(("HTTP/1.1 502 Bad Gateway\r\n"
            + "Connection: close\r\nContent-Length: 0\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
      } catch (IOException exception) {
        LOGGER.log(System.Logger.Level.DEBUG, "Could not write proxy failure response", exception);
      }
    }
  }

  record RequestHead(boolean connect, String host, int port, byte[] forwardHeader) {
    static RequestHead parse(byte[] headerBytes) throws IOException {
      String header = new String(headerBytes, StandardCharsets.ISO_8859_1);
      int lineEnd = header.indexOf("\r\n");
      if (lineEnd <= 0) throw new IOException("invalid proxy request line");
      String[] request = header.substring(0, lineEnd).split(" ", 3);
      if (request.length != 3) throw new IOException("invalid proxy request line");
      if (request[0].equalsIgnoreCase("CONNECT")) {
        HostPort destination = authority(request[1], 443);
        return new RequestHead(true, destination.host(), destination.port(), new byte[0]);
      }
      URI target;
      try {
        target = URI.create(request[1]);
      } catch (IllegalArgumentException exception) {
        throw new IOException("invalid absolute proxy URI", exception);
      }
      if (target.getHost() == null || target.getScheme() == null) {
        throw new IOException("proxy request requires an absolute URI");
      }
      int port = target.getPort() > 0 ? target.getPort()
          : target.getScheme().equalsIgnoreCase("https") ? 443 : 80;
      String path = target.getRawPath();
      if (path == null || path.isEmpty()) path = "/";
      if (target.getRawQuery() != null) path += "?" + target.getRawQuery();
      String remaining = header.substring(lineEnd + 2)
          .replaceAll("(?im)^Proxy-Connection:[^\r\n]*\r\n", "")
          .replaceAll("(?im)^Connection:[^\r\n]*\r\n", "");
      remaining = remaining.substring(0, remaining.length() - 2)
          + "Connection: close\r\n\r\n";
      byte[] forwarded = (request[0] + " " + path + " " + request[2] + "\r\n" + remaining)
          .getBytes(StandardCharsets.ISO_8859_1);
      return new RequestHead(false, target.getHost(), port, forwarded);
    }

    private static HostPort authority(String value, int defaultPort) throws IOException {
      try {
        URI uri = URI.create("tcp://" + value);
        if (uri.getHost() == null) throw new IllegalArgumentException();
        return new HostPort(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : defaultPort);
      } catch (IllegalArgumentException exception) {
        throw new IOException("invalid CONNECT authority");
      }
    }
  }

  enum Route { GENERAL, PROVIDER, OAUTH }

  record BridgeConfig(HostPort socks, HostPort api, HostPort oauth, Route route) {
    boolean active() { return socks != null || api != null || oauth != null; }

    HostPort destination(RequestHead request) {
      var original = new HostPort(request.host(), request.port());
      return switch (route) {
        case GENERAL -> original;
        case OAUTH -> oauth == null ? original : oauth;
        case PROVIDER -> request.host().equalsIgnoreCase("platform.claude.com")
            ? oauth == null ? original : oauth
            : api == null ? original : api;
      };
    }

    Socket upstreamSocket() {
      return socks == null ? new Socket() : new Socket(new Proxy(Proxy.Type.SOCKS,
          InetSocketAddress.createUnresolved(socks.host(), socks.port())));
    }

    InetSocketAddress socketAddress(HostPort destination) {
      return socks == null
          ? new InetSocketAddress(destination.host(), destination.port())
          : InetSocketAddress.createUnresolved(destination.host(), destination.port());
    }
  }

  record HostPort(String host, int port) {}
}
