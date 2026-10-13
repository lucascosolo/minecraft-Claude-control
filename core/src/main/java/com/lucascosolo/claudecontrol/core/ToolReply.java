package com.lucascosolo.claudecontrol.core;
public record ToolReply(String text, boolean error) { public ToolReply { if(text==null) throw new IllegalArgumentException("Missing tool result"); } }
