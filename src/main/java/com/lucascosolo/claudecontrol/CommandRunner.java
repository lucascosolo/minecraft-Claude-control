package com.lucascosolo.claudecontrol;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** The only place commands are executed, and the only place {@link CommandPolicy} is checked. */
public final class CommandRunner {
    public record Anchor(String world, int x, int z) {}

    private static final String NOT_ONLINE = "player not online";
    private static final int MAX_OUTPUT = 2000;
    private static final long LATE_FEEDBACK_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private final Plugin plugin;
    private final Logger log;
    private volatile CommandPolicy policy;
    private volatile long timeoutMs;

    public CommandRunner(Plugin plugin, CommandPolicy policy, long timeoutMs) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        configure(policy, timeoutMs);
    }

    public void configure(CommandPolicy policy, long timeoutMs) {
        this.policy = policy;
        this.timeoutMs = timeoutMs;
    }

    /** Blocks the calling (agent worker) thread; never call from a server thread. */
    public CommandResult run(String command, String actors, String nearPlayer, Anchor anchor) throws InterruptedException {
        String cmd = command.strip();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (!policy.isAllowed(cmd)) {
            log.info(clean("[audit] DENIED for " + actors + ": " + cmd));
            return new CommandResult(command, "denied", "This command is blocked by the server's ClaudeControl policy.");
        }
        log.info(clean("[audit] RUN for " + actors + (anchor != null ? " at " + anchor.world() + " " + anchor.x() + " " + anchor.z()
                : nearPlayer != null ? " near " + nearPlayer : "") + ": " + cmd));
        World world = anchor == null ? null : Bukkit.getWorld(anchor.world());
        if (anchor != null && world == null) return new CommandResult(command, "error", "unknown world");
        Player near = anchor != null || nearPlayer == null ? null : Bukkit.getPlayerExact(nearPlayer);
        if (anchor == null && nearPlayer != null && near == null) return new CommandResult(command, "error", NOT_ONLINE);
        StringBuffer feedback = new StringBuffer();
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        String toRun = cmd;
        Runnable task = () -> {
            try {
                CommandSender sender = Bukkit.createCommandSender(c ->
                        feedback.append(PlainTextComponentSerializer.plainText().serialize(c)).append('\n'));
                done.complete(Bukkit.dispatchCommand(sender, toRun));
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        };
        if (world != null) {
            Bukkit.getRegionScheduler().execute(plugin, world, anchor.x() >> 4, anchor.z() >> 4, task);
        } else if (near == null) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        } else if (near.getScheduler().run(plugin, t -> task.run(), () -> done.complete(null)) == null) {
            return new CommandResult(command, "error", NOT_ONLINE);
        }
        long start = System.nanoTime();
        try {
            Boolean known = done.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (known == null) return new CommandResult(command, "error", NOT_ONLINE);
            awaitLateFeedback(feedback, start);
            String out = cap(feedback.toString().strip());
            if (!known) return new CommandResult(command, "error", out.isEmpty() ? "Unknown command." : out);
            return new CommandResult(command, "ok", out.isEmpty() ? "(no output)" : out);
        } catch (TimeoutException e) {
            return new CommandResult(command, "error", "timed out");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            while (c.getCause() != null) c = c.getCause();
            return new CommandResult(command, "error", cap(c.getClass().getSimpleName() + ": " + c.getMessage()));
        }
    }

    /** On Folia some commands (time set, weather) finish on a later tick and report after dispatch returns. */
    private void awaitLateFeedback(StringBuffer feedback, long startNanos) throws InterruptedException {
        long deadline = Math.min(startNanos + TimeUnit.MILLISECONDS.toNanos(timeoutMs), System.nanoTime() + LATE_FEEDBACK_NANOS);
        while (feedback.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
    }

    long timeoutMs() { return timeoutMs; }

    /** Control characters would let a command or message forge log lines. */
    static String clean(String s) {
        return s.replaceAll("[\\p{Cc}\\u2028\\u2029]", " ");
    }

    private static String cap(String s) {
        return s.length() <= MAX_OUTPUT ? s : s.substring(0, MAX_OUTPUT);
    }
}
