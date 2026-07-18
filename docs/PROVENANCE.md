# Provenance

Ajent is a clean Java port of AgenTTY 0.2.8 at commit `c7594d64020cfdacb10b6a0b2074bcedcc827bba`.

Reference-derived work currently includes:

| Ajent area | AgenTTY source |
|---|---|
| CLI parsing and text | `src/runtime/main.cpp` |
| FSM transition/ownership invariants | `include/agentty/io/fsm.hpp`, `tests/fsm_test.cpp` |
| Model capability inference | `include/agentty/domain/catalog.hpp`, `tests/model_caps_test.cpp` |
| Fuzzy edit matching | `src/tool/util/fuzzy_match.cpp`, `tests/fuzzy_match_smoke.cpp` |
| XML-in-JSON repair | `include/agentty/runtime/app/update/param_tag_repair.hpp`, `tests/param_tag_repair_test.cpp` |
| Model display labels | `include/agentty/ui/render/model_label.hpp`, `tests/model_label_test.cpp` |
| Conversation records and Anthropic message blocks | `include/agentty/domain/conversation.hpp`, `src/llm/anthropic/transport.cpp`, `tests/tool_result_budget_test.cpp` |
| OpenAI-compatible endpoints, HTTP request/list-model transport, wire messages, streamed events, salvage, and framing | `include/agentty/provider/openai/transport.hpp`, `include/agentty/provider/wire.hpp`, `src/provider/openai/transport.cpp`, `src/provider/selection.cpp`, `tests/openai_transport_test.cpp` |
| Ollama native/JSON-protocol HTTP transport, prompt/memory tiers, messages, options, model probing, streamed events, response extraction, and argument repair | `include/agentty/provider/ollama/transport.hpp`, `src/provider/ollama/transport.cpp`, `tests/ollama_transport_test.cpp` |
| Tool effects and path-aware scheduling | `include/agentty/tool/effects.hpp`, `include/agentty/tool/spec.hpp`, `src/runtime/app/cmd_factory.cpp`, `tests/scheduler_path_test.cpp` |
| Agent doom-loop breaker | `src/runtime/app/cmd_factory.cpp`, `tests/doom_loop_test.cpp` |
| Provider-backed task subagents, role tool filters, retries, turn/depth bounds, progress, and report harvesting | `include/agentty/tool/subagent.hpp`, `src/tool/subagent.cpp`, `src/tool/mcp_tools_backends.cpp`, `src/runtime/main.cpp` |
| Salvaged-call re-leak reducer | `src/runtime/app/cmd_factory.cpp`, `tests/salvage_dedup_test.cpp` |
| Canonical reducer transition traces | `src/runtime/app/update.cpp`, `src/runtime/app/update/stream.cpp`, `src/runtime/app/update/tool.cpp`, `include/agentty/runtime/msg.hpp` |
| Composer word deletion, attachment placeholders, and undo | `src/runtime/app/update/composer.cpp`, `src/runtime/composer_attachment.cpp`, `src/runtime/view/helpers.cpp`, `tests/composer_edit_test.cpp` |
| Workspace file/symbol indexing, scored matching, picker reducers, and lazy attachment expansion | `src/workspace/files.cpp`, `src/workspace/symbols.cpp`, `include/agentty/runtime/mention_palette.hpp`, `include/agentty/runtime/symbol_palette.hpp`, `src/runtime/app/update/mention.cpp`, `src/runtime/app/update/symbol.cpp`, `src/runtime/view/pickers.cpp` |
| Always-chip text paste, image sniff/path ingestion, smart clipboard routing, and OSC query fallback | `src/runtime/app/update/composer.cpp`, `src/io/clipboard.cpp`, `include/agentty/io/clipboard.hpp`, `src/runtime/composer_attachment.cpp`, `maya/src/terminal/ansi.cpp`, `maya/src/app/app.cpp` |
| Structured bounded-LCS hunks, unified rendering/reconstruction, and review reducer | `src/diff/diff.cpp`, `include/agentty/diff/diff.hpp`, `src/runtime/app/update/diff.cpp`, `src/runtime/view/diff_review.cpp` |
| Partial-stream todo projection, parse throttling, plan synchronization, and settled updates | `src/runtime/app/update/stream_preview.cpp`, `src/runtime/app/update/stream.cpp`, `src/runtime/app/update/tool.cpp` |
| Rich per-tool body discrimination, streaming bounds, semantic extraction, and row rendering | `include/agentty/runtime/view/thread/turn/agent_timeline/tool_body_preview.hpp`, `src/runtime/view/thread/turn/agent_timeline/tool_body_preview.cpp`, `src/runtime/view/thread/turn/agent_timeline/tool_args.cpp`, `maya/include/maya/widget/tool_body_preview.hpp` |
| Code-block discovery, platform shell selection, POSIX inherited-terminal execution, merged live tee, bounded capture, signal handling, and result workflow | `src/runtime/code_block_picker.cpp`, `src/runtime/app/update/codeblock.cpp`, `src/runtime/view/pickers.cpp` |
| Rate-paced text reveal, deterministic scramble, RGB trail, ghost/sweep front, eager-row structure protection, and live end caret | `maya/include/maya/anim/text_reveal.hpp`, `maya/tests/test_motion.cpp`, `maya/src/widget/markdown/streaming/reveal_fx.cpp`, `src/runtime/view/thread/turn/turn.cpp` |
| CLI reference fixtures | downloaded AgenTTY 0.2.8 Windows binary plus `src/runtime/main.cpp` |

The reference checkout and binary remain local under ignored `agentty/` and are not part of Ajent's Git history. Raw retained fixtures are small behavioral outputs and carry source commit and binary hashes in the reference manifests.

Submodule behavior from Maya, acp-cpp, and mcp-cpp will be recorded here when those surfaces are ported. Their license texts must be audited before any derived assets or substantial code are incorporated.
