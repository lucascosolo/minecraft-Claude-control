package com.lucascosolo.claudecontrol.core;

import com.lucascosolo.claudecontrol.core.bootstrap.RuntimeArchive;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeArchiveTest {
    private static final String CORE = "com/lucascosolo/claudecontrol/core/";
    private Path scratch() throws IOException {
        Path root = Path.of("build", "test-scratch").toAbsolutePath();
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "runtime-archive-");
    }
    private Map<String, byte[]> supported() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(CORE + "ClaudeControlPlugin.class", new byte[]{1, 2});
        entries.put(CORE + "ClaudeControlPlugin$Helper.class", new byte[]{3});
        entries.put(CORE + "bootstrap/RuntimeLifecycle.class", new byte[]{4});
        entries.put(CORE + "bootstrap/RuntimeArchive.class", new byte[]{5});
        entries.put(CORE + "ClaudeRuntime.class", new byte[]{6});
        return entries;
    }
    private Path jar(Path path, Map<String, byte[]> entries) throws IOException {
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }
    @Test void requiresRuntimeMainAndLifecycleEntriesInAnActualJar() throws Exception {
        Path root = scratch();
        Map<String, byte[]> validated = RuntimeArchive.validate(jar(root.resolve("good.jar"), supported()));
        assertArrayEquals(new byte[]{1, 2}, validated.get(CORE + "ClaudeControlPlugin.class"));
        for (String required : List.of("ClaudeRuntime.class", "ClaudeControlPlugin.class", "bootstrap/RuntimeLifecycle.class")) {
            var entries = supported();
            entries.remove(CORE + required);
            Path incomplete = jar(root.resolve(required.replace('/', '-') + ".jar"), entries);
            assertThrows(IOException.class, () -> RuntimeArchive.validate(incomplete));
        }
        Path text = Files.writeString(root.resolve("text.jar"), "not a jar");
        assertThrows(IOException.class, () -> RuntimeArchive.validate(text));
        assertThrows(IOException.class, () -> RuntimeArchive.validate(root));
    }
    @Test void rejectsUnsafeEntryNames() throws Exception {
        Path root = scratch();
        int index = 0;
        for (String name : List.of("../escape.class", "/absolute.class", "nested/../escape.class", "nested\\escape.class")) {
            var entries = supported();
            entries.put(name, new byte[]{7});
            Path invalid = jar(root.resolve("unsafe-" + index++ + ".jar"), entries);
            assertThrows(IOException.class, () -> RuntimeArchive.validate(invalid), name);
        }
    }
    @Test void rejectsDuplicateEntriesAndOversizedInputFiles() throws Exception {
        Path root = scratch();
        var entries = supported();
        entries.put("duplicate-one", new byte[]{1});
        entries.put("duplicate-two", new byte[]{2});
        Path duplicate = jar(root.resolve("duplicate.jar"), entries);
        byte[] content = Files.readAllBytes(duplicate);
        byte[] originalName = "duplicate-two".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] repeatedName = "duplicate-one".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int replacements = 0;
        for (int offset = 0; offset <= content.length - originalName.length; offset++) {
            if (java.util.Arrays.equals(content, offset, offset + originalName.length, originalName, 0, originalName.length)) {
                System.arraycopy(repeatedName, 0, content, offset, repeatedName.length);
                replacements++;
            }
        }
        assertEquals(2, replacements, "Change the local and central-directory names only");
        Files.write(duplicate, content);
        assertThrows(IOException.class, () -> RuntimeArchive.validate(duplicate));
        Path oversized = root.resolve("oversized.jar");
        try (var channel = Files.newByteChannel(oversized, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(64L * 1024 * 1024);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }
        assertThrows(IOException.class, () -> RuntimeArchive.validate(oversized));
    }
    @Test void rejectsCompressedArchiveExpansionBeyondBudget() throws Exception {
        Path root = scratch();
        Path expanded = root.resolve("expanded.jar");
        byte[] block = new byte[1024 * 1024];
        try (var output = new JarOutputStream(Files.newOutputStream(expanded))) {
            for (var entry : supported().entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry("large.bin"));
            for (int i = 0; i < 65; i++) output.write(block);
            output.closeEntry();
        }
        assertTrue(Files.size(expanded) < 1024 * 1024);
        assertThrows(IOException.class, () -> RuntimeArchive.validate(expanded));
    }
    @Test void allowsImplementationChangesButRequiresExactStableClasses() throws Exception {
        Path root = scratch();
        Path baseline = jar(root.resolve("baseline.jar"), supported());
        var next = supported();
        next.put(CORE + "ClaudeRuntime.class", new byte[]{8, 9});
        next.put(CORE + "Settings.class", new byte[]{10});
        assertDoesNotThrow(() -> RuntimeArchive.compatible(baseline, jar(root.resolve("runtime-change.jar"), next)));
        int index = 0;
        for (String stable : List.of("ClaudeControlPlugin.class", "ClaudeControlPlugin$Helper.class", "bootstrap/RuntimeArchive.class")) {
            var changed = supported();
            changed.put(CORE + stable, new byte[]{99});
            Path replacement = jar(root.resolve("changed-" + index++ + ".jar"), changed);
            assertThrows(IOException.class, () -> RuntimeArchive.compatible(baseline, replacement));
            changed.remove(CORE + stable);
            Path missing = jar(root.resolve("missing-" + index++ + ".jar"), changed);
            assertThrows(IOException.class, () -> RuntimeArchive.compatible(baseline, missing));
        }
        var added = supported();
        added.put(CORE + "bootstrap/NewStable.class", new byte[]{11});
        Path extra = jar(root.resolve("extra.jar"), added);
        assertThrows(IOException.class, () -> RuntimeArchive.compatible(baseline, extra));
    }
    @Test void snapshotsAreUniqueCopiesAndRetainEarlierBytes() throws Exception {
        Path root = scratch();
        Path source = jar(root.resolve("source.jar"), supported());
        byte[] original = Files.readAllBytes(source);
        Path archive = root.resolve("archive");
        Path first = RuntimeArchive.snapshot(source, archive);
        Path second = RuntimeArchive.snapshot(source, archive);
        assertNotEquals(first, second);
        assertEquals(archive, first.getParent());
        assertArrayEquals(original, Files.readAllBytes(first));
        assertArrayEquals(original, Files.readAllBytes(second));
        var changed = supported();
        changed.put(CORE + "ClaudeRuntime.class", new byte[]{42});
        jar(source, changed);
        assertArrayEquals(original, Files.readAllBytes(first));
        assertTrue(Files.exists(source));
    }
    @Test void rejectsSymlinkSourcesAndArchiveDirectoriesWithoutTouchingCanary() throws Exception {
        Path root = scratch();
        Path target = Files.createDirectory(root.resolve("target"));
        Path source = jar(target.resolve("source.jar"), supported());
        Path canary = Files.writeString(target.resolve("canary"), "preserve");
        Path sourceLink = Files.createSymbolicLink(root.resolve("source-link.jar"), source);
        assertThrows(IOException.class, () -> RuntimeArchive.validate(sourceLink));
        Path directoryLink = Files.createSymbolicLink(root.resolve("directory-link"), target);
        assertThrows(IOException.class, () -> RuntimeArchive.validate(directoryLink.resolve("source.jar")));
        assertThrows(IOException.class, () -> RuntimeArchive.snapshot(source, directoryLink));
        assertThrows(IOException.class, () -> RuntimeArchive.snapshot(source, directoryLink.resolve("nested")));
        assertEquals("preserve", Files.readString(canary));
        try (var entries = Files.list(target)) { assertEquals(2, entries.count()); }
    }
}
