package com.github.skanga.ajent.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** SSH reverse-SOCKS launcher for running Ajent on an air-gapped host. */
final class AirgapCommand {
  private static final int USAGE_ERROR = 64;
  private static final int CLIPBOARD_RELAY_PORT = 1175;
  private static final List<String> TERMINAL_MARKERS = List.of(
      "TERM_PROGRAM", "TERM_PROGRAM_VERSION", "KITTY_WINDOW_ID", "ALACRITTY_LOG",
      "ALACRITTY_WINDOW_ID", "GHOSTTY_RESOURCES_DIR", "WEZTERM_EXECUTABLE", "WT_SESSION",
      "KONSOLE_VERSION", "VTE_VERSION", "ITERM_SESSION_ID", "TERM", "COLORTERM");
  private static final String USAGE = """
      usage: ajent airgap [--setup] [--remote-agentty PATH] <user@host>
             ajent airgap [--setup] <user@host> --acp [acp flags…]

        Opens an SSH session to <user@host> with `-R 1080`, which makes
        OpenSSH expose a SOCKS5 proxy on the remote's localhost:1080.
        Connections to it are tunnelled back through SSH and dialed by
        this laptop, so the remote ajent — pointed at the proxy via
        AGENTTY_SOCKS_PROXY — can reach every destination it needs (chat,
        OAuth refresh, web tools) over a single tunnel.  TLS / cert
        verification stay pinned on the real upstream end-to-end, so
        the network path between laptop and remote can't MITM you.

        Trust boundary (read this before --setup): the remote ends up
        with a copy of ~/.config/agentty/credentials.json, which contains
        your OAuth refresh token (or API key). A compromised remote
        can therefore exfiltrate your Anthropic credentials independent
        of the tunnel. ajent airgap protects the *network* between
        laptop and remote, not the remote itself — treat the remote as
        a credential-bearing peer, not a sandboxed proxy.

        --setup            Copy ~/.config/agentty/credentials.json from
                           this laptop to the remote (chmod 600) before
                           launching.  Run this once on first connect or
                           after re-OAuthing locally.
        --remote-agentty PATH Absolute path to ajent on the remote.  Default:
                           `ajent` (resolved via remote PATH).
        --acp [acp flags…] Don't launch the TUI — instead print a ready-to-
                           paste Zed `agent_servers` config that runs the
                           remote `ajent acp` over this same SSH+SOCKS
                           tunnel, with Zed owning the process. Everything
                           after --acp is forwarded to the remote acp agent
                           (e.g. --acp -m claude-haiku-4-5 --profile ask).
                           Must come AFTER <user@host>.
        --clipboard-relay  Make Ctrl+V image paste work on the remote.
                           Adds a reverse tunnel back to this laptop's
                           sshd and points the remote's clipboard reader
                           at it, so a pasted image is pulled from THIS
                           machine's clipboard on demand.  Requires sshd
                           running on the laptop and key-based login to
                           localhost (BatchMode — no password prompt).

        ssh and scp must be on this laptop's PATH.  Pass extra ssh args
        via the AGENTTY_AIRGAP_SSH env var (e.g. -i, -p, -J).

        Image paste (Ctrl+V) on the remote: easiest is --clipboard-relay
        above.  For full control, set AGENTTY_CLIPBOARD_CMD on the remote
        (or on this laptop — it's forwarded) to any command that prints
        the laptop's clipboard image to stdout, e.g.:
          AGENTTY_CLIPBOARD_CMD='ssh laptop wl-paste --type image/png'
        Without either, attach images by path instead.
      """;

  @FunctionalInterface
  interface ProcessExecutor {
    int run(List<String> arguments);
  }

  private final Map<String, String> environment;
  private final Path home;
  private final boolean windows;
  private final ProcessExecutor processes;

  AirgapCommand(Map<String, String> environment, Path home, boolean windows,
                ProcessExecutor processes) {
    this.environment = Map.copyOf(environment);
    this.home = home == null ? null : home.toAbsolutePath().normalize();
    this.windows = windows;
    this.processes = Objects.requireNonNull(processes, "processes");
  }

  static AirgapCommand systemDefault() {
    Map<String, String> environment = System.getenv();
    String configured = firstNonempty(environment.get("HOME"), environment.get("USERPROFILE"));
    if (configured == null && environment.get("HOMEDRIVE") != null
        && environment.get("HOMEPATH") != null) {
      configured = environment.get("HOMEDRIVE") + environment.get("HOMEPATH");
    }
    Path home = configured == null ? null : Path.of(configured);
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    return new AirgapCommand(environment, home, windows, AirgapCommand::runInherited);
  }

