package com.github.skanga.ajent.tools.args;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArgReaderTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void coercesIntegerAndBooleanValuesLikeMcpCpp() {
    assertThat(reader("offset", 7).integer("offset", 1)).isEqualTo(7);
    assertThat(reader("offset", "7").integer("offset", 1)).isEqualTo(7);
    assertThat(reader("offset", 7.9).integer("offset", 1)).isEqualTo(7);
    assertThat(reader("offset", "nope").integer("offset", 1)).isEqualTo(1);
    assertThat(new ArgReader(JSON.createObjectNode()).integer("offset", 1)).isEqualTo(1);

    for (Object value : List.of(true, "true", "True", "yes", "1", 1)) {
      assertThat(reader("replace_all", value).bool("replace_all", false)).isTrue();
    }
    for (Object value : List.of(false, "false", "no", "0", 0)) {
      assertThat(reader("replace_all", value).bool("replace_all", true)).isFalse();
    }
    assertThat(reader("replace_all", "unknown").bool("replace_all", true)).isTrue();
  }

  @Test
  void stringCoercionJoinsArraysAndDumpsOtherJsonValues() {
    assertThat(reader("content", "hi").string("content", "")).isEqualTo("hi");
    ObjectNode args = JSON.createObjectNode();
    ArrayNode array = args.putArray("content");
    array.add("a").add("b").add(3);
    assertThat(new ArgReader(args).string("content", "")).isEqualTo("a\nb\n3");
    assertThat(reader("content", 42).string("content", "")).isEqualTo("42");
    assertThat(new ArgReader(JSON.createObjectNode()).string("content", "def"))
        .isEqualTo("def");
  }

  @Test
  void everyWeakModelAliasResolvesAndCanonicalKeyWins() {
    aliases("command", "ls", "cmd", "shell", "script", "run", "cmdline", "shell_command");
    aliases("path", "/tmp/x", "file", "filepath", "filename", "file_path", "dir",
        "directory", "target", "pathname");
    aliases("old_string", "needle", "old_text", "old", "search", "find", "from", "old_str");
    aliases("new_string", "hay", "new_text", "new", "replace", "replacement", "to", "new_str");
    aliases("pattern", "foo.*", "query", "q", "regex", "search", "term", "glob", "match", "pat");
    aliases("query", "how to", "q", "search", "term", "text", "prompt", "question");
    aliases("url", "https://x", "uri", "link", "address", "href");
    aliases("content", "body", "file_text", "text", "file_content", "contents", "body", "data", "code");
    aliases("cd", "dir", "cwd", "workdir", "working_directory", "directory");

    ObjectNode both = JSON.createObjectNode().put("command", "right").put("cmd", "wrong");
    assertThat(new ArgReader(both).string("command", "")).isEqualTo("right");
    assertThat(reader("path", "/a").string("file_path", "")).isEqualTo("/a");
    assertThat(reader("file", "/b").string("file_path", "")).isEqualTo("/b");
  }

  @Test
  void aliasingAndCoercionCompose() {
    assertThat(reader("start_line", "12").integer("offset", 1)).isEqualTo(12);
    ObjectNode args = JSON.createObjectNode();
    args.putArray("cmd").add("echo").add("hi");
    assertThat(new ArgReader(args).string("command", "")).isEqualTo("echo\nhi");
    assertThat(reader("file", "x").requiredString("path")).contains("x");
    assertThat(reader("file", "").requiredString("path")).isEmpty();
  }

  private static ArgReader reader(String key, Object value) {
    ObjectNode node = JSON.createObjectNode();
    node.set(key, JSON.valueToTree(value));
    return new ArgReader(node);
  }

  private static void aliases(String canonical, String value, String... aliases) {
    for (String alias : aliases) {
      assertThat(reader(alias, value).string(canonical, ""))
          .as("%s alias for %s", alias, canonical).isEqualTo(value);
    }
  }
}
