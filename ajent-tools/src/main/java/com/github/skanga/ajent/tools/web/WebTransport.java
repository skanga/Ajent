package com.github.skanga.ajent.tools.web;

import java.util.List;
import java.util.Map;

public interface WebTransport {
  record Request(String method, String url, Map<String, String> headers, String body) {
    public Request { headers = Map.copyOf(headers); }
  }
  record Response(int status, Map<String, List<String>> headers, String body, String error) {
    public Response { headers = Map.copyOf(headers); error = error == null ? "" : error; }
    public String header(String name) {
      return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
          .flatMap(entry -> entry.getValue().stream()).findFirst().orElse("");
    }
  }
  Response send(Request request);
}
