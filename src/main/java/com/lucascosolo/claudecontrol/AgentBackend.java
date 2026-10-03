package com.lucascosolo.claudecontrol;

/** One brain that serves agent turns. Turns run on the agent worker thread only. */
public interface AgentBackend {
    void runTurn(String userTurn, String actors) throws Exception;

    void reset();

    void close();

    String describe();

    /** Called from the shutdown path while a turn may still be running, so the turn ends quickly. */
    default void stop() {}
}
