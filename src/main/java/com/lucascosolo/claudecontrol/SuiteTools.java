package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * The v0.3 suite tools spawn, player and show: places are resolved when the work runs, a player's location is read on their
 * own scheduler and a place is acted on by the region that owns it. Callers are MCP, worker or async threads, never tick threads.
 */
final class SuiteTools implements Listener {
    static final int MAX_RESULT = 2000;
    private static final ObjectMapper M = new ObjectMapper();
    private final Plugin plugin;
    private final LongSupplier timeoutMs;
    private final NamespacedKey expires, keepName, keepOwner, noFall, harmless;
    private final Map<String, List<UUID>> groups = new ConcurrentHashMap<>();
    private final AtomicInteger groupSeq = new AtomicInteger();
    /** Entities this suite spawned, by id: Bukkit.getEntity(UUID) may not be called off the owning thread on Folia. */
    private final Map<UUID, Entity> live = new ConcurrentHashMap<>();

    SuiteTools(Plugin plugin, LongSupplier timeoutMs) {
        this.plugin = plugin;
        this.timeoutMs = timeoutMs;
        expires = new NamespacedKey(plugin, "expires");
        keepName = new NamespacedKey(plugin, "own_name");
        keepOwner = new NamespacedKey(plugin, "own_owner");
        noFall = new NamespacedKey(plugin, "no_fall");
        harmless = new NamespacedKey(plugin, "harmless");
    }

    // ---------- ids

    static Set<String> entityIds() {
        Set<String> s = new java.util.TreeSet<>();
        for (EntityType t : EntityType.values()) if (t != EntityType.UNKNOWN && t.isSpawnable() && t != EntityType.PLAYER) s.add(t.getKey().getKey());
        return s;
    }

    static Set<String> itemIds() {
        Set<String> s = new java.util.TreeSet<>();
        for (Material m : Material.values()) if (!m.isLegacy() && m.isItem() && !m.isAir()) s.add(m.getKey().getKey());
        return s;
    }

    static Set<String> keys(Registry<?> r) {
        Set<String> s = new java.util.TreeSet<>();
        r.forEach(k -> s.add(((org.bukkit.Keyed) k).getKey().getKey()));
        return s;
    }

    static Set<String> particleIds() {
        Set<String> s = new java.util.TreeSet<>();
        for (Particle p : Particle.values()) if (p.getDataType() == Void.class) s.add(p.getKey().getKey());
        return s;
    }

    static String id(String kind, String input, Set<String> known) throws GameTools.ToolError {
        try {
            return IdResolver.resolve(kind, input, known);
        } catch (IllegalArgumentException e) {
            throw new GameTools.ToolError(e.getMessage());
        }
    }

    static PotionEffectType effect(String type) throws GameTools.ToolError {
        return Registry.EFFECT.get(NamespacedKey.minecraft(id("effect", type, keys(Registry.EFFECT))));
    }

