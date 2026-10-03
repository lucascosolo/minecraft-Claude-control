package com.lucascosolo.claudecontrol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

/** The container entrypoint creates the boot marker before each start; a finished load removes it. */
public final class BootGuardListener implements Listener {
    private final Path marker;
    private final Logger log;

    public BootGuardListener(Path marker, Logger log) {
        this.marker = marker;
        this.log = log;
    }

    @EventHandler
    public void onLoad(ServerLoadEvent event) {
        try {
            if (Files.deleteIfExists(marker)) log.info("Server finished loading; removed " + marker.getFileName() + ".");
        } catch (IOException e) {
            log.warning("Cannot remove " + marker + ": " + e.getMessage());
        }
        Path dir = marker.getParent();
        try {
            Files.writeString(dir.resolve("claude-boot-failures"), "0\n");
            Path ok = dir.resolve("claude-boot-ok");
            if (Files.exists(ok)) Files.setLastModifiedTime(ok, FileTime.from(Instant.now()));
            else Files.createFile(ok);
        } catch (IOException e) {
            log.warning("Cannot record a successful boot in " + dir + ": " + e.getMessage());
        }
    }
}
