package com.lucascosolo.claudecontrol;

/** The outcome of one tool call, as both backends report it to Claude. */
public record ToolResult(String text, boolean isError) {}
