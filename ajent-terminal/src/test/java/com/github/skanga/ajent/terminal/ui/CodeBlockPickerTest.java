package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CodeBlockPickerTest {
  @Test void extractsBothFencesIndentCrLfInfoWordAndUnterminatedTail() {
    List<CodeBlockPicker.Block> blocks = CodeBlockPicker.extract("""
        prose
          ```BASH title\r
        echo one\r
        ```   \r
        ~~~python
        print(2)
        ~~~
        ``` sh
        echo tail
        """);
    assertThat(blocks).containsExactly(
        new CodeBlockPicker.Block("bash", "echo one", 1),
        new CodeBlockPicker.Block("python", "print(2)", 1),
        new CodeBlockPicker.Block("sh", "echo tail", 1));
  }

  @Test void stripsOnlyUniformDollarOrContinuationPromptsAndDropsEmptyBlocks() {
    assertThat(CodeBlockPicker.extract("```console\n$ one\n> two\n\n```"))
        .containsExactly(new CodeBlockPicker.Block("console", "one\ntwo", 2));
    assertThat(CodeBlockPicker.extract("```sh\n$ one\noutput\n```").getFirst().body())
        .isEqualTo("$ one\noutput");
    assertThat(CodeBlockPicker.extract("```sh\n\n```" )).isEmpty();
  }

  @Test void shellClassificationMatchesBothPlatformsAndPowerShellEncoding() {
    assertThat(CodeBlockPicker.shell("", true)).isEqualTo(CodeBlockPicker.Shell.CMD);
    assertThat(CodeBlockPicker.shell("bash", true)).isEqualTo(CodeBlockPicker.Shell.CMD);
    assertThat(CodeBlockPicker.shell("ps1", true)).isEqualTo(CodeBlockPicker.Shell.POWERSHELL);
    assertThat(CodeBlockPicker.shell("cmd", false)).isEqualTo(CodeBlockPicker.Shell.NONE);
    assertThat(CodeBlockPicker.shell("sh", false)).isEqualTo(CodeBlockPicker.Shell.POSIX);
    assertThat(CodeBlockPicker.shell("python", true)).isEqualTo(CodeBlockPicker.Shell.NONE);
    assertThat(CodeBlockPicker.commandFor(CodeBlockPicker.Shell.POWERSHELL, "Write-Host 'hi'"))
        .startsWith("powershell -NoProfile -ExecutionPolicy Bypass -EncodedCommand ");
    assertThat(CodeBlockPicker.commandFor(CodeBlockPicker.Shell.CMD, "echo hi"))
        .isEqualTo("echo hi");
  }

  @Test void findsNewestAssistantThatActuallyContainsBlocks() {
    var messages = List.of(
        new Message(Role.ASSISTANT, "```sh\necho old\n```", List.of(), List.of()),
        new Message(Role.USER, "ignore", List.of(), List.of()),
        new Message(Role.ASSISTANT, "prose only", List.of(), List.of()));
    assertThat(CodeBlockPicker.latestAssistantBlocks(messages).orElseThrow().getFirst().body())
        .isEqualTo("echo old");
    assertThat(CodeBlockPicker.latestAssistantBlocks(List.of())).isEmpty();
  }

  @Test void pickerClampsMovesSupportsDigitsAndScrollsResult() {
    var blocks = List.of(new CodeBlockPicker.Block("sh", "one", 1),
        new CodeBlockPicker.Block("sh", "two\nthree", 2));
    CodeBlockPicker.State state = CodeBlockPicker.open(blocks);
    state = CodeBlockPicker.move(state, 20);
    assertThat(CodeBlockPicker.selected(state, -1)).contains(blocks.get(1));
    assertThat(CodeBlockPicker.selected(state, 0)).contains(blocks.getFirst());
    assertThat(CodeBlockPicker.selected(state, 9)).isEmpty();
    assertThat(CodeBlockPicker.move(state, -20)).isEqualTo(new CodeBlockPicker.Open(blocks, 0));
    assertThat(CodeBlockPicker.open(List.of())).isEqualTo(new CodeBlockPicker.Closed());
    var result = new CodeBlockPicker.Result("cmd", "out", 0, false);
    assertThat(CodeBlockPicker.move(result, 10)).isEqualTo(result);
    var longResult = new CodeBlockPicker.Result("cmd",
        java.util.stream.IntStream.range(0, 20).mapToObj(String::valueOf)
            .collect(java.util.stream.Collectors.joining("\n")), 0, false);
    assertThat(CodeBlockPicker.move(longResult, 20))
        .isEqualTo(new CodeBlockPicker.Result(longResult.command(), longResult.output(),
            0, false, 6));
    assertThat(CodeBlockPicker.move(result, -1)).isEqualTo(result);
    assertThat(CodeBlockPicker.close(state)).isEqualTo(new CodeBlockPicker.Closed());
    assertThat(blocks.get(1).preview()).isEqualTo("two");
  }
}
