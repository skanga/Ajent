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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** JDK HTTP clients honoring AgenTTY's process-wide SOCKS5 air-gap contract. */
public final class EnvironmentHttpClient {
  private static final int DEFAULT_SOCKS_PORT = 1080;
  private static final int MAX_PROXY_HEADER = 64 * 1024;
  private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
  private static final Map<InetSocketAddress, SocksBridge> BRIDGES = new ConcurrentHashMap<>();
  private static final ProxySelector DIRECT = new ProxySelector() {
    @Override public List<Proxy> select(URI uri) {
      Objects.requireNonNull(uri, "uri");
      return List.of(Proxy.NO_PROXY);
    }

    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
  };

  private EnvironmentHttpClient() {}

  public static HttpClient create(Map<String, String> environment) {
    return builder(environment).build();
  }

  public static HttpClient.Builder builder(Map<String, String> environment) {
    Objects.requireNonNull(environment, "environment");
    HttpClient.Builder builder = HttpClient.newBuilder().proxy(DIRECT);
    String specification = environment.getOrDefault("AGENTTY_SOCKS_PROXY", "").strip();
    if (!specification.isEmpty()) {
      InetSocketAddress socks = parseSocksAddress(specification);
      SocksBridge bridge = BRIDGES.computeIfAbsent(socks, SocksBridge::open);
      builder.proxy(ProxySelector.of(bridge.address())).version(HttpClient.Version.HTTP_1_1);
    }
    return builder;
  }

  static InetSocketAddress parseSocksAddress(String specification) {
    String value = Objects.requireNonNull(specification, "specification").strip();
    if (value.isEmpty()) throw invalidProxy(value);
    String host;
    int port = DEFAULT_SOCKS_PORT;
    if (value.startsWith("[")) {
      int close = value.indexOf(']');
      if (close <= 1) throw invalidProxy(value);
      host = value.substring(1, close);
      if (close + 1 < value.length()) {
        if (value.charAt(close + 1) != ':') throw invalidProxy(value);
        port = port(value.substring(close + 2), value);
      }
    } else {
      int colon = value.lastIndexOf(':');
      if (colon > 0 && value.indexOf(':') == colon) {
        host = value.substring(0, colon);
        port = port(value.substring(colon + 1), value);
      } else {
        host = value;
      }
    }
    if (host.isBlank() || host.indexOf('/') >= 0 || host.indexOf('\\') >= 0) {
      throw invalidProxy(value);
    }
    return InetSocketAddress.createUnresolved(host, port);
  }

  private static int port(String value, String specification) {
    try {
      int port = Integer.parseInt(value);
      if (port < 1 || port > 65_535) throw invalidProxy(specification);
      return port;
    } catch (NumberFormatException exception) {
      throw invalidProxy(specification);
    }
  }

  private static IllegalArgumentException invalidProxy(String value) {
    return new IllegalArgumentException(
        "AGENTTY_SOCKS_PROXY must be host[:port] (got '" + value + "')");
  }

  static final class SocksBridge {
    private final InetSocketAddress socks;
    private final ServerSocket listener;

    private SocksBridge(InetSocketAddress socks, ServerSocket listener) {
      this.socks = socks;
      this.listener = listener;
    }

    static SocksBridge open(InetSocketAddress socks) {
      try {
        var listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32);
        var bridge = new SocksBridge(socks, listener);
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
          try (Socket upstream = new Socket(new Proxy(Proxy.Type.SOCKS, socks))) {
            upstream.connect(InetSocketAddress.createUnresolved(request.host(), request.port()),
                CONNECT_TIMEOUT_MILLIS);
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
      } catch (IOException ignored) { }
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
          .replaceAll("(?im)^Proxy-Connection:[^\r\n]*\r\n", "");
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

  private record HostPort(String host, int port) {}
}
