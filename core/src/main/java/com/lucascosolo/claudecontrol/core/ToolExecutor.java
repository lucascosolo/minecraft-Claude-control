package com.lucascosolo.claudecontrol.core;
import com.google.gson.JsonObject;
@FunctionalInterface public interface ToolExecutor { ToolReply execute(String name, JsonObject input, Turn turn) throws Exception; }
