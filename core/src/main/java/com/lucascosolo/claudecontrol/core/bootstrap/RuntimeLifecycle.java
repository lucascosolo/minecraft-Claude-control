package com.lucascosolo.claudecontrol.core.bootstrap;

import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** Stable bridge: only Bukkit/JDK types cross the runtime classloader boundary. */
public interface RuntimeLifecycle extends CommandExecutor {
    void start(boolean paused) throws Exception;
    void stop();
    boolean paused();
    boolean owner(CommandSender sender);
    boolean awaitStopped(long timeoutMillis) throws InterruptedException;
}
