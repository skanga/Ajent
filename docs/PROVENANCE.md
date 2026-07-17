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
| CLI reference fixtures | downloaded AgenTTY 0.2.8 Windows binary plus `src/runtime/main.cpp` |

The reference checkout and binary remain local under ignored `agentty/` and are not part of Ajent's Git history. Raw retained fixtures are small behavioral outputs and carry source commit and binary hashes in the reference manifests.

Submodule behavior from Maya, acp-cpp, and mcp-cpp will be recorded here when those surfaces are ported. Their license texts must be audited before any derived assets or substantial code are incorporated.
