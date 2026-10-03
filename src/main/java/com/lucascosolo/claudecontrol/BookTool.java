package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

/** book: a written book to a player or into a container, or text on a sign. Region and player work runs on their own threads. */
final class BookTool implements SuiteTool {
    private static final ObjectMapper M = new ObjectMapper();
    private final SuiteTools suite;
    private final ContainerTool containers;

    BookTool(SuiteTools suite, ContainerTool containers) {
        this.suite = suite;
        this.containers = containers;
    }

    @Override
    public ToolOutcome run(JsonNode in, Authority a, String actors) throws GameTools.ToolError, InterruptedException {
        BookSpec s = BookSpec.parse(in);
        if (s.isSign()) {
            Location at = SuiteTools.point(s.signAt(), suite.base(s.signAt()));
            ChangeLog.Change change = suite.onBlock(at, "sign", () -> {
                Block b = at.getBlock();
                if (Graves.block(b)) throw new GameTools.ToolError("that block is a grave and is never touched.");
                if (!(b.getState(false) instanceof Sign sign)) throw new GameTools.ToolError("there is no sign at " + b.getX() + " " + b.getY() + " " + b.getZ() + ".");
                if (sign.isWaxed()) throw new GameTools.ToolError("that sign is waxed; waxed signs are left as they are.");
                String other = ContainerTool.foreignKey(sign, suite.plugin());
                if (other != null) throw new GameTools.ToolError("that sign belongs to another plugin (" + other + ").");
                var side = sign.getSide(Side.FRONT);
                List<String> before = new ArrayList<>(), after = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    before.add(PlainTextComponentSerializer.plainText().serialize(side.line(i)));
                    after.add(i < s.signLines().size() ? s.signLines().get(i) : "");
                    side.line(i, Component.text(after.get(i)));
                }
                sign.update(true, false);
                return new ChangeLog.SignChange(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), before, after);
            });
            return new ToolOutcome(M.createObjectNode().put("sign", "written").toString(), List.of(change));
        }
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle(s.title());
        meta.setAuthor(s.author() == null ? "Claude" : s.author());
        List<Component> pages = new ArrayList<>();
        for (String p : s.pages()) pages.add(Component.text(p));
        meta.addPages(pages.toArray(Component[]::new));
        book.setItemMeta(meta);
        if (s.toPlayer() != null) {
            Player p = Bukkit.getPlayerExact(s.toPlayer());
            if (p == null) throw new GameTools.ToolError("Player " + s.toPlayer() + " is not online.");
            boolean fit = suite.await(GameTools.onEntity(suite.plugin(), p, () -> p.getInventory().addItem(book).isEmpty()), "giving the book");
            if (!fit) throw new GameTools.ToolError(p.getName() + "'s inventory is full; nothing was given or dropped.");
            return new ToolOutcome(M.createObjectNode().put("book", s.title()).put("to", p.getName()).toString(),
                    List.of(new ChangeLog.ItemsGiven(p.getName(), "written_book", 1)));
        }
        Location at = SuiteTools.point(s.container(), suite.base(s.container()));
        ChangeLog.Change change = suite.onBlock(at, "book", () -> {
            Block b = at.getBlock();
            SuiteTools.refuse(ContainerTool.guard(b, suite.plugin()));
            SuiteTools.refuse(containers.gate(b, "add", a));
            var inv = ((Container) b.getState(false)).getInventory();
            List<String> before = ContainerTool.snapshot(inv.getContents());
            if (!inv.addItem(book).isEmpty()) throw new GameTools.ToolError("that container is full.");
            return new ChangeLog.ContainerChange(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), ContainerTool.half(b, inv), before,
                    ContainerTool.snapshot(inv.getContents()));
        });
        return new ToolOutcome(M.createObjectNode().put("book", s.title()).put("into", at.getWorld().getName() + " " + at.getBlockX() + " "
                + at.getBlockY() + " " + at.getBlockZ()).toString(), List.of(change));
    }
}
