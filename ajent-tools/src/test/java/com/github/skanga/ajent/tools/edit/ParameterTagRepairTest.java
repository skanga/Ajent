package com.github.skanga.ajent.tools.edit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ParameterTagRepairTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test void repairsWireShapeWithSingularEditKey() {
    ObjectNode args = object("{\"display_description\":\"probe edit\",\"edit\":\"\\n<parameter name=\\\"old_text\\\">    constexpr int k = 1;\\n\",\"new_text\":\"    constexpr int k = 2;\\n\",\"path\":\"src/foo.cpp\"}");
    assertThat(ParameterTagRepair.repair("edit", args)).isTrue();
    assertThat(args.has("edit")).isFalse();
    assertThat(args.path("edits").size()).isOne();
    assertThat(args.path("edits").path(0).path("old_text").textValue()).isEqualTo("    constexpr int k = 1;\n");
    assertThat(args.path("edits").path(0).path("new_text").textValue()).isEqualTo("    constexpr int k = 2;\n");
    assertThat(args.path("path").textValue()).isEqualTo("src/foo.cpp");
    assertThat(args.path("display_description").textValue()).isEqualTo("probe edit");
  }

  @Test void extractsOldAndNewTextFromOneStringIncludingClosingTags() {
    ObjectNode args = object("{\"path\":\"a.cpp\",\"edits\":\"<parameter name=\\\"old_text\\\">AAA</parameter><parameter name=\\\"new_text\\\">BBB</parameter>\"}");
    assertThat(ParameterTagRepair.repair("edit", args)).isTrue();
    assertThat(args.path("edits").path(0).path("old_text").textValue()).isEqualTo("AAA");
    assertThat(args.path("edits").path(0).path("new_text").textValue()).isEqualTo("BBB");
  }

  @Test void wellFormedArgumentsAreUnchanged() {
    ObjectNode args = object("{\"path\":\"a.cpp\",\"edits\":[{\"old_text\":\"X\",\"new_text\":\"Y\"}]}");
    ObjectNode before = args.deepCopy();
    assertThat(ParameterTagRepair.repair("edit", args)).isFalse();
    assertThat(args).isEqualTo(before);
  }

  @Test void repairsWriteContentLeak() {
    ObjectNode args = object("{\"path\":\"new.txt\",\"write\":\"<parameter name=\\\"content\\\">hello\\nworld\\n\"}");
    assertThat(ParameterTagRepair.repair("write", args)).isTrue();
    assertThat(args.path("content").textValue()).isEqualTo("hello\nworld\n");
    assertThat(args.path("path").textValue()).isEqualTo("new.txt");
  }

  @Test void markersNestedInsideValidEditsDoNotCorruptThem() {
    ObjectNode args = object("{\"path\":\"a.cpp\",\"edits\":[{\"old_text\":\"real old\",\"new_text\":\"// see <parameter name=\\\"old_text\\\"> docs\"}]}");
    ParameterTagRepair.repair("edit", args);
    assertThat(args.path("edits").path(0).path("old_text").textValue()).isEqualTo("real old");
  }

  @Test void rejectsUnsupportedToolsAndMalformedOrEmptyTags() {
    assertThat(ParameterTagRepair.repair("read", object("{\"x\":\"<parameter name=\\\"path\\\">a\"}"))).isFalse();
    assertThat(ParameterTagRepair.repair("edit", object("{\"x\":\"plain\"}"))).isFalse();
    assertThat(ParameterTagRepair.repair("edit", object("{\"x\":\"<parameter name=\\\"broken\"}"))).isFalse();
    assertThat(ParameterTagRepair.repair("edit", object("{\"x\":\"<parameter name=\\\"new_text\\\">new\"}"))).isFalse();
    assertThat(ParameterTagRepair.repair("write", object("{\"x\":\"<parameter name=\\\"path\\\">a\"}"))).isFalse();
  }

  @Test void aliasesLineHintsAndDuplicateTagsFollowReferencePrecedence() {
    ObjectNode args = object("{\"file_path\":\"a.cpp\",\"old_string\":\"clean\",\"x\":\"<parameter name=\\\"old_text\\\">ignored</parameter><parameter name=\\\"new_string\\\">new</parameter><parameter name=\\\"line\\\">12</parameter><parameter name=\\\"line\\\">99</parameter>\"}");
    assertThat(ParameterTagRepair.repair("edit", args)).isTrue();
    assertThat(args.path("path").textValue()).isEqualTo("a.cpp");
    assertThat(args.path("edits").path(0).path("old_text").textValue()).isEqualTo("clean");
    assertThat(args.path("edits").path(0).path("new_text").textValue()).isEqualTo("new");
    assertThat(args.path("edits").path(0).path("line").intValue()).isEqualTo(12);
  }

  @Test void numericTopLevelLineIsPreservedAndMalformedTaggedLineIsIgnored() {
    ObjectNode numeric = object("{\"path\":\"a\",\"line\":7,\"x\":\"<parameter name=\\\"old_text\\\">old\"}");
    assertThat(ParameterTagRepair.repair("edit", numeric)).isTrue();
    assertThat(numeric.path("edits").path(0).path("line").intValue()).isEqualTo(7);
    ObjectNode malformed = object("{\"path\":\"a\",\"x\":\"<parameter name=\\\"old_text\\\">old</parameter><parameter name=\\\"line\\\">NaN\"}");
    assertThat(ParameterTagRepair.repair("edit", malformed)).isTrue();
    assertThat(malformed.path("edits").path(0).has("line")).isFalse();
  }

  @Test void writeAliasesAndDisplayDescriptionArePreserved() {
    ObjectNode args = object("{\"filename\":\"new.txt\",\"display_description\":\"write it\",\"body\":\"clean body\",\"x\":\"<parameter name=\\\"content\\\">tagged body\"}");
    assertThat(ParameterTagRepair.repair("write", args)).isTrue();
    assertThat(args.path("path").textValue()).isEqualTo("new.txt");
    assertThat(args.path("content").textValue()).isEqualTo("clean body");
    assertThat(args.path("display_description").textValue()).isEqualTo("write it");
  }

  private static ObjectNode object(String json) {
    try { return (ObjectNode) JSON.readTree(json); }
    catch (Exception exception) { throw new AssertionError(exception); }
  }
}
