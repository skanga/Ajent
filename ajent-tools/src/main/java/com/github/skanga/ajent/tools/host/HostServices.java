package com.github.skanga.ajent.tools.host;

import com.github.skanga.ajent.domain.CancellationSignal;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class HostServices {
  private HostServices() {}

  public record TodoItem(String content, String status) {}
  @FunctionalInterface public interface TodoSink { void set(List<TodoItem> items); }

  public record SkillResolution(Optional<String> body, String error) {
    public SkillResolution { body = body == null ? Optional.empty() : body; error = error == null ? "" : error; }
  }
  @FunctionalInterface public interface SkillResolver { SkillResolution load(String name); }

  public record DocQuery(String query, int limit) {}
  public record DocHit(String source, String path, int lineStart, int lineEnd,
                       double score, String text) {}
  public record DocResponse(List<DocHit> hits, String mode, String error) {
    public DocResponse { hits = List.copyOf(hits); mode = mode == null ? "" : mode; error = error == null ? "" : error; }
  }
  @FunctionalInterface public interface DocRetriever { DocResponse retrieve(DocQuery query); }

  public record SubagentRequest(String prompt, String agentType) {}
  public record SubagentResponse(String report, boolean error) {}
  public interface SubagentRunner {
    boolean available();
    SubagentResponse run(SubagentRequest request);
    default SubagentResponse run(SubagentRequest request, CancellationSignal cancellation) {
      return run(request);
    }
    default SubagentResponse run(SubagentRequest request, CancellationSignal cancellation,
                                 Consumer<String> progress) {
      return run(request, cancellation);
    }
  }
}