  int run(List<String> arguments, PrintStream output, PrintStream error) {
    boolean setup = false;
    boolean clipboardRelay = false;
    boolean acp = false;
    String remoteAgent = "ajent";
    String remote = "";
    String acpExtra = "";
    for (int index = 0; index < arguments.size(); index++) {
      String argument = arguments.get(index);
      switch (argument) {
        case "-h", "--help" -> {
          error.print(USAGE);
          return 0;
        }
        case "--setup" -> setup = true;
        case "--clipboard-relay" -> clipboardRelay = true;
        case "--acp" -> {
          acp = true;
          acpExtra = String.join(" ", arguments.subList(index + 1, arguments.size()));
          index = arguments.size();
        }
        case "--remote-agentty" -> {
          if (++index >= arguments.size()) {
            return unrecognized(argument, error);
          }
          remoteAgent = arguments.get(index);
        }
        default -> {
          if (!argument.startsWith("-") && remote.isEmpty()) remote = argument;
          else return unrecognized(argument, error);
        }
      }
    }
    if (remote.isEmpty()) {
      error.print(USAGE);
      return USAGE_ERROR;
    }
    if (setup) {
      int result = copyCredentials(remote, error);
      if (result != 0) return result;
    }
    if (acp) {
      printAcpConfig(remote, remoteAgent, acpExtra, error);
      return 0;
    }
    if (clipboardRelay && environment.getOrDefault("SSH_AUTH_SOCK", "").isEmpty()) {
      error.print("ajent airgap: --clipboard-relay needs a running ssh-agent on this laptop\n"
          + "             (the remote authenticates back via the forwarded agent). "
          + "Continuing anyway — image paste may not work.\n");
    }
    int result = processes.run(sshArguments(remote, remoteAgent, clipboardRelay));
    if (result < 0) {
      error.print("ajent airgap: failed to run `ssh`.\n"
          + "             ensure the OpenSSH client is installed and on PATH.\n");
      return 1;
    }
    return result;
  }

  private int copyCredentials(String remote, PrintStream error) {
    if (home == null) {
      error.print("ajent airgap: HOME is unset.\n");
      return 1;
    }
    Path local = home.resolve(".config/agentty/credentials.json");
    if (!Files.exists(local)) {
      error.print("ajent airgap: no local credentials at " + local + "\n"
          + "             run `ajent login` on this machine first.\n");
      return 1;
    }
    error.print("ajent airgap: copying credentials -> " + remote + " …\n");
    int result = processes.run(List.of("ssh", remote,
        "mkdir -p ~/.config/agentty && chmod 700 ~/.config/agentty"));
    if (result != 0) return stepFailure("remote mkdir failed (ssh exit ", result, error);
    result = processes.run(List.of(
        "scp", "-q", local.toString(), remote + ":.config/agentty/credentials.json"));
    if (result != 0) return stepFailure("scp failed (exit ", result, error);
    result = processes.run(List.of(
        "ssh", remote, "chmod 600 ~/.config/agentty/credentials.json"));
    if (result != 0) return stepFailure("remote chmod failed (ssh exit ", result, error);
    error.print("ajent airgap: credentials copied.\n");
    return 0;
  }

  private static int stepFailure(String message, int result, PrintStream error) {
    error.print("ajent airgap: " + message + result + ").\n");
    return result < 0 ? 1 : result;
  }

  private List<String> sshArguments(String remote, String remoteAgent, boolean clipboardRelay) {
    var command = new StringBuilder("AGENTTY_SOCKS_PROXY=localhost:1080 MAYA_FORCE_SYNC=1");
    String clipboard = environment.getOrDefault("AGENTTY_CLIPBOARD_CMD", "");
    if (!clipboard.isEmpty()) {
      command.append(" AGENTTY_CLIPBOARD_CMD=").append(shellQuote(clipboard));
    } else if (clipboardRelay) {
      command.append(" AGENTTY_CLIPBOARD_CMD=").append(shellQuote(clipboardCallback()));
    }
    for (String name : TERMINAL_MARKERS) {
      String value = environment.getOrDefault(name, "");
      if (!value.isEmpty()) command.append(' ').append(name).append('=').append(shellQuote(value));
    }
    command.append(" exec ").append(remoteAgent);

    var result = new ArrayList<>(List.of("ssh", "-t", "-R", "1080"));
    if (clipboardRelay) {
      result.add("-A");
      result.add("-R");
      result.add(CLIPBOARD_RELAY_PORT + ":localhost:22");
    }
    addOption(result, "ServerAliveInterval=30");
    addOption(result, "ServerAliveCountMax=3");
    addOption(result, "TCPKeepAlive=yes");
    addOption(result, "ConnectTimeout=10");
    addOption(result, "ExitOnForwardFailure=yes");
    String extra = environment.getOrDefault("AGENTTY_AIRGAP_SSH", "").strip();
    if (!extra.isEmpty()) result.addAll(List.of(extra.split("[ \\t]+")));
    result.add(remote);
    result.add(command.toString());
    return List.copyOf(result);
  }

