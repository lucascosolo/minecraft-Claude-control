package com.lucascosolo.claudecontrol;

/** The backend cannot serve turns right now (not a failure of the turn itself); the fallback may take over. */
public class BackendUnavailable extends RuntimeException {
    public BackendUnavailable(String message) { super(message); }
}
