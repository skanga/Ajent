package com.github.skanga.ajent.tools.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Ordered, composable transformations over structured retrieval context. */
public final class RagPipeline {
  @FunctionalInterface
  public interface Stage {
    RagContext process(RagContext context);
    default String name() { return getClass().getSimpleName(); }
  }

  public record NormalizeConfig(boolean lowercase, boolean normalizeWhitespace) {}

  public record RetrieveStage(KnowledgeSource source, int poolLimit) implements Stage {
    @Override public String name() { return "retrieve"; }
    @Override public RagContext process(RagContext context) {
      return RagContext.fromHits(context.query(), source.retrieve(context.query(), poolLimit));
    }
  }

  public record RerankStage(int outputLimit, RagReranker.Weights weights) implements Stage {
    @Override public String name() { return "rerank"; }
    @Override public RagContext process(RagContext context) {
      List<RagCorpus.Hit> hits = context.chunks().stream().map(RagContext.ContextChunk::hit).toList();
      return RagContext.fromHits(context.query(),
          RagReranker.rerank(context.query(), hits, outputLimit, weights));
    }
  }

  public record CompressStage(int targetCharacters) implements Stage {
    @Override public String name() { return "compress"; }
    @Override public RagContext process(RagContext context) {
      List<RagContext.ContextChunk> compressed = context.chunks().stream().map(value -> {
        RagChunk chunk = value.hit().chunk();
        return chunk == null ? value : new RagContext.ContextChunk(value.hit(),
            RagReranker.compress(context.query(), chunk.text(), targetCharacters));
      }).toList();
      return new RagContext(context.query(), compressed, context.confidence());
    }
  }

  public record MmrStage(int outputLimit, double lambda) implements Stage {
    @Override public String name() { return "mmr"; }
    @Override public RagContext process(RagContext context) {
      List<RagCorpus.Hit> hits = context.chunks().stream().map(RagContext.ContextChunk::hit).toList();
      return RagContext.fromHits(context.query(), RagReranker.mmrDiversify(hits, outputLimit, lambda));
    }
  }

  public record NormalizeQueryStage(NormalizeConfig config) implements Stage {
    public NormalizeQueryStage() { this(new NormalizeConfig(true, true)); }
    @Override public String name() { return "normalize"; }
    @Override public RagContext process(RagContext context) {
      String query = config.lowercase() ? context.query().toLowerCase(Locale.ROOT) : context.query();
      if (config.normalizeWhitespace()) query = normalizeWhitespace(query);
      return new RagContext(query, context.chunks(), context.confidence());
    }

    private static String normalizeWhitespace(String query) {
      var result = new StringBuilder();
      boolean inSpace = true;
      for (int index = 0; index < query.length(); index++) {
        char character = query.charAt(index);
        boolean whitespace = character == ' ' || character == '\t'
            || character == '\n' || character == '\r';
        if (whitespace) {
          if (!inSpace && !result.isEmpty()) {
            result.append(' ');
            inSpace = true;
          }
        } else {
          result.append(character);
          inSpace = false;
        }
      }
      if (!result.isEmpty() && result.charAt(result.length() - 1) == ' ')
        result.setLength(result.length() - 1);
      return result.toString();
    }
  }

  private final List<Stage> stages = new ArrayList<>();

  public RagPipeline add(Stage stage) {
    if (stage != null) stages.add(stage);
    return this;
  }

  public int stageCount() { return stages.size(); }

  public RagContext run(RagContext seed) {
    RagContext context = seed;
    for (Stage stage : stages) context = stage.process(context);
    return context;
  }
}
