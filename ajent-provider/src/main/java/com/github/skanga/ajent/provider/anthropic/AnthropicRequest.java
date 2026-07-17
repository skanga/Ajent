package com.github.skanga.ajent.provider.anthropic;

import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.provider.auth.MachineSeed;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Complete Anthropic Messages API request, including typed auth and wire controls. */
public record AnthropicRequest(
    String model,
    String systemPrompt,
    List<Message> messages,
    List<ToolSpecification> tools,
    int maxTokens,
    ProviderAuth auth,
    int retryCount,
    String effort,
    URI endpoint,
    String userId) {
  private static final URI DEFAULT_ENDPOINT =
      URI.create("https://api.anthropic.com/v1/messages?beta=true");

  public AnthropicRequest(
      String model, String systemPrompt, List<Message> messages,
      List<ToolSpecification> tools, int maxTokens, ProviderAuth auth,
      int retryCount, String effort) {
    this(model, systemPrompt, messages, tools, maxTokens, auth, retryCount, effort,
        DEFAULT_ENDPOINT, Identity.USER_ID);
  }

  public AnthropicRequest {
    model = Objects.requireNonNull(model, "model");
    systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
    messages = List.copyOf(messages);
    tools = List.copyOf(tools);
    auth = Objects.requireNonNull(auth, "auth");
    effort = Objects.requireNonNull(effort, "effort");
    endpoint = Objects.requireNonNull(endpoint, "endpoint");
    userId = Objects.requireNonNull(userId, "userId");
    if (maxTokens < 0) throw new IllegalArgumentException("maxTokens cannot be negative");
    if (retryCount < 0) throw new IllegalArgumentException("retryCount cannot be negative");
  }

  private static final class Identity {
    private static final String USER_ID = create();

    private static String create() {
      byte[] seed = MachineSeed.current().getBytes(StandardCharsets.UTF_8);
      long first = fnv(seed, 0xcbf29ce484222325L);
      long second = fnv(seed, 0x84222325cbf29ce4L);
      long sessionFirst = fnv(longBytes(System.nanoTime()), 0xcbf29ce484222325L);
      long sessionSecond = sessionFirst ^ 0x9e3779b97f4a7c15L;
      return "{\"device_id\":\"%016x%016x\",\"session_id\":\"%016x%016x\"}"
          .formatted(first, second, sessionFirst, sessionSecond);
    }

    private static long fnv(byte[] bytes, long offset) {
      long hash = offset;
      for (byte value : bytes) {
        hash ^= Byte.toUnsignedInt(value);
        hash *= 0x100000001b3L;
      }
      return hash;
    }

    private static byte[] longBytes(long value) {
      var bytes = new byte[8];
      for (int index = 0; index < bytes.length; index++) {
        bytes[index] = (byte) (value >>> (index * 8));
      }
      return bytes;
    }
  }
}
