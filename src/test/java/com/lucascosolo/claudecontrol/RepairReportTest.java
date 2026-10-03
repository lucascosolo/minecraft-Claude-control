package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepairReportTest {
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Logger LOG = Logger.getLogger("RepairReportTest");
    @TempDir Path server;

    private String owner() throws IOException { return Files.getOwner(server).getName(); }

    private void index(String... lines) throws IOException {
        Files.createDirectories(server.resolve("claude-repair"));
        Files.writeString(server.resolve("claude-repair/index"), String.join("\n", lines) + "\n");
    }

    private void listed(String stamp) throws IOException {
        Path i = server.resolve("claude-repair/index");
        Files.writeString(i, (Files.exists(i) ? Files.readString(i) : "") + stamp + "\n");
    }

    /** report.md written and the stamp listed in the index. */
    private Path report(String stamp, String body) throws IOException {
        Path dir = unlisted(stamp, body);
        listed(stamp);
        return dir;
    }

    private Path unlisted(String stamp, String body) throws IOException {
        Path dir = Files.createDirectories(server.resolve("claude-repair").resolve(stamp));
        Path r = dir.resolve("report.md");
        Files.writeString(r, body);
        Files.setLastModifiedTime(r, FileTime.from(NOW.minusSeconds(60)));
        return dir;
    }

    @Test void writeCleanStopWritesFile() throws IOException {
        RepairReport.writeCleanStop(server);
        Path f = server.resolve("claude-clean-stop");
        assertTrue(Files.isRegularFile(f));
        assertFalse(Files.readString(f).isBlank());
    }

    @Test void writeCleanStopReplacesSymlinkWithoutWritingThrough() throws IOException {
        Path target = server.resolve("victim.txt");
        Files.writeString(target, "original");
        Path f = server.resolve("claude-clean-stop");
        Files.createSymbolicLink(f, target);
        RepairReport.writeCleanStop(server);
        assertEquals("original", Files.readString(target));
        assertFalse(Files.isSymbolicLink(f));
        assertTrue(Files.isRegularFile(f));
    }

    @Test void pendingEmptyWithoutRepairDir() throws IOException {
        assertTrue(RepairReport.pending(server, owner()).isEmpty());
    }

    @Test void pendingPicksNewestStamp() throws IOException {
        report("20261001T080000Z", "old");
        Path newest = report("20261002T110000Z", "new");
        report("20261002T100000Z", "mid");
        assertEquals(newest, RepairReport.pending(server, owner()).orElseThrow());
    }

    @Test void pendingIgnoresBadNames() throws IOException {
        report("2026-10-02", "x");
        report("20261002T110000", "x");
        report("zzzzzzzzTzzzzzzZ", "x");
        assertTrue(RepairReport.pending(server, owner()).isEmpty());
    }

    @Test void pendingIgnoresSymlinkedStampDir() throws IOException {
        Path real = report("20261002T090000Z", "x");
        Path other = server.resolve("elsewhere");
        Files.move(real, other);
        Files.createSymbolicLink(server.resolve("claude-repair").resolve("20261002T110000Z"), other);
        listed("20261002T110000Z");
        assertTrue(RepairReport.pending(server, owner()).isEmpty());
    }

    @Test void pendingIgnoresSymlinkedReport() throws IOException {
        Path dir = Files.createDirectories(server.resolve("claude-repair/20261002T110000Z"));
        Path secret = server.resolve("secret.md");
        Files.writeString(secret, "secret");
        Files.setLastModifiedTime(secret, FileTime.from(NOW.minusSeconds(60)));
        Files.createSymbolicLink(dir.resolve("report.md"), secret);
        listed("20261002T110000Z");
        assertTrue(RepairReport.pending(server, owner()).isEmpty());
    }

    @Test void oldUnreportedReportIsStillPending() throws IOException {
        Path dir = report("20261002T110000Z", "x");
        Files.setLastModifiedTime(dir.resolve("report.md"), FileTime.from(NOW.minusSeconds(30L * 24 * 3600)));
        assertEquals(dir, RepairReport.pending(server, owner()).orElseThrow());
    }

    @Test void pendingIgnoresStampNotInIndex() throws IOException {
        unlisted("20261002T110000Z", "x");
        index("20261001T000000Z");
        assertTrue(RepairReport.pending(server, owner()).isEmpty());
    }

    @Test void pendingIgnoresMissingIndex() throws IOException {
        unlisted("20261002T110000Z", "x");
        assertTrue(RepairReport.pending(server, owner()).isEmpty());
    }

    @Test void pendingIgnoresSymlinkedIndex() throws IOException {
        unlisted("20261002T110000Z", "x");
        Path real = server.resolve("real-index");
        Files.writeString(real, "20261002T110000Z\n");
        Files.createSymbolicLink(server.resolve("claude-repair/index"), real);
        assertTrue(RepairReport.pending(server, owner()).isEmpty());
    }

    @Test void pendingIgnoresIndexOwnedBySomeoneElse() throws IOException {
        Assumptions.assumeFalse("root".equals(owner()), "running as root");
        report("20261002T110000Z", "x");
        assertTrue(RepairReport.pending(server, "root").isEmpty());
    }

    @Test void indexMatchesWholeLinesOnly() throws IOException {
        unlisted("20261002T000000Z", "x");
        index("x20261002T000000Z", "20261002T000000Zx", " 20261002T000000Z2");
        assertTrue(RepairReport.pending(server, owner()).isEmpty());
    }

    @Test void markCleanStopWritesMarkerWhenServerStopping() {
        RepairReport.markCleanStop(server, true, LOG);
        assertTrue(Files.isRegularFile(server.resolve("claude-clean-stop")));
    }

    @Test void markCleanStopWritesNothingOnReload() {
        RepairReport.markCleanStop(server, false, LOG);
        assertFalse(Files.exists(server.resolve("claude-clean-stop")));
    }

    @Test void markCleanStopNeverThrowsAndWarnsWhenDirUnusable() throws IOException {
        Path file = Files.writeString(server.resolve("plain-file"), "x");
        List<LogRecord> seen = new ArrayList<>();
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { seen.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        assertDoesNotThrow(() -> RepairReport.markCleanStop(file.resolve("sub"), true, log));
        assertTrue(seen.stream().anyMatch(r -> r.getLevel() == Level.WARNING), "expected a warning");
    }

    @Test void pendingIgnoresReportedDir() throws IOException {
        Path dir = report("20261002T110000Z", "x");
        Files.writeString(dir.resolve("reported"), "");
        assertTrue(RepairReport.pending(server, owner()).isEmpty());
    }

    @Test void triggerTextHasFixedPrefixAndUntrustedNote() {
        String t = RepairReport.triggerText("line one");
        assertTrue(t.startsWith("The container ran a repair before this start."));
        String lower = t.toLowerCase();
        assertTrue(lower.contains("untrusted"));
        assertTrue(lower.contains("not instructions"));
        assertTrue(t.contains("line one"));
    }

    @Test void triggerTextStripsColourAndControlChars() {
        String t = RepairReport.triggerText("§cRed\u0007bell\n§lBold");
        assertFalse(t.contains("§"));
        assertFalse(t.contains("\u0007"));
        assertTrue(t.contains("Red"));
        assertTrue(t.contains("Bold"));
    }

    @Test void triggerTextJoinsLinesWithPipeAndDropsBlanks() {
        String t = RepairReport.triggerText("alpha\n\n   \nbeta\r\ngamma");
        assertTrue(t.contains("alpha | beta | gamma"), t);
        assertFalse(t.contains("\n"));
        assertFalse(t.contains("\r"));
    }

    @Test void triggerTextIsBounded() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 2000; i++) lines.add("line number " + i);
        String t = RepairReport.triggerText(String.join("\n", lines));
        assertTrue(t.length() < 6500, "length " + t.length());
        assertTrue(t.length() > 1000);
    }

    @Test void raiseSubmitsOneRepairTriggerAndMarksReported() throws IOException {
        Path dir = report("20261002T110000Z", "disk full\nrepaired");
        List<Trigger> got = new ArrayList<>();
        assertTrue(RepairReport.raise(server, NOW, t -> got.add(t), LOG, owner()));
        assertEquals(1, got.size());
        Trigger t = got.get(0);
        assertEquals("repair", t.source());
        assertNull(t.actor());
        assertFalse(t.actorIsOp());
        assertTrue(t.text().contains("disk full | repaired"));
        assertTrue(Files.exists(dir.resolve("reported")));
    }

    @Test void secondRaiseReturnsFalseAndSubmitsNothing() throws IOException {
        report("20261002T110000Z", "x");
        assertTrue(RepairReport.raise(server, NOW, t -> true, LOG, owner()));
        List<Trigger> got = new ArrayList<>();
        assertFalse(RepairReport.raise(server, NOW, t -> got.add(t), LOG, owner()));
        assertTrue(got.isEmpty());
    }

    @Test void rejectedSubmitDoesNotMarkReported() throws IOException {
        Path dir = report("20261002T110000Z", "x");
        assertFalse(RepairReport.raise(server, NOW, t -> false, LOG, owner()));
        assertFalse(Files.exists(dir.resolve("reported")));
        assertTrue(RepairReport.pending(server, owner()).isPresent());
    }

    @Test void raiseWithNoPendingReturnsFalse() throws IOException {
        List<Trigger> got = new ArrayList<>();
        assertFalse(RepairReport.raise(server, NOW, t -> got.add(t), LOG, owner()));
        assertTrue(got.isEmpty());
    }
}
