package com.github.skanga.ajent.tools.memory;

import java.util.List;
import java.util.OptionalInt;

public interface MemoryStore {
  record AppendRequest(String text, String scope, boolean pinned, List<String> tags,
                       String supersedesId) {
    public AppendRequest { tags = List.copyOf(tags); }
  }
  record AppendResult(String id, boolean deduped, String note, int rolled, String error) {}
  record Record(String id, String text) {}

  List<String> scopes();
  AppendResult append(AppendRequest request);
  int forgetById(String id);
  int forgetBySubstring(String substring);
  List<Record> previewForget(String substring);
  OptionalInt wipe(String scope);
}
