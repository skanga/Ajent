package com.github.skanga.ajent.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509TrustManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.RequestBody;

/**
 * {@link HttpClient} adapter that keeps the logical URI, Host header, TLS SNI, and certificate
 * verification target while dialing a different TCP endpoint. The JDK client cannot retain HTTP/2
 * through an HTTP CONNECT proxy, so AgenTTY's dial-only provider override uses this adapter.
 */
final class RoutedHttpClient extends HttpClient {
  private final HttpClient configuration;
  private final OkHttpClient client;

  RoutedHttpClient(HttpClient configuration, EnvironmentHttpClient.HostPort api,
                   EnvironmentHttpClient.HostPort oauth,
                   EnvironmentHttpClient.HostPort socks, boolean insecure) {
    this.configuration = configuration;
    var dispatcher = new Dispatcher(java.util.concurrent.Executors.newThreadPerTaskExecutor(
        java.lang.Thread.ofVirtual().name("ajent-http-", 0).factory()));
    var builder = new OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .socketFactory(new DialingSocketFactory(api, oauth, socks))
        .dns(hostname -> List.of(java.net.InetAddress.getByAddress(
            hostname, new byte[] {127, 0, 0, 1})))
        .followRedirects(configuration.followRedirects() != Redirect.NEVER)
        .followSslRedirects(configuration.followRedirects() != Redirect.NEVER)
        .protocols(List.of(Protocol.HTTP_2, Protocol.HTTP_1_1));
    configuration.connectTimeout().ifPresent(builder::connectTimeout);
    if (insecure) configureInsecureTls(builder);
    client = builder.build();
  }

  @Override public Optional<CookieHandler> cookieHandler() { return configuration.cookieHandler(); }
  @Override public Optional<Duration> connectTimeout() { return configuration.connectTimeout(); }
  @Override public Redirect followRedirects() { return configuration.followRedirects(); }
  @Override public Optional<ProxySelector> proxy() { return configuration.proxy(); }
  @Override public SSLContext sslContext() { return configuration.sslContext(); }
  @Override public SSLParameters sslParameters() { return configuration.sslParameters(); }
  @Override public Optional<Authenticator> authenticator() { return configuration.authenticator(); }
  @Override public Version version() { return Version.HTTP_2; }
  @Override public Optional<Executor> executor() { return configuration.executor(); }

