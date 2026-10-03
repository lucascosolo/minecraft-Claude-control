package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * container: read, fill, add or clear a chest, barrel or other container, on the region thread that owns the block. Refuses a
 * container a player has open, graves and containers carrying another plugin's data; others' containers pass ContainerGate (B2).
 * Records the contents before and after (A12).
 */
final class ContainerTool implements SuiteTool, org.bukkit.event.Listener {
    private static final ObjectMapper M = new ObjectMapper();
    private final SuiteTools suite;
    private final NamespacedKey made;

    ContainerTool(SuiteTools suite) {
        this.suite = suite;
        this.made = new NamespacedKey(suite.plugin(), "made");
    }

    /** B2: marks a container as made by Claude (container create and build), so later calls may fill it. */
    static void stamp(Block b, Plugin plugin) {
        if (b.getState(false) instanceof Container c) {
            c.getPersistentDataContainer().set(new NamespacedKey(plugin, "made"), PersistentDataType.BYTE, (byte) 1);
            c.update(true, false);
        }
    }

    /** Another plugin's key on a block or entity, or null. */
    static String foreignKey(PersistentDataHolder h, Plugin plugin) {
        for (NamespacedKey k : h.getPersistentDataContainer().getKeys()) {
            if (!k.getNamespace().equals(plugin.getName().toLowerCase())) return k.getNamespace();
        }
        return null;
    }

    /** Why this block may not be touched at all, or null. Must run on the block's region. */
    static String guard(Block b, Plugin plugin) {
        if (Graves.block(b)) return "that block is a grave and is never touched.";
        if (!(b.getState(false) instanceof Container c)) return "there is no container at " + b.getX() + " " + b.getY() + " " + b.getZ()
                + " (it is " + b.getType().getKey().getKey() + ").";
        if (!c.getInventory().getViewers().isEmpty()) return "a player has that container open right now; try again later.";
        String other = foreignKey(c, plugin);
        return other == null ? null : "that container belongs to another plugin (" + other + ").";
    }

    /** B2: Claude's gate for this container under the turn's authority. Region thread only. */
    String gate(Block b, String action, Authority a) {
        Container c = (Container) b.getState(false);
        boolean stamped = c.getPersistentDataContainer().has(made, PersistentDataType.BYTE);
        Boolean other = null;
        if (c.getInventory().getHolder(false) instanceof DoubleChest dc) {
            Container half = dc.getLeftSide(false) instanceof Container l && l.getBlock().equals(b) ? (Container) dc.getRightSide(false) : (Container) dc.getLeftSide(false);
            other = half != null && half.getPersistentDataContainer().has(made, PersistentDataType.BYTE);
        }
        stamped = ContainerGate.stamped(stamped, other);
        Set<String> near = b.getLocation().toCenterLocation().getNearbyPlayers(6).stream().map(Player::getName).collect(Collectors.toSet());
        return ContainerGate.refusal(stamped, action, a, near);
    }

