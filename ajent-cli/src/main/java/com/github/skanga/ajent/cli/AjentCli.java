package com.github.skanga.ajent.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Ajent process entry point and top-level command dispatcher. */
public final class AjentCli {
  public static final String VERSION = "0.2.8";
  private static final int USAGE_ERROR = 2;
  private static final int SOFTWARE_ERROR = 70;
  private static final String USAGE = """
      ajent 0.2.8

      usage: ajent [subcommand] [options]

      subcommands:
        login             Authenticate (OAuth via claude.ai or API key)
        logout            Remove saved credentials
        status            Show current auth status
        airgap            Launch ajent on an air-gapped host via SSH tunnel
                          (`ajent airgap --help` for details)
        acp               Run as an ACP agent over stdio (for Zed et al.)
        mcp-serve         Serve ajent's native tools over MCP (stdio).
                          Point any MCP client at `ajent mcp-serve`.
        skills            List discovered skills with spec-lint diagnostics
                          (exit 1 on warnings — CI-friendly validate)
        version           Print the ajent version and exit
        help              Show this message

      options:
        -k, --key KEY       API-key override for this session
        -m, --model ID      Model id (e.g. claude-opus-4-5)
        -w, --workspace DIR Sandbox filesystem tools to this directory
                            (default: cwd). Tools refuse paths outside it.
                            Pass `--workspace /` to disable the gate.
            --sandbox MODE  Wrap bash/diagnostics in an OS-native sandbox
                            (Linux: bwrap, macOS: sandbox-exec).
                            MODE = auto (default: use if available),
                                   on  (require backend; fail otherwise),
                                   off (disable wrapping).
        -p, --profile MODE  ACP permission tier (Zed shows the prompts):
                                   ask     (default: prompt write/exec/net),
                                   minimal (also prompt reads),
                                   write   (never prompt reads).
            --provider P    LLM backend. anthropic (default, OAuth/Pro/Max)
                            or an OpenAI-compatible one: openai | groq |
                            openrouter | together | cerebras | ollama |
                            llama.cpp, or a raw host[:port] for any other
                            OpenAI-compatible server. Reads OPENAI_API_KEY
                            (or the provider-specific *_API_KEY) / -k for
                            the key; local backends need no key. Persisted
                            like -m. (Switch live in-app with Ctrl-P — the
                            picker has a "Custom host…" entry too.)
        -V, --version       Print the ajent version and exit.
        -h, --help          Show this message.
      """;

  private AjentCli() {}

  public static void main(String[] arguments) {
    int exitCode = run(arguments,
        new PrintStream(System.out, true, StandardCharsets.UTF_8),
        new PrintStream(System.err, true, StandardCharsets.UTF_8));
    if (exitCode != 0) System.exit(exitCode);
  }

  public static int run(String[] arguments, PrintStream stdout, PrintStream stderr) {
    CliArguments parsed = CliArguments.parse(arguments);
    if (parsed.badArgument().isPresent()) {
      stderr.print("unknown arg: " + parsed.badArgument().orElseThrow() + "\n\n");
      stderr.print(USAGE);
      return USAGE_ERROR;
    }
    if (parsed.subcommand() == CliArguments.Subcommand.HELP) {
      stderr.print(USAGE);
      return 0;
    }
    if (parsed.subcommand() == CliArguments.Subcommand.VERSION) {
      stdout.print("ajent " + VERSION + "\n");
      return 0;
    }
    String command = parsed.subcommand() == CliArguments.Subcommand.NONE
        ? "interactive mode" : parsed.subcommand().commandName();
    stderr.print("ajent: " + command + " is not implemented yet\n");
    return SOFTWARE_ERROR;
  }
}