  @Override public <T> HttpResponse<T> send(
      HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    try {
      return sendAsync(request, handler).get();
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof IOException io) throw io;
      if (cause instanceof RuntimeException runtime) throw runtime;
      throw new IOException(cause);
    }
  }

  @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    try {
      okhttp3.Request routedRequest = toOkHttp(request);
      Call call = client.newCall(routedRequest);
      request.timeout().ifPresent(timeout -> call.timeout().timeout(
          timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS));
      var result = new CallFuture<T>(call);
      call.enqueue(new Callback() {
        @Override public void onFailure(Call failedCall, IOException failure) {
          result.completeExceptionally(failure);
        }

        @Override public void onResponse(Call completedCall, okhttp3.Response response) {
          deliver(request, handler, response, result);
        }
      });
      return result;
    } catch (IOException | RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }
  }

  @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    return sendAsync(request, handler);
  }

  @Override public void shutdown() {
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
  }

  @Override public void shutdownNow() {
    client.dispatcher().cancelAll();
    shutdown();
  }

  @Override public boolean isTerminated() {
    return client.dispatcher().executorService().isTerminated();
  }

  @Override public boolean awaitTermination(Duration duration) throws InterruptedException {
    return client.dispatcher().executorService().awaitTermination(
        duration.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
  }

  private static okhttp3.Request toOkHttp(HttpRequest request) throws IOException {
    var builder = new okhttp3.Request.Builder().url(request.uri().toString());
    request.headers().map().forEach((name, values) ->
        values.forEach(value -> builder.addHeader(name, value)));
    RequestBody body = null;
    if (request.bodyPublisher().isPresent()) {
      String contentType = request.headers().firstValue("content-type")
          .orElse("application/octet-stream");
      body = RequestBody.create(collect(request.bodyPublisher().orElseThrow()),
          MediaType.parse(contentType));
    }
    return builder.method(request.method(), body).build();
  }

  private static byte[] collect(HttpRequest.BodyPublisher publisher) throws IOException {
    var result = new CompletableFuture<byte[]>();
    var output = new ByteArrayOutputStream();
    publisher.subscribe(new Flow.Subscriber<>() {
      @Override public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override public void onNext(ByteBuffer item) {
        byte[] bytes = new byte[item.remaining()];
        item.get(bytes);
        output.writeBytes(bytes);
      }

      @Override public void onError(Throwable failure) {
        result.completeExceptionally(failure);
      }

      @Override public void onComplete() {
        result.complete(output.toByteArray());
      }
    });
    try {
      return result.join();
    } catch (CompletionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof IOException io) throw io;
      throw new IOException("unable to publish HTTP request body", cause);
    }
  }

  private static <T> void deliver(
      HttpRequest request, HttpResponse.BodyHandler<T> handler, okhttp3.Response response,
      CallFuture<T> result) {
    HttpHeaders headers = HttpHeaders.of(response.headers().toMultimap(), (name, value) -> true);
    Version version = response.protocol() == Protocol.HTTP_2 ? Version.HTTP_2 : Version.HTTP_1_1;
    HttpResponse.ResponseInfo info = new ResponseInformation(response.code(), headers, version);
    HttpResponse.BodySubscriber<T> subscriber;
    try {
      subscriber = handler.apply(info);
    } catch (RuntimeException failure) {
      response.close();
      result.completeExceptionally(failure);
      return;
    }
    subscriber.getBody().whenComplete((body, failure) -> {
      if (failure != null) result.completeExceptionally(failure);
      else result.complete(new RoutedResponse<>(
          response.code(), request, headers, body, request.uri(), version));
    });
    subscriber.onSubscribe(new ResponseSubscription(response, result.call, subscriber));
  }

  private static void configureInsecureTls(OkHttpClient.Builder builder) {
    try {
      X509TrustManager trustAll = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
      };
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new X509TrustManager[] {trustAll}, new SecureRandom());
      builder.sslSocketFactory(context.getSocketFactory(), trustAll)
          .hostnameVerifier((host, session) -> true);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("unable to configure routed insecure TLS", exception);
    }
  }

  private record ResponseInformation(
      int statusCode, HttpHeaders headers, Version version) implements HttpResponse.ResponseInfo { }

  private record RoutedResponse<T>(
      int statusCode, HttpRequest request, HttpHeaders headers, T body, URI uri, Version version)
      implements HttpResponse<T> {
    @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
    @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
  }

  private static final class CallFuture<T> extends CompletableFuture<HttpResponse<T>> {
    private final Call call;

    private CallFuture(Call call) { this.call = call; }

    @Override public boolean cancel(boolean mayInterruptIfRunning) {
      boolean cancelled = super.cancel(mayInterruptIfRunning);
      if (cancelled) call.cancel();
      return cancelled;
    }
  }

  private static final class ResponseSubscription implements Flow.Subscription {
    private final okhttp3.Response response;
    private final Call call;
    private final HttpResponse.BodySubscriber<?> subscriber;
    private final Object demandMonitor = new Object();
    private final AtomicBoolean started = new AtomicBoolean();
    private long demand;
    private volatile boolean cancelled;

    private ResponseSubscription(
        okhttp3.Response response, Call call, HttpResponse.BodySubscriber<?> subscriber) {
      this.response = response;
      this.call = call;
      this.subscriber = subscriber;
    }

    @Override public void request(long count) {
      if (count <= 0) {
        cancel();
        subscriber.onError(new IllegalArgumentException("non-positive response demand"));
        return;
      }
      synchronized (demandMonitor) {
        try {
          demand = Math.addExact(demand, count);
        } catch (ArithmeticException overflow) {
          demand = Long.MAX_VALUE;
        }
        demandMonitor.notifyAll();
      }
      if (started.compareAndSet(false, true)) java.lang.Thread.startVirtualThread(this::read);
    }

    @Override public void cancel() {
      synchronized (demandMonitor) {
        cancelled = true;
        demandMonitor.notifyAll();
      }
      call.cancel();
    }

    private void read() {
      try (response; var input = response.body().byteStream()) {
        byte[] buffer = new byte[16 * 1024];
        while (true) {
          awaitDemand();
          if (cancelled) return;
          int count = input.read(buffer);
          if (count < 0) {
            subscriber.onComplete();
            return;
          }
          subscriber.onNext(List.of(ByteBuffer.wrap(buffer, 0, count).asReadOnlyBuffer()));
          synchronized (demandMonitor) {
            if (demand != Long.MAX_VALUE) demand--;
          }
        }
      } catch (IOException | RuntimeException failure) {
        reportUnlessCancelled(failure);
      } catch (InterruptedException interrupted) {
        java.lang.Thread.currentThread().interrupt();
        reportUnlessCancelled(interrupted);
      }
    }

    private void reportUnlessCancelled(Throwable failure) {
      if (!cancelled) subscriber.onError(failure);
    }

    private void awaitDemand() throws InterruptedException {
      synchronized (demandMonitor) {
        while (!cancelled && demand == 0) demandMonitor.wait();
      }
    }
  }

  private static final class DialingSocketFactory extends SocketFactory {
    private final EnvironmentHttpClient.HostPort api;
    private final EnvironmentHttpClient.HostPort oauth;
    private final EnvironmentHttpClient.HostPort socks;

    private DialingSocketFactory(
        EnvironmentHttpClient.HostPort api, EnvironmentHttpClient.HostPort oauth,
        EnvironmentHttpClient.HostPort socks) {
      this.api = api;
      this.oauth = oauth;
      this.socks = socks;
    }

    @Override public Socket createSocket() {
      return socks == null ? new RoutedSocket(api, oauth)
          : new RoutedSocket(api, oauth, new Proxy(Proxy.Type.SOCKS,
              InetSocketAddress.createUnresolved(socks.host(), socks.port())));
    }

    @Override public Socket createSocket(String host, int port) throws IOException {
      Socket socket = createSocket();
      socket.connect(InetSocketAddress.createUnresolved(host, port));
      return socket;
    }

    @Override public Socket createSocket(
        String host, int port, java.net.InetAddress localHost, int localPort) throws IOException {
      Socket socket = createSocket();
      socket.bind(new InetSocketAddress(localHost, localPort));
      socket.connect(InetSocketAddress.createUnresolved(host, port));
      return socket;
    }

    @Override public Socket createSocket(java.net.InetAddress host, int port) throws IOException {
      return createSocket(host.getHostName(), port);
    }

    @Override public Socket createSocket(
        java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort)
        throws IOException {
      return createSocket(address.getHostName(), port, localAddress, localPort);
    }
  }

  private static final class RoutedSocket extends Socket {
    private final EnvironmentHttpClient.HostPort api;
    private final EnvironmentHttpClient.HostPort oauth;
    private final boolean proxied;

    private RoutedSocket(
        EnvironmentHttpClient.HostPort api, EnvironmentHttpClient.HostPort oauth) {
      this.api = api;
      this.oauth = oauth;
      proxied = false;
    }
    private RoutedSocket(
        EnvironmentHttpClient.HostPort api, EnvironmentHttpClient.HostPort oauth, Proxy proxy) {
      super(proxy);
      this.api = api;
      this.oauth = oauth;
      proxied = true;
    }

    @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
      if (!(endpoint instanceof InetSocketAddress original)) {
        super.connect(endpoint, timeout);
        return;
      }
      EnvironmentHttpClient.HostPort dial = original.getHostString()
          .equalsIgnoreCase("platform.claude.com") ? oauth : api;
      String host = dial == null ? original.getHostString() : dial.host();
      int port = dial == null ? original.getPort() : dial.port();
      SocketAddress destination = proxied
          ? InetSocketAddress.createUnresolved(host, port) : new InetSocketAddress(host, port);
      super.connect(destination, timeout);
    }

    @Override public void connect(SocketAddress endpoint) throws IOException {
      connect(endpoint, 0);
    }
  }
}
