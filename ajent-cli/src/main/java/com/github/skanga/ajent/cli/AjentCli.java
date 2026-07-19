package com.github.skanga.ajent.cli;

import java.io.PrintStream;
import java.io.BufferedReader;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
    int exitCode = run(arguments, processStream(System.out), processStream(System.err));
    if (exitCode != 0) System.exit(exitCode);
  }

  static PrintStream processStream(OutputStream output) {
    OutputStream translated = System.lineSeparator().equals("\r\n")
        ? new WindowsNewlineOutputStream(output) : output;
    return new PrintStream(translated, true, StandardCharsets.UTF_8);
  }

  public static int run(String[] arguments, PrintStream stdout, PrintStream stderr) {
    return run(arguments, new BufferedReader(
        new InputStreamReader(System.in, StandardCharsets.UTF_8)), stdout, stderr,
        new CommandServices() {
          @Override public int login(BufferedReader input, PrintStream out, PrintStream err) {
            return AuthCommands.systemDefault().login(input, out, err);
          }
          @Override public int logout(PrintStream out, PrintStream err) {
            return AuthCommands.systemDefault().logout(out, err);
          }
          @Override public int status(PrintStream out) {
            return AuthCommands.systemDefault().status(out);
          }
          @Override public int skills(PrintStream out) {
            return SkillCommands.systemDefault().list(out);
          }
          @Override public int mcpServe(CliArguments arguments, BufferedReader input,
                                        PrintStream out, PrintStream err) {
            return McpServeCommand.systemDefault().run(arguments, input, out, err);
          }
          @Override public int acp(CliArguments arguments, BufferedReader input,
                                   PrintStream out, PrintStream err) {
            return AcpCommand.systemDefault().run(arguments, input, out, err);
          }
          @Override public int airgap(CliArguments arguments, PrintStream out, PrintStream err) {
            return AirgapCommand.systemDefault().run(arguments.airgapArguments(), out, err);
          }
          @Override public int interactive(CliArguments arguments, PrintStream err) {
            return InteractiveCommand.systemDefault().run(arguments, err);
          }
        });
  }

  static int run(String[] arguments, BufferedReader input, PrintStream stdout,
                 PrintStream stderr, CommandServices commands) {
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
    if (parsed.subcommand() == CliArguments.Subcommand.LOGIN) {
      return commands.login(input, stdout, stderr);
    }
    if (parsed.subcommand() == CliArguments.Subcommand.LOGOUT)
      return commands.logout(stdout, stderr);
    if (parsed.subcommand() == CliArguments.Subcommand.STATUS)
      return commands.status(stdout);
    if (parsed.subcommand() == CliArguments.Subcommand.SKILLS)
      return commands.skills(stdout);
    if (parsed.subcommand() == CliArguments.Subcommand.MCP_SERVE)
      return commands.mcpServe(parsed, input, stdout, stderr);
    if (parsed.subcommand() == CliArguments.Subcommand.ACP)
      return commands.acp(parsed, input, stdout, stderr);
    if (parsed.subcommand() == CliArguments.Subcommand.AIRGAP)
      return commands.airgap(parsed, stdout, stderr);
    if (parsed.subcommand() == CliArguments.Subcommand.NONE)
      return commands.interactive(parsed, stderr);
    stderr.print("ajent: " + parsed.subcommand().commandName() + " is not implemented yet\n");
    return SOFTWARE_ERROR;
  }

  interface CommandServices {
    int login(BufferedReader input, PrintStream output, PrintStream error);
    int logout(PrintStream output, PrintStream error);
    int status(PrintStream output);
    int skills(PrintStream output);
    int mcpServe(CliArguments arguments, BufferedReader input,
                 PrintStream output, PrintStream error);
    int acp(CliArguments arguments, BufferedReader input,
            PrintStream output, PrintStream error);
    int airgap(CliArguments arguments, PrintStream output, PrintStream error);
    int interactive(CliArguments arguments, PrintStream error);
  }

  private static final class WindowsNewlineOutputStream extends FilterOutputStream {
    private int previous = -1;

    private WindowsNewlineOutputStream(OutputStream output) { super(output); }

    @Override public void write(int value) throws IOException {
      if (value == '\n' && previous != '\r') out.write('\r');
      out.write(value);
      previous = value;
    }

    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
      for (int index = offset; index < offset + length; index++) write(bytes[index] & 0xff);
    }
  }
}
