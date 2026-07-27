package com.github.skanga.ajent.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CredentialCryptTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SEED = "machine\u001fuser\u001fajent-credentials-v1";
  private static final String PLAINTEXT =
      "{\"method\":\"api_key\",\"access_token\":\"secret\"}";

  @Test
  void fixedInputsMatchIndependentHkdfAndAesGcmVector() throws Exception {
    byte[] salt = sequence(0, 16);
    byte[] nonce = sequence(16, 12);

    String envelope = CredentialCrypt.seal(PLAINTEXT, SEED, salt, nonce).orElseThrow();
    var json = JSON.readTree(envelope);

    assertThat(json.path("v").intValue()).isEqualTo(1);
    assertThat(json.path("enc").textValue()).isEqualTo("aes-256-gcm");
    assertThat(json.path("salt").textValue()).isEqualTo("AAECAwQFBgcICQoLDA0ODw==");
    assertThat(json.path("nonce").textValue()).isEqualTo("EBESExQVFhcYGRob");
    assertThat(json.path("ct").textValue())
        .isEqualTo("iVA3W1JuGwx5t1bGOhpHMwNKE3/5DMR266ocw1ImnaQIIdwxo79vRCQTuBg=");
    assertThat(json.path("tag").textValue()).isEqualTo("Wdrw8oJXeEDfwwn9hzVLMg==");
    assertThat(CredentialCrypt.unseal(envelope, SEED)).contains(PLAINTEXT);
  }

  @Test
  void detectsSealedShapeAndRejectsTamperWrongSeedAndMalformedFields() throws Exception {
    String envelope = CredentialCrypt.seal(
        PLAINTEXT, SEED, sequence(0, 16), sequence(16, 12)).orElseThrow();
    assertThat(CredentialCrypt.looksSealed(" \n" + envelope)).isTrue();
    assertThat(CredentialCrypt.looksSealed(PLAINTEXT)).isFalse();
    assertThat(CredentialCrypt.looksSealed("not json")).isFalse();
    assertThat(CredentialCrypt.looksSealed("")).isFalse();
    assertThat(CredentialCrypt.looksSealed(" \n\t")).isFalse();
    assertThat(CredentialCrypt.unseal(envelope, "other-machine")).isEmpty();

    var tampered = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(envelope);
    byte[] tag = Base64.getDecoder().decode(tampered.path("tag").textValue());
    tag[0] ^= 1;
    tampered.put("tag", Base64.getEncoder().encodeToString(tag));
    assertThat(CredentialCrypt.unseal(tampered.toString(), SEED)).isEmpty();
    tampered.put("enc", "other");
    assertThat(CredentialCrypt.unseal(tampered.toString(), SEED)).isEmpty();
    assertThat(CredentialCrypt.unseal("{bad}", SEED)).isEmpty();

    var malformed = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(envelope);
    malformed.put("salt", "");
    assertThat(CredentialCrypt.unseal(malformed.toString(), SEED)).isEmpty();
    malformed = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(envelope);
    malformed.put("nonce", "");
    assertThat(CredentialCrypt.unseal(malformed.toString(), SEED)).isEmpty();
    malformed = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(envelope);
    malformed.put("tag", "");
    assertThat(CredentialCrypt.unseal(malformed.toString(), SEED)).isEmpty();

    assertThat(CredentialCrypt.seal(
        PLAINTEXT, SEED, new byte[0], sequence(0, 12))).isEmpty();
    assertThat(CredentialCrypt.seal(
        PLAINTEXT, SEED, sequence(0, 16), new byte[0])).isEmpty();
  }

  private static byte[] sequence(int start, int size) {
    byte[] result = new byte[size];
    for (int index = 0; index < size; index++) result[index] = (byte) (start + index);
    return result;
  }
}