    /** C9: the first change a player makes to a stamped container's contents makes it theirs: the stamp goes from every half. */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (ContainerGate.playerChange(e.getRawSlot(), e.getView().getTopInventory().getSize(), e.getAction().name())) unstamp(e.getView().getTopInventory());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        int top = e.getView().getTopInventory().getSize();
        if (e.getRawSlots().stream().anyMatch(i -> i < top)) unstamp(e.getView().getTopInventory());
    }

    private void unstamp(Inventory top) {
        List<Container> halves = new ArrayList<>();
        var holder = top.getHolder(false);
        if (holder instanceof DoubleChest dc) {
            if (dc.getLeftSide(false) instanceof Container l) halves.add(l);
            if (dc.getRightSide(false) instanceof Container r) halves.add(r);
        } else if (holder instanceof Container c) halves.add(c);
        for (Container c : halves) {
            Block b = c.getBlock();
            if (!Bukkit.isOwnedByCurrentRegion(b)) continue;
            if (b.getState(false) instanceof Container live && live.getPersistentDataContainer().has(made, PersistentDataType.BYTE)) {
                live.getPersistentDataContainer().remove(made);
                live.update(true, false);
            }
        }
    }

    /** Which half of a double chest b is: single, left or right. */
    static String half(Block b, Inventory inv) {
        if (!(inv.getHolder(false) instanceof DoubleChest dc)) return "single";
        return dc.getLeftSide(false) instanceof Container l && l.getBlock().equals(b) ? "left" : "right";
    }

    @Override
    public ToolOutcome run(JsonNode in, Authority a, String actors) throws GameTools.ToolError, InterruptedException {
        ContainerSpec s = ContainerSpec.parse(in);
        SuiteTools.refuse(s.refusal(a));
        List<ItemStack> items = new ArrayList<>();
        for (ItemSpec i : s.items()) items.addAll(SuiteTools.stacks(i, a));
        Location at = SuiteTools.point(s.at(), suite.base(s.at()));
        World w = at.getWorld();
        List<ChangeLog.Change> changes = new ArrayList<>();
        ObjectNode out = M.createObjectNode();
        suite.onBlock(at, "container", () -> {
            Block b = w.getBlockAt(at);
            boolean created = false;
            if (s.create() != null && !(b.getState(false) instanceof Container)) {
                if (!b.getType().isAir() && !b.isReplaceable()) throw new GameTools.ToolError("cannot create a " + s.create() + " at "
                        + b.getX() + " " + b.getY() + " " + b.getZ() + ": the block there is " + b.getType().getKey().getKey() + ".");
                String before = b.getBlockData().getAsString();
                b.setType(s.create().equals("barrel") ? Material.BARREL : Material.CHEST, false);
                stamp(b, suite.plugin());
                changes.add(new ChangeLog.BlockChange(w.getName(), b.getX(), b.getY(), b.getZ(), before, b.getBlockData().getAsString()));
                out.put("created", s.create());
                created = true;
            }
            SuiteTools.refuse(guard(b, suite.plugin()));
            // C9: a new chest that joined a player's chest makes a double chest that is not Claude's.
            if (!created || ((Container) b.getState(false)).getInventory().getHolder(false) instanceof DoubleChest) {
                SuiteTools.refuse(gate(b, s.action(), a));
                if (s.name() != null) SuiteTools.refuse(gate(b, "fill", a));
            }
            Container c = (Container) b.getState(false);
            if (s.name() != null) {
                c.customName(Component.text(s.name()));
                c.update(true, false);
                c = (Container) b.getState(false);
            }
            Inventory inv = c.getInventory();
            List<String> before = snapshot(inv.getContents());
            switch (s.action()) {
                case "clear" -> inv.clear();
                case "fill", "add" -> {
                    if (s.action().equals("fill")) inv.clear();
                    int left = 0;
                    for (ItemStack st : items) left += inv.addItem(st.clone()).values().stream().mapToInt(ItemStack::getAmount).sum();
                    if (left > 0) out.put("did_not_fit", left);
                }
                default -> {}
            }
            if (!s.action().equals("read"))
                changes.add(new ChangeLog.ContainerChange(w.getName(), b.getX(), b.getY(), b.getZ(), half(b, inv), before, snapshot(inv.getContents())));
            ArrayNode now = out.putArray("contents");
            for (ItemStack st : inv.getContents()) if (st != null && !st.getType().isAir()) now.add(describe(st));
            out.put("at", w.getName() + " " + b.getX() + " " + b.getY() + " " + b.getZ()).put("type", b.getType().getKey().getKey());
            return null;
        });
        return new ToolOutcome(out.toString(), changes);
    }

    /** B2: the item's count and id, and its name quoted, cleaned and cut to 40 characters. */
    static String describe(ItemStack st) {
        String text = st.getAmount() + " " + st.getType().getKey().getKey();
        var meta = st.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return text;
        String name = ChatText.plain(PlainTextComponentSerializer.plainText().serialize(meta.displayName()));
        return text + " " + WakeStore.quote(name.length() > 40 ? name.substring(0, 40) : name);
    }

    static List<String> snapshot(ItemStack[] contents) {
        List<String> out = new ArrayList<>();
        for (ItemStack st : contents) out.add(st == null || st.getType().isAir() ? null : Base64.getEncoder().encodeToString(st.serializeAsBytes()));
        return out;
    }
}
