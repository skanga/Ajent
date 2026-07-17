package com.github.skanga.ajent.cli;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Immutable result of parsing Ajent's top-level command line. */
public record CliArguments(
    Subcommand subcommand,
    String key,
    String model,
    String workspace,
    String sandbox,
    String profile,
    String provider,
    List<String> airgapArguments,
    Optional<String> badArgument) {

  public CliArguments {
    airgapArguments = List.copyOf(airgapArguments);
    badArgument = badArgument == null ? Optional.empty() : badArgument;
  }

  public static CliArguments parse(String[] arguments) {
    Subcommand subcommand = Subcommand.NONE;
    String key = "";
    String model = "";
    String workspace = "";
    String sandbox = "";
    String profile = "";
    String provider = "";
    for (int index = 0; index < arguments.length; index++) {
      String argument = arguments[index];
      Subcommand named = Subcommand.fromName(argument);
      if (named != null && named != Subcommand.AIRGAP && named != Subcommand.VERSION) {
        subcommand = named;
      } else if (named == Subcommand.AIRGAP) {
        return parsed(Subcommand.AIRGAP, key, model, workspace, sandbox, profile, provider,
            Arrays.asList(Arrays.copyOfRange(arguments, index + 1, arguments.length)));
      } else if (takesValue(argument, "-k", "--key") && index + 1 < arguments.length) {
        key = arguments[++index];
      } else if (takesValue(argument, "-m", "--model") && index + 1 < arguments.length) {
        model = arguments[++index];
      } else if (takesValue(argument, "-w", "--workspace") && index + 1 < arguments.length) {
        workspace = arguments[++index];
      } else if (argument.equals("--sandbox") && index + 1 < arguments.length) {
        sandbox = arguments[++index];
      } else if (takesValue(argument, "-p", "--profile") && index + 1 < arguments.length) {
        profile = arguments[++index];
      } else if (argument.equals("--provider") && index + 1 < arguments.length) {
        provider = arguments[++index];
      } else if (argument.equals("-h") || argument.equals("--help")) {
        subcommand = Subcommand.HELP;
      } else if (argument.equals("-V") || argument.equals("--version")
          || named == Subcommand.VERSION) {
        return parsed(Subcommand.VERSION, key, model, workspace, sandbox, profile, provider,
            List.of());
      } else {
        return new CliArguments(subcommand, key, model, workspace, sandbox, profile, provider,
            List.of(), Optional.of(argument));
      }
    }
    return parsed(subcommand, key, model, workspace, sandbox, profile, provider, List.of());
  }

  private static CliArguments parsed(
      Subcommand subcommand, String key, String model, String workspace, String sandbox,
      String profile, String provider, List<String> airgapArguments) {
    return new CliArguments(subcommand, key, model, workspace, sandbox, profile, provider,
        airgapArguments, Optional.empty());
  }

  private static boolean takesValue(String value, String shortName, String longName) {
    return value.equals(shortName) || value.equals(longName);
  }

  public enum Subcommand {
    NONE(""), LOGIN("login"), LOGOUT("logout"), STATUS("status"), AIRGAP("airgap"),
    ACP("acp"), MCP_SERVE("mcp-serve"), SKILLS("skills"), VERSION("version"), HELP("help");

    private final String commandName;

    Subcommand(String commandName) {
      this.commandName = commandName;
    }

    public String commandName() {
      return commandName;
    }

    public static List<String> names() {
      return Arrays.stream(values()).filter(value -> value != NONE)
          .map(Subcommand::commandName).toList();
    }

    private static Subcommand fromName(String name) {
      for (Subcommand value : values()) {
        if (!value.commandName.isEmpty() && value.commandName.equals(name)) return value;
      }
      return null;
    }
  }
}
