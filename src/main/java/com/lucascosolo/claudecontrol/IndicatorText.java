package com.lucascosolo.claudecontrol;

/** The action-bar line players see while a turn of theirs runs. */
final class IndicatorText {
    private IndicatorText() {}

    private static final java.util.regex.Pattern GRADLE = java.util.regex.Pattern.compile("(^|[\\s;&|(/])gradlew?(\\s|$)");

    static String of(String tool) {
        return of(tool, null);
    }

    /** Bash is "building a plugin" only when the command runs gradle inside workshop/; game turns use Bash for other things too. */
    static String of(String tool, String command) {
        if (tool == null) return "Claude is thinking…";
        if (tool.equals("Bash")) {
            boolean build = command != null && command.contains("workshop/") && GRADLE.matcher(command).find();
            return build ? "Claude is building a plugin…" : "Claude is working…";
        }
        String name = tool.replaceFirst("^mcp__game__", "").replaceAll("[^A-Za-z0-9_]", "");
        return "Claude is working: " + (name.length() > 32 ? name.substring(0, 32) : name);
    }
}