    static ItemStack item(ItemSpec s, Authority a) throws GameTools.ToolError {
        String key = id("item", s.id(), itemIds());
        refuse(SuiteGates.item(key, a));
        Material m = Material.matchMaterial(key);
        ItemStack it = new ItemStack(m, Math.min(s.count(), m.getMaxStackSize()));
        var meta = it.getItemMeta();
        if (meta != null) {
            if (s.name() != null) meta.displayName(Component.text(ChatText.plain(s.name())));
            if (s.lore() != null && !s.lore().isEmpty()) meta.lore(s.lore().stream().map(l -> (Component) Component.text(ChatText.plain(l))).toList());
            for (var en : s.enchantments().entrySet()) {
                Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(id("enchantment", en.getKey(), keys(Registry.ENCHANTMENT))));
                refuse(SuiteGates.enchant(e.getKey().getKey(), en.getValue(), e.getMaxLevel(), a));
                meta.addEnchant(e, en.getValue(), true);
            }
            meta.setUnbreakable(s.unbreakable());
            it.setItemMeta(meta);
        }
        return it;
    }

    /** Stacks for the whole count of an item spec (count may exceed one stack). */
    static List<ItemStack> stacks(ItemSpec s, Authority a) throws GameTools.ToolError {
        ItemStack one = item(s, a);
        List<ItemStack> out = new ArrayList<>();
        int left = s.count(), max = one.getMaxStackSize();
        while (left > 0) {
            ItemStack st = one.clone();
            st.setAmount(Math.min(left, max));
            out.add(st);
            left -= st.getAmount();
        }
        return out;
    }

    // ---------- places

    /** Where the place's base is now: the near player's location (read on their scheduler) or the absolute point. */
    Location base(PlaceExpr p) throws GameTools.ToolError, InterruptedException {
        if (p.near() != null) {
            Player pl = Bukkit.getPlayerExact(p.near());
            if (pl == null) throw new GameTools.ToolError("Player " + p.near() + " is not online.");
            return await(GameTools.onEntity(plugin, pl, pl::getLocation), "reading " + pl.getName());
        }
        World w = Bukkit.getWorld(p.world());
        if (w == null) throw new GameTools.ToolError("unknown world " + p.world());
        return new Location(w, 0, 0, 0);
    }

    /** A fresh point of the expression around base (scatter is re-rolled on every call). */
    static Location point(PlaceExpr p, Location base) {
        PlaceExpr.Point r = p.resolve(new PlaceExpr.Point(base.getX(), base.getY(), base.getZ()), ThreadLocalRandom.current());
        return new Location(base.getWorld(), r.x(), r.y(), r.z());
    }

    <T> T await(CompletableFuture<T> f, String what) throws GameTools.ToolError, InterruptedException {
        try {
            return f.get(timeoutMs.getAsLong(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new GameTools.ToolError(what + " timed out.");
        } catch (ExecutionException e) {
            throw new GameTools.ToolError(what + " failed: " + (e.getCause() instanceof GameTools.ToolError t ? t.getMessage() : e.getCause()));
        } catch (java.util.concurrent.CancellationException e) {
            throw new GameTools.ToolError(what + " was skipped: the player or place is no longer available.");
        }
    }

    /** Work on the region owning a block (B3 checks, B9 cancellation); the block position is checked again on that thread. */
    <T> T onBlock(Location at, String what, GameTools.RegionWork<T> work) throws GameTools.ToolError, InterruptedException {
        World w = at.getWorld();
        int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ();
        refuse(BlockSpot.refusal(w, x, y, z) == null ? null : what + " refused: " + BlockSpot.refusal(w, x, y, z));
        return GameTools.region(plugin, w, x, z, what, timeoutMs.getAsLong(), () -> {
            String r = BlockSpot.refusalHere(w, x, y, z);
            if (r != null) throw new GameTools.ToolError(what + " refused: " + r);
            return work.run();
        });
    }

    private CompletableFuture<Void> onRegion(Location at, java.util.concurrent.atomic.AtomicBoolean cancelled, Runnable work) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(plugin, at, () -> {
            if (cancelled.get()) return;
            try {
                work.run();
                f.complete(null);
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    static void refuse(String refusal) throws GameTools.ToolError {
        if (refusal != null) throw new GameTools.ToolError(refusal);
    }

    Plugin plugin() { return plugin; }

    /** An entity spawned through the suite since start, or null. */
    Entity spawned(UUID id) { return live.get(id); }

    NamespacedKey harmless() { return harmless; }

    // ---------- spawn

    /** chunks: where each entity was spawned (chunk x, z), for undo after a restart. */
    record Spawned(String group, String world, List<UUID> ids, int unloaded, int roofed, String type, Map<UUID, int[]> chunks) {}

    /** The spawn gates (A3, A5) for a spec under an authority; throws the refusal. */
    static EntityType check(SpawnSpec s, Authority a) throws GameTools.ToolError {
        if (s.type().equals("item")) {
            for (ItemSpec i : s.items()) refuse(SuiteGates.item(id("item", i.id(), itemIds()), a));
            return EntityType.ITEM;
        }
        String key = id("entity", s.type(), entityIds());
        EntityType t = EntityType.fromName(key);
        boolean hostile = t.getEntityClass() != null && org.bukkit.entity.Enemy.class.isAssignableFrom(t.getEntityClass());
        refuse(SuiteGates.spawn(key, s.at().near(), hostile, a));
        return t;
    }

    /** Spawns on the regions that own each point and waits for them; the ids are those that exist now. */
    Spawned spawnNow(SpawnSpec s, Authority a) throws GameTools.ToolError, InterruptedException {
        boolean items = s.type().equals("item");
        EntityType type = check(s, a);
        List<ItemStack> drops = new ArrayList<>();
        if (items) for (ItemSpec i : s.items()) drops.addAll(stacks(i, a));
        List<ItemStack> equipment = new ArrayList<>();
        List<EquipmentSlot> slots = new ArrayList<>();
        for (var e : s.equipment().entrySet()) {
            slots.add(switch (e.getKey()) {
                case "head" -> EquipmentSlot.HEAD; case "chest" -> EquipmentSlot.CHEST; case "legs" -> EquipmentSlot.LEGS;
                case "feet" -> EquipmentSlot.FEET; case "offhand" -> EquipmentSlot.OFF_HAND; default -> EquipmentSlot.HAND;
            });
            equipment.add(item(e.getValue(), a));
        }
        List<PotionEffect> effects = new ArrayList<>();
        for (EffectSpec e : s.effects()) effects.add(new PotionEffect(effect(e.type()), e.seconds() * 20, e.amplifier()));
        Location base = base(s.at());
        List<UUID> ids = java.util.Collections.synchronizedList(new ArrayList<>());
        Map<UUID, int[]> chunks = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger unloaded = new AtomicInteger(), roofed = new AtomicInteger();
        List<CompletableFuture<Void>> all = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < s.count(); i++) {
            Location at = point(s.at(), base).add(s.spread() == 0 ? 0 : rnd.nextInt(-s.spread(), s.spread() + 1), s.heightOffset(),
                    s.spread() == 0 ? 0 : rnd.nextInt(-s.spread(), s.spread() + 1));
            int n = i;
            if (BlockSpot.refusal(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ()) != null) { unloaded.incrementAndGet(); continue; }
            all.add(onRegion(at, cancelled, () -> {
                World w = at.getWorld();
                if (BlockSpot.refusalHere(w, at.getBlockX(), at.getBlockY(), at.getBlockZ()) != null) { unloaded.incrementAndGet(); return; }
                if (s.landing() != null && w.getHighestBlockYAt(at.getBlockX(), at.getBlockZ()) >= at.getBlockY()) { roofed.incrementAndGet(); return; }
                Entity e = items ? w.dropItem(at, drops.get(n % drops.size()).clone()) : w.spawn(at, type.getEntityClass(), x -> configure(x, s, slots, equipment, effects));
                if (items) configure(e, s, slots, equipment, effects);
                ids.add(e.getUniqueId());
                chunks.put(e.getUniqueId(), new int[] {at.getBlockX() >> 4, at.getBlockZ() >> 4});
                live.put(e.getUniqueId(), e);
            }));
        }
        try {
            CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).get(timeoutMs.getAsLong(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new GameTools.ToolError("spawn failed: " + e.getCause());
        } catch (TimeoutException e) {
            cancelled.set(true);
            throw new GameTools.ToolError("spawn timed out after " + ids.size() + " of " + s.count());
        }
        if (live.size() > 2000) live.values().removeIf(e -> !e.isValid());
        String group = "g" + groupSeq.incrementAndGet();
        groups.put(group, List.copyOf(ids));
        return new Spawned(group, base.getWorld().getName(), List.copyOf(ids), unloaded.get(), roofed.get(), s.type(), Map.copyOf(chunks));
    }

    private void configure(Entity e, SpawnSpec s, List<EquipmentSlot> slots, List<ItemStack> equipment, List<PotionEffect> effects) {
        var pdc = e.getPersistentDataContainer();
        if (s.name() != null) {
            e.customName(Component.text(ChatText.plain(s.name())));
            pdc.set(keepName, PersistentDataType.STRING, s.name());
        }
        if (s.baby() && e instanceof Ageable a) a.setBaby();
        if (s.tamedBy() != null && e instanceof Tameable t) {
            t.setOwner(Bukkit.getOfflinePlayer(s.tamedBy()));
            pdc.set(keepOwner, PersistentDataType.STRING, String.valueOf(t.getOwnerUniqueId()));
        }
        if (e instanceof LivingEntity le) {
            for (int i = 0; i < slots.size(); i++) if (le.getEquipment() != null) le.getEquipment().setItem(slots.get(i), equipment.get(i));
            effects.forEach(le::addPotionEffect);
        }
        if (s.invulnerableSeconds() > 0) {
            e.setInvulnerable(true);
            e.getScheduler().runDelayed(plugin, t -> e.setInvulnerable(false), null, s.invulnerableSeconds() * 20L);
        }
        if (s.lifetimeSeconds() > 0) {
            pdc.set(expires, PersistentDataType.LONG, System.currentTimeMillis() + s.lifetimeSeconds() * 1000L);
            e.getScheduler().runDelayed(plugin, t -> expire(e), null, s.lifetimeSeconds() * 20L);
        }
        if (s.landing() != null && !(e instanceof Item)) land(e, s.landing());
    }

    private void land(Entity e, String mode) {
        e.getPersistentDataContainer().set(noFall, PersistentDataType.BYTE, (byte) 1);
        if (e instanceof LivingEntity le) le.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 60, 0));
        e.getScheduler().runAtFixedRate(plugin, t -> {
            if (!e.isOnGround()) return;
            t.cancel();
            if (e instanceof LivingEntity le) le.removePotionEffect(PotionEffectType.SLOW_FALLING);
            switch (mode) {
                case "bounce" -> e.setVelocity(new org.bukkit.util.Vector(0, 0.45, 0));
                case "vanish" -> e.getScheduler().runDelayed(plugin, x -> {
                    e.getWorld().spawnParticle(Particle.POOF, e.getLocation().add(0, 0.5, 0), 12, 0.3, 0.3, 0.3, 0.02);
                    e.remove();
                }, null, 10);
                default -> {}
            }
        }, null, 5, 4);
    }

    /** Removes an entity whose lifetime ended, unless a player tamed it or named it since. Runs on the entity's scheduler. */
    private void expire(Entity e) {
        var pdc = e.getPersistentDataContainer();
        Long until = pdc.get(expires, PersistentDataType.LONG);
        if (until == null) return;
        String own = pdc.get(keepName, PersistentDataType.STRING);
        boolean renamed = e.customName() != null && (own == null || !Component.text(ChatText.plain(own)).equals(e.customName()));
        boolean tamed = e instanceof Tameable t && t.isTamed() && !String.valueOf(t.getOwnerUniqueId()).equals(pdc.get(keepOwner, PersistentDataType.STRING));
        if (renamed || tamed) {
            pdc.remove(expires);
            return;
        }
        e.remove();
    }

    /** Leftovers from before a restart: an entity whose lifetime passed is removed when its chunk loads, else rescheduled. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        long now = System.currentTimeMillis();
        for (Entity e : event.getEntities()) {
            Long until = e.getPersistentDataContainer().get(expires, PersistentDataType.LONG);
            if (until == null) continue;
            long ticks = Math.max(1, (until - now) / 50);
            e.getScheduler().runDelayed(plugin, t -> expire(e), null, ticks);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity().getPersistentDataContainer().has(noFall)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFirework(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework f && f.getPersistentDataContainer().has(harmless)) event.setCancelled(true);
    }

    /** Removes the entities in ids that still exist, each on its own scheduler. */
    void remove(List<UUID> ids) {
        for (UUID id : ids) {
            Entity e = live.remove(id);
            if (e != null) e.getScheduler().run(plugin, t -> e.remove(), null);
        }
    }

    /** Counts of entity types in ids that still exist, for routine reports. */
    static String countTypes(List<Entity> es) {
        Map<String, Integer> m = new TreeMap<>();
        for (Entity e : es) m.merge(e.getType().getKey().getKey(), 1, Integer::sum);
        return m.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "));
    }

    static Duration seconds(int s) { return s <= 0 ? null : Duration.ofSeconds(s); }

    static JsonNode json(String text) {
        try {
            return M.readTree(text);
        } catch (Exception e) {
            return M.createObjectNode();
        }
    }
}