  private String clipboardCallback() {
    boolean wayland = environment.getOrDefault("XDG_SESSION_TYPE", "").equals("wayland")
        || !environment.getOrDefault("WAYLAND_DISPLAY", "").isEmpty();
    String prefix = "";
    if (wayland && !environment.getOrDefault("WAYLAND_DISPLAY", "").isEmpty()) {
      prefix = "WAYLAND_DISPLAY=" + environment.get("WAYLAND_DISPLAY") + " ";
    } else if (!wayland && !environment.getOrDefault("DISPLAY", "").isEmpty()) {
      prefix = "DISPLAY=" + environment.get("DISPLAY") + " ";
    }
    String reader = wayland ? "wl-paste --type image/png"
        : "xclip -selection clipboard -t image/png -o";
    String user = environment.getOrDefault("USER", "");
    String destination = user.isEmpty() ? "localhost" : user + "@localhost";
    return "ssh -p " + CLIPBOARD_RELAY_PORT
        + " -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
        + " -o BatchMode=yes -o LogLevel=ERROR " + destination + " " + prefix + reader;
  }

  private void printAcpConfig(
      String remote, String remoteAgent, String acpExtra, PrintStream error) {
    String remoteCommand = "AGENTTY_SOCKS_PROXY=localhost:1080 exec " + remoteAgent + " acp"
        + (acpExtra.isEmpty() ? "" : " " + acpExtra);
    List<String> ssh = List.of("-T", "-R", "1080", "-o", "ExitOnForwardFailure=yes",
        "-o", "ServerAliveInterval=30", "-o", "ServerAliveCountMax=3", remote, remoteCommand);
    String args = ssh.stream().map(AirgapCommand::jsonString)
        .collect(java.util.stream.Collectors.joining(", "));
    Path settings = settingsPath();
    error.print("ajent airgap --acp: add this to Zed's settings.json"
        + (settings == null ? "" : "\n  → " + settings) + "\n"
        + "  (the laptop's settings — NOT the remote's; Zed runs `ssh` locally\n"
        + "   and the agent over its stdio, so this works for a LOCAL Zed\n"
        + "   project too, not just an SSH-remote one):\n\n"
        + "  \"agent_servers\": {\n"
        + "    \"ajent (airgap)\": {\n"
        + "      \"command\": \"ssh\",\n"
        + "      \"args\": [" + args + "]\n"
        + "    }\n"
        + "  }\n\n"
        + "Then pick “ajent (airgap)” in Zed's agent panel. One ssh process is\n"
        + "the tunnel, the agent, and the transport — nothing to keep running by\n"
        + "hand. Make sure `" + remote + "` has ajent installed and its credentials\n"
        + "(run `ajent airgap --setup " + remote + "` once to copy them over).\n");
  }

  private Path settingsPath() {
    if (windows) {
      String appData = environment.getOrDefault("APPDATA", "");
      return appData.isEmpty() ? null : Path.of(appData).resolve("Zed/settings.json");
    }
    return home == null ? null : home.resolve(".config/zed/settings.json");
  }

  private static int unrecognized(String argument, PrintStream error) {
    error.print("ajent airgap: unrecognized argument: " + argument + "\n\n" + USAGE);
    return USAGE_ERROR;
  }

  private static void addOption(List<String> arguments, String option) {
    arguments.add("-o");
    arguments.add(option);
  }

  static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private static String jsonString(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String firstNonempty(String first, String second) {
    if (first != null && !first.isEmpty()) return first;
    return second == null || second.isEmpty() ? null : second;
  }

  private static int runInherited(List<String> arguments) {
    try {
      return new ProcessBuilder(arguments).inheritIO().start().waitFor();
    } catch (IOException exception) {
      return -1;
    } catch (InterruptedException exception) {
      java.lang.Thread.currentThread().interrupt();
      return -1;
    }
  }
}
