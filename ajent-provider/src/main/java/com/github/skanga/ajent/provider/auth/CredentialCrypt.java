package com.github.skanga.ajent.provider.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AgenTTY v1 credential envelope: HKDF-SHA256 plus AES-256-GCM. */
public final class CredentialCrypt {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final byte[] INFO =
      "agentty-credentials-v1".getBytes(StandardCharsets.UTF_8);
  private static final int SALT_SIZE = 16;
  private static final int NONCE_SIZE = 12;
  private static final int TAG_SIZE = 16;
  private static final SecureRandom RANDOM = new SecureRandom();

  private CredentialCrypt() {}

  public static boolean looksSealed(String value) {
    int index = 0;
    while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
    return index < value.length() && value.charAt(index) == '{'
        && value.contains("\"enc\"") && value.contains("aes-256-gcm");
  }

  public static Optional<String> seal(String plaintext, String machineSeed) {
    byte[] salt = new byte[SALT_SIZE];
    byte[] nonce = new byte[NONCE_SIZE];
    RANDOM.nextBytes(salt);
    RANDOM.nextBytes(nonce);
    return seal(plaintext, machineSeed, salt, nonce);
  }

  static Optional<String> seal(
      String plaintext, String machineSeed, byte[] salt, byte[] nonce) {
    if (salt.length == 0 || nonce.length != NONCE_SIZE) return Optional.empty();
    byte[] key = null;
    try {
      key = hkdfSha256(machineSeed.getBytes(StandardCharsets.UTF_8), salt, INFO, 32);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_SIZE * 8, nonce));
      byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      int ciphertextSize = encrypted.length - TAG_SIZE;
      ObjectNode envelope = JSON.createObjectNode();
      envelope.put("v", 1);
      envelope.put("enc", "aes-256-gcm");
      envelope.put("salt", encode(salt));
      envelope.put("nonce", encode(nonce));
      envelope.put("ct", encode(Arrays.copyOf(encrypted, ciphertextSize)));
      envelope.put("tag", encode(Arrays.copyOfRange(encrypted, ciphertextSize, encrypted.length)));
      return Optional.of(JSON.writeValueAsString(envelope));
    } catch (GeneralSecurityException | JsonProcessingException exception) {
      return Optional.empty();
    } finally {
      if (key != null) Arrays.fill(key, (byte) 0);
    }
  }

  public static Optional<String> unseal(String envelope, String machineSeed) {
    byte[] key = null;
    try {
      var root = JSON.readTree(envelope);
      if (root == null || !root.isObject()
          || !"aes-256-gcm".equals(root.path("enc").asText())) {
        return Optional.empty();
      }
      byte[] salt = decode(root.path("salt").asText());
      byte[] nonce = decode(root.path("nonce").asText());
      byte[] ciphertext = decode(root.path("ct").asText());
      byte[] tag = decode(root.path("tag").asText());
      if (salt.length == 0 || nonce.length != NONCE_SIZE || tag.length != TAG_SIZE) {
        return Optional.empty();
      }
      key = hkdfSha256(machineSeed.getBytes(StandardCharsets.UTF_8), salt, INFO, 32);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_SIZE * 8, nonce));
      byte[] combined = Arrays.copyOf(ciphertext, ciphertext.length + tag.length);
      System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);
      return Optional.of(new String(cipher.doFinal(combined), StandardCharsets.UTF_8));
    } catch (GeneralSecurityException | JsonProcessingException | IllegalArgumentException exception) {
      return Optional.empty();
    } finally {
      if (key != null) Arrays.fill(key, (byte) 0);
    }
  }

  static byte[] hkdfSha256(byte[] inputKey, byte[] salt, byte[] info, int length)
      throws GeneralSecurityException {
    Mac hmac = Mac.getInstance("HmacSHA256");
    hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
    byte[] pseudoRandomKey = hmac.doFinal(inputKey);
    try {
      byte[] result = new byte[length];
      byte[] previous = new byte[0];
      int offset = 0;
      for (int block = 1; offset < length; block++) {
        hmac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
        hmac.update(previous);
        hmac.update(info);
        hmac.update((byte) block);
        previous = hmac.doFinal();
        int count = Math.min(previous.length, length - offset);
        System.arraycopy(previous, 0, result, offset, count);
        offset += count;
      }
      Arrays.fill(previous, (byte) 0);
      return result;
    } finally {
      Arrays.fill(pseudoRandomKey, (byte) 0);
    }
  }

  private static String encode(byte[] value) {
    return Base64.getEncoder().encodeToString(value);
  }

  private static byte[] decode(String value) {
    return Base64.getDecoder().decode(value);
  }
}
