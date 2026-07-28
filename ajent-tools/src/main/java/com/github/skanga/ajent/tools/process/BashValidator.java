package com.github.skanga.ajent.tools.process;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Rejects commands that cannot safely complete in an unattended agent session.
 *
 * <p>This is a UX guardrail, not a security boundary. Its reliable job is refusing commands that
 * would hang a non-interactive session: interactive editors and pagers, a bare REPL, or an
 * editor-opening {@code git commit}. The {@code DANGERS} substring checks and the {@code curl|sh}
 * pattern are only a best-effort guard against obvious accidental footguns; they match literal
 * text and are trivially bypassed by quoting, spacing, variables, or encoding, so they must never
 * be relied on to contain hostile input.
 *
 * <p>Real containment for {@code bash} is enforced elsewhere by three layers: the permission
 * policy ({@code bash} carries the {@code EXEC} effect and prompts before it runs), the OS-level
 * {@code ProcessSandbox} (bwrap or sandbox-exec), and the {@code WorkspaceSandbox} path checks.
 */
public final class BashValidator {
  private static final Set<String> INTERACTIVE = Set.of("vim", "vi", "nvim", "nano", "emacs",
      "pico", "ed", "joe", "mcedit", "less", "more", "man", "top", "htop", "btop", "tmux",
      "screen", "mysql", "psql", "sqlite3", "redis-cli", "mongo", "ghci", "ocaml", "irb",
      "pry", "lua", "tclsh", "gdb", "lldb", "fzf", "dialog", "whiptail");
  private static final Set<String> REPL = Set.of("python", "python3", "node", "deno", "ruby",
      "php", "iex", "bash", "sh", "zsh", "fish", "pwsh", "powershell", "cmd");
  // Best-effort refusal of obvious accidental footguns only. Literal-substring matching, so it is
  // trivially bypassed by obfuscation; this is not a security control (see class javadoc).
  private static final List<String[]> DANGERS = List.of(
      new String[] {"rm -rf /", "refusing wide rm that could wipe the filesystem root"},
      new String[] {"rm -rf /*", "refusing wide rm that could wipe the filesystem root"},
      new String[] {"rm -rf ~", "refusing to recursively delete the home directory"},
      new String[] {":(){ :|:& };:", "fork-bomb pattern refused"},
      new String[] {"mkfs", "refusing mkfs - would reformat a filesystem"},
      new String[] {"dd if=", "refusing raw `dd` write - can corrupt disks if misdirected"},
      new String[] {"shutdown", "refusing shutdown"}, new String[] {"reboot", "refusing reboot"},
      new String[] {"git push --force", "refusing `git push --force`; use --force-with-lease and ask the user first"},
      new String[] {"git push -f", "refusing `git push -f`; use --force-with-lease and ask the user first"});

  private BashValidator() {}

  public static String validate(String command) {
    String token = firstToken(command);
    if (INTERACTIVE.contains(token)) return "refusing to run interactive command '" + token
        + "' - it would block waiting for stdin. Use a non-interactive alternative.";
    if (REPL.contains(token) && command.substring(Math.min(command.length(), command.indexOf(token)
        + token.length())).isBlank()) return "refusing to start interactive " + token
            + " REPL - it would block waiting for stdin. Provide a script path or use `-c` to run a snippet.";
    if (token.equals("git")) {
      if (word(command, "rebase") && word(command, "-i")) return "refusing to run interactive rebase (`git rebase -i`)";
      if (word(command, "add") && (word(command, "-i") || word(command, "-p")
          || word(command, "--interactive") || word(command, "--patch")))
        return "refusing to run interactive git add - use explicit file paths.";
      if (word(command, "commit") && !(word(command, "-m") || word(command, "-F")
          || word(command, "--message") || word(command, "--file") || word(command, "--amend")
          || word(command, "-C") || word(command, "--no-edit")))
        return "refusing `git commit` without -m/-F - it would open an editor.";
    }
    for (String[] danger : DANGERS) if (command.contains(danger[0])) return danger[1];
    if ((command.contains("curl") || command.contains("wget"))
        && command.matches("(?s).*\\|\\s*(?:sh|bash|zsh|dash|ksh)(?:\\s|$).*$"))
      return "refusing `curl|sh` / `wget|sh` - download the script, inspect it, then run explicitly.";
    return "";
  }

  private static String firstToken(String command) {
    String stripped = command.stripLeading();
    int end = 0;
    while (end < stripped.length() && " \t|&;".indexOf(stripped.charAt(end)) < 0) end++;
    String token = stripped.substring(0, end);
    int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
    if (slash >= 0) token = token.substring(slash + 1);
    String lower = token.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".exe") || lower.endsWith(".cmd") || lower.endsWith(".bat"))
      token = token.substring(0, token.length() - 4);
    return token;
  }

  private static boolean word(String command, String needle) {
    return PatternHolder.words(command).contains(needle);
  }

  private static final class PatternHolder {
    private PatternHolder() {}
    private static List<String> words(String command) {
      return List.of(command.trim().split("[ \\t]+")).stream().map(value -> value
          .replaceAll("^[\"']|[\"']$", "")).toList();
    }
  }
}
