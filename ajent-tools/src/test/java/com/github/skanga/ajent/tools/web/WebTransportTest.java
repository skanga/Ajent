package com.github.skanga.ajent.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebTransportTest {
  @Test void responseNormalizesNullErrorAndLooksUpHeadersCaseInsensitively() {
    var values = new ArrayList<>(List.of("application/json"));
    var headers = new LinkedHashMap<String, List<String>>();
    headers.put("Content-Type", values);

    var response = new WebTransport.Response(200, headers, "{}", null);
    headers.clear();

    assertThat(response.error()).isEmpty();
    assertThat(response.header("content-type")).isEqualTo("application/json");
    assertThat(response.header("missing")).isEmpty();
    assertThat(response.headers()).containsOnlyKeys("Content-Type");
  }
}
