package com.github.skanga.ajent.tools.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ToolCatalogTest {
  private record Row(String name, int effects, boolean eager, long seconds, int characters,
                     TruncationStrategy strategy) {}

  @Test
  void catalogExactlyMatchesAjentOrderAndOperationalMetadata() {
    List<Row> expected = List.of(
        row("read",1,false,20,80000,"HEAD"), row("edit",3,true,20,40000,"HEAD"),
        row("write",2,true,20,40000,"HEAD"), row("bash",8,true,0,30000,"TAIL"),
        row("grep",1,false,45,30000,"HEAD_TAIL"), row("glob",1,false,30,25000,"HEAD"),
        row("list_dir",1,false,20,25000,"HEAD"), row("todo",0,true,5,0,"HEAD"),
        row("web_fetch",4,false,30,30000,"HEAD"),
        row("web_search",4,false,20,25000,"HEAD_TAIL"),
        row("find_definition",1,false,30,25000,"HEAD_TAIL"),
        row("diagnostics",8,false,0,30000,"TAIL"),
        row("git_status",1,false,20,30000,"HEAD_TAIL"),
        row("git_diff",1,false,20,60000,"HEAD_TAIL"),
        row("git_log",1,false,20,30000,"HEAD_TAIL"),
        row("git_commit",2,true,30,0,"HEAD"),
        row("remember",2,false,5,2000,"HEAD"), row("forget",2,false,5,2000,"HEAD"),
        row("wipe_memory",2,false,5,2000,"HEAD"),
        row("task",8,false,0,40000,"HEAD_TAIL"),
        row("skill",1,false,10,64000,"HEAD"),
        row("search_docs",5,false,60,30000,"HEAD_TAIL"),
        row("repo_map",1,false,30,60000,"HEAD"));

    assertThat(ToolCatalog.all()).extracting(spec -> new Row(
        spec.name(), spec.effects().bits(), spec.eagerInputStreaming(),
        spec.timeout().toSeconds(), spec.maxOutputCharacters(), spec.truncationStrategy()))
        .containsExactlyElementsOf(expected);
    assertThat(ToolCatalog.all()).isUnmodifiable();
  }

  @Test
  void kindAndNameAreBijectiveAndLookupIsExplicit() {
    assertThat(ToolCatalog.all()).hasSameSizeAs(ToolKind.values());
    for (ToolSpec spec : ToolCatalog.all()) {
      assertThat(ToolCatalog.byName(spec.name())).contains(spec);
      assertThat(ToolCatalog.byKind(spec.kind())).isEqualTo(spec);
    }
    assertThat(ToolCatalog.byName("nonexistent")).isEmpty();
  }

  @Test
  void taskKeepsExecPermissionButSchedulesAsComposableReadAndNetwork() {
    ToolSpec task = ToolCatalog.byName("task").orElseThrow();
    assertThat(task.effects().bits()).isEqualTo(8);
    assertThat(ToolCatalog.schedulingEffects(task).bits()).isEqualTo(5);
    assertThat(ToolCatalog.schedulingEffects(ToolCatalog.byName("write").orElseThrow()))
        .isEqualTo(ToolCatalog.byName("write").orElseThrow().effects());
  }

  private static Row row(
      String name, int effects, boolean eager, long seconds, int characters, String strategy) {
    return new Row(name, effects, eager, seconds, characters,
        TruncationStrategy.valueOf(strategy));
  }
}
