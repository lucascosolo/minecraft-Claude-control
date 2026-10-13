package com.lucascosolo.claudecontrol.core;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {
    private Path scratch() throws Exception {
        Path base = Path.of("build", "test-scratch").toAbsolutePath();
        Files.createDirectories(base);
        return Files.createTempDirectory(base, "memory-");
    }

    @Test void replacementAndArchivePreservePriorContent() throws Exception {
        Path root = scratch();
        MemoryStore memory = new MemoryStore(root);
        memory.write("town.md", "first plan");
        memory.write("town.md", "second plan");
        assertEquals("second plan", memory.read("town.md"));
        assertEquals(List.of("town.md"), memory.list());
        memory.archive("town.md");
        assertTrue(memory.list().isEmpty());
        try (var paths = Files.walk(root)) {
            var retained = paths.filter(Files::isRegularFile).map(path -> {
                try { return Files.readString(path); } catch (Exception e) { throw new AssertionError(e); }
            }).toList();
            assertTrue(retained.contains("first plan"));
            assertTrue(retained.contains("second plan"));
        }
    }

    @Test void rejectsTraversalAbsolutePathsAndLinksWithoutTouchingCanaries() throws Exception {
        Path fixture = scratch();
        Path root = fixture.resolve("store");
        Path canary = fixture.resolve("canary.md");
        Files.writeString(canary, "preserve");
        MemoryStore memory = new MemoryStore(root);
        for (String name : List.of("../canary.md", "nested/../../canary.md", canary.toString())) {
            assertThrows(Exception.class, () -> memory.write(name, "changed"));
            assertThrows(Exception.class, () -> memory.read(name));
            assertThrows(Exception.class, () -> memory.archive(name));
        }
        Files.createSymbolicLink(root.resolve("linked.md"), canary);
        assertThrows(Exception.class, () -> memory.read("linked.md"));
        assertThrows(Exception.class, () -> memory.write("linked.md", "changed"));
        assertThrows(Exception.class, () -> memory.archive("linked.md"));
        assertEquals("preserve", Files.readString(canary));
        assertTrue(Files.isSymbolicLink(root.resolve("linked.md")));
    }

    @Test void rejectsSymlinkedRoot() throws Exception {
        Path fixture = scratch();
        Path target = fixture.resolve("target");
        Files.createDirectories(target);
        Path link = fixture.resolve("store");
        Files.createSymbolicLink(link, target);
        assertThrows(Exception.class, () -> new MemoryStore(link));
    }

    @Test void enforcesUtf8ByteLimitWithoutReplacingOldValue() throws Exception {
        MemoryStore memory = new MemoryStore(scratch());
        memory.write("note.md", "preserve");
        assertThrows(Exception.class, () -> memory.write("note.md", "x".repeat(65537)));
        assertThrows(Exception.class, () -> memory.write("note.md", "é".repeat(32769)));
        assertEquals("preserve", memory.read("note.md"));
    }

    @Test void enforcesFileCountAndActiveByteBudget() throws Exception {
        MemoryStore many = new MemoryStore(scratch());
        for (int i = 0; i < 128; i++) many.write("note-" + i + ".md", "x");
        assertThrows(Exception.class, () -> many.write("overflow.md", "x"));
        assertEquals(128, many.list().size());
        many.write("note-0.md", "replacement");
        assertEquals("replacement", many.read("note-0.md"));
        MemoryStore large = new MemoryStore(scratch());
        for (int i = 0; i < 8; i++) large.write("large-" + i + ".md", "x".repeat(65536));
        assertThrows(Exception.class, () -> large.write("overflow.md", "x"));
        large.archive("large-0.md");
        large.write("within-budget.md", "x");
        assertEquals("x", large.read("within-budget.md"));
    }
}
