package com.lucascosolo.claudecontrol;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The claude command line for the game or workshop mode. Pure. Permission mode dontAsk denies anything not allowed here;
 * reads outside the working directory are blocked by the user settings, and the denials below cover secrets inside it.
 * Rule paths: "//x" is absolute, "x" is relative to the CLI's current directory, which moves when a Bash command runs cd.
 * Each scoped rule is therefore given twice, relative and absolute, so file tools keep working after a cd.
 */
public final class CliArgs {
    public record Options(String binary, String model, String effort, Path systemPromptFile, Path mcpConfig,
                          String resumeSessionId, boolean workshop, List<String> gameTools, Path workDir) {}

    static final List<String> DENIED = List.of("Read(//proc/**)", "Read(//server/*.properties)", "Read(//server/*.json)",
            "Read(//server/**/cli-run/**)", "Read(//server/**/cli-home/**)", "Edit(//server/**/cli-home/**)", "Read(**/.claude/**)",
            "Edit(**/.claude/**)", "Read(//server/plugins/ClaudeControl/config.yml)", "WebSearch", "Agent");

    private CliArgs() {}

    public static List<String> build(Options o) {
        List<String> a = new ArrayList<>(List.of(o.binary(), "-p", "--input-format", "stream-json", "--output-format", "stream-json",
                "--verbose", "--model", o.model(), "--effort", o.effort(), "--system-prompt-file", o.systemPromptFile().toString(),
                "--mcp-config", o.mcpConfig().toString(), "--strict-mcp-config", "--setting-sources", "user"));
        if (o.resumeSessionId() != null) a.addAll(List.of("--resume", o.resumeSessionId()));
        a.add("--allowedTools");
        for (String t : o.gameTools()) a.add("mcp__game__" + t);
        a.addAll(scoped(o.workDir(), "memory"));
        a.add("WebFetch(domain:minecraft.wiki)");
        if (o.workshop()) {
            a.addAll(scoped(o.workDir(), "workshop"));
            a.add("Bash");
        }
        a.add("--disallowedTools");
        if (!o.workshop()) a.add("Bash");
        a.addAll(DENIED);
        a.addAll(List.of("--permission-mode", "dontAsk"));
        return a;
    }

    private static List<String> scoped(Path workDir, String folder) {
        List<String> r = new ArrayList<>();
        String abs = "/" + workDir.toAbsolutePath().normalize().resolve(folder).toString().replace('\\', '/');
        for (String tool : List.of("Read", "Edit", "Write", "Glob", "Grep")) {
            r.add(tool + "(" + folder + "/**)");
            r.add(tool + "(" + abs + "/**)");
        }
        return r;
    }

    /** cli-home/settings.json, written by the server user: no hooks, and file tools stay inside the working directory. */
    static String userSettings() {
        return "{\"disableAllHooks\": true, \"permissions\": {\"blockReadsOutsideWorkingDirectories\": true, \"defaultMode\": \"dontAsk\"}}\n";
    }
}
