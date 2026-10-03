package com.lucascosolo.claudecontrol;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;

/** send_chat and get_player_details. Called from the agent worker thread only. */
public final class GameTools {
    /** A tool failure that is reported to Claude as is_error, not thrown out of the turn. */
    public static final class ToolError extends Exception {
        public ToolError(String message) { super(message); }
    }

    private static final int MAX_CHAT = 1000;
    private final Plugin plugin;
    private final LongSupplier timeoutMs;
    private final Logger log;
    private volatile String chatPrefix;

    public GameTools(Plugin plugin, String chatPrefix, LongSupplier timeoutMs) {
        this.plugin = plugin;
        this.timeoutMs = timeoutMs;
        this.log = plugin.getLogger();
        this.chatPrefix = chatPrefix;
    }

    public void setChatPrefix(String chatPrefix) { this.chatPrefix = chatPrefix; }

    public String sendChat(String message, String playerName) throws ToolError {
        String prefix = chatPrefix;
        String capped = message.length() > MAX_CHAT ? message.substring(0, MAX_CHAT) : message;
        Component text = Component.text(capped.lines().map(l -> prefix + l).collect(Collectors.joining("\n")));
        if (playerName == null) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> Bukkit.broadcast(text));
            return "Broadcast sent.";
        }
        Player p = Bukkit.getPlayerExact(playerName);
        if (p == null) throw new ToolError("Player " + playerName + " is not online.");
        if (p.getScheduler().run(plugin, t -> p.sendMessage(text), null) == null) {
            throw new ToolError("Player " + playerName + " is not online.");
        }
        log.info(CommandRunner.clean("[whisper to " + playerName + "] " + capped));
        return "Message sent to " + playerName + ".";
    }

    public String surveyArea(String worldName, int x, int z, int radius, int footprint) throws ToolError, InterruptedException {
        if (radius < 1 || radius > 12) throw new ToolError("radius must be 1..12.");
        if (footprint < 3 || footprint > 15) throw new ToolError("footprint must be 3..15.");
        World world = Bukkit.getWorld(worldName);
        if (world == null) throw new ToolError("unknown world");
        CompletableFuture<String> done = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(plugin, world, x >> 4, z >> 4, () -> {
            try {
                done.complete(survey(world, x, z, radius, footprint));
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return await(done, "Survey");
    }

    private static String survey(World world, int cx, int cz, int radius, int footprint) {
        int side = 2 * radius + 1, originX = cx - radius, originZ = cz - radius;
        int[][] height = new int[side][side];
        boolean[][] blocked = new boolean[side][side];
        String[][] surface = new String[side][side];
        Map<String, Integer> entities = new TreeMap<>();
        Set<Long> seen = new HashSet<>();
        for (int zi = 0; zi < side; zi++) {
            for (int xi = 0; xi < side; xi++) {
                int x = originX + xi, z = originZ + zi;
                height[zi][xi] = Integer.MIN_VALUE;
                blocked[zi][xi] = true;
                if (!Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4) || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
                if (seen.add((long) (x >> 4) << 32 | (z >> 4) & 0xffffffffL)) {
                    for (Entity e : world.getChunkAt(x >> 4, z >> 4).getEntities()) {
                        var l = e.getLocation();
                        if (!(e instanceof Player) && l.getBlockX() >= originX && l.getBlockX() < originX + side
                                && l.getBlockZ() >= originZ && l.getBlockZ() < originZ + side) {
                            entities.merge(e.getType().getKey().getKey(), 1, Integer::sum);
                        }
                    }
                }
                int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block top = world.getBlockAt(x, y, z);
                height[zi][xi] = y;
                blocked[zi][xi] = top.isLiquid();
                surface[zi][xi] = top.getType().getKey().getKey();
            }
        }
        var sites = SiteFinder.find(originX, originZ, height, blocked, footprint, 3, cx, cz);
        return SurveyRenderer.render(world.getName(), originX, originZ, height, surface, sites, entities);
    }

    public String teleportPlayer(String playerName, String worldName, int x, int y, int z) throws ToolError, InterruptedException {
        Player p = Bukkit.getPlayerExact(playerName);
        if (p == null) throw new ToolError("Player " + playerName + " is not online.");
        World world = Bukkit.getWorld(worldName);
        if (world == null) throw new ToolError("unknown world");
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        var task = p.getScheduler().run(plugin, t -> p.teleportAsync(new Location(world, x + 0.5, y, z + 0.5))
                .whenComplete((ok, err) -> { if (err != null) done.completeExceptionally(err); else done.complete(ok); }),
                () -> done.complete(false));
        if (task == null) throw new ToolError("Player " + playerName + " is not online.");
        String where = world.getName() + " " + x + " " + y + " " + z;
        if (!await(done, "Teleport")) throw new ToolError("Teleport of " + playerName + " to " + where + " failed.");
        return "Teleported " + playerName + " to " + where + ".";
    }

    private <T> T await(CompletableFuture<T> f, String what) throws ToolError, InterruptedException {
        try {
            return f.get(timeoutMs.getAsLong(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ToolError(what + " timed out.");
        } catch (ExecutionException e) {
            throw new ToolError(what + " failed: " + e.getCause());
        }
    }

    public String playerDetails(String playerName) throws ToolError, InterruptedException {
        Player p = Bukkit.getPlayerExact(playerName);
        if (p == null) throw new ToolError("Player " + playerName + " is not online.");
        try {
            return WorldSnapshot.onEntity(plugin, p, () -> describe(p)).get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ToolError("Timed out reading " + playerName + ".");
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolError("Player " + playerName + " is unavailable (" + e.getClass().getSimpleName() + ").");
        }
    }

    private static String describe(Player p) {
        var l = p.getLocation();
        Map<String, Integer> inv = new TreeMap<>();
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && !it.getType().isAir()) inv.merge(it.getType().name().toLowerCase(), it.getAmount(), Integer::sum);
        }
        ItemStack held = p.getInventory().getItemInMainHand();
        String effects = p.getActivePotionEffects().stream()
                .map((PotionEffect e) -> e.getType().getKey().getKey() + " " + (e.getAmplifier() + 1) + " (" + e.getDuration() / 20 + "s)")
                .collect(Collectors.joining(", "));
        return "name: " + p.getName() + (p.isOp() ? " (op)" : "")
                + "\nworld: " + l.getWorld().getName()
                + String.format("\nposition: %.1f %.1f %.1f (block %d %d %d)", l.getX(), l.getY(), l.getZ(), l.getBlockX(), l.getBlockY(), l.getBlockZ())
                + "\ngamemode: " + p.getGameMode().name().toLowerCase()
                + "\nhealth: " + Math.round(p.getHealth()) + "/" + Math.round(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue())
                + "\nfood: " + p.getFoodLevel()
                + "\nxp level: " + p.getLevel()
                + "\nheld item: " + (held.getType().isAir() ? "nothing" : held.getType().name().toLowerCase() + " x" + held.getAmount())
                + "\ninventory: " + (inv.isEmpty() ? "empty" : inv.entrySet().stream().map(e -> e.getKey() + " x" + e.getValue()).collect(Collectors.joining(", ")))
                + "\neffects: " + (effects.isEmpty() ? "none" : effects);
    }
}
