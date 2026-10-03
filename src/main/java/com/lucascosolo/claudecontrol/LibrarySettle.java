package com.lucascosolo.claudecontrol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** E3: settles plugins/ jars whose base is also in claude-library/, keyed on the activation marker's sha and the jars' content. */
final class LibrarySettle {
    private LibrarySettle() {}

    interface Quarantine { void move(Path jar) throws IOException; }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A matching marker plus any plugins/ jar with the library's sha completes the activation (the base's other plugins/ jars go to quarantine); otherwise every plugins/ jar of the base is quarantined. */
    static void settle(Path pluginsDir, Path libDir, Map<String, String> library, Function<String, String> markerSha,
                       Function<Path, String> baseOf, Quarantine quarantine, Consumer<String> warn) {
        Map<String, List<Path>> byBase = new LinkedHashMap<>();
        try (var s = Files.list(pluginsDir)) {
            for (Path jar : s.filter(j -> j.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                String b = baseOf.apply(jar);
                if (b != null && library.containsKey(b)) byBase.computeIfAbsent(b, k -> new ArrayList<>()).add(jar);
            }
        } catch (IOException e) {
            warn.accept("Cannot list " + pluginsDir + ": " + e.getMessage());
            return;
        }
        byBase.forEach((b, jars) -> {
            String libSha = library.get(b), marker = markerSha.apply(b);
            if (marker != null && marker.equals(libSha) && jars.stream().anyMatch(j -> shaOf(j, warn).equals(libSha))) {
                for (Path jar : jars) {
                    if (shaOf(jar, warn).equals(marker)) continue;
                    try {
                        quarantine.move(jar);
                    } catch (IOException e) {
                        warn.accept("Cannot settle " + jar.getFileName() + ": " + e.getMessage());
                    }
                }
                try {
                    Files.deleteIfExists(libDir.resolve(b + ".jar"));
                    library.remove(b);
                } catch (IOException e) {
                    warn.accept("Cannot drop the library copy of " + b + ": " + e.getMessage());
                }
                return;
            }
            for (Path jar : jars) {
                try {
                    quarantine.move(jar);
                } catch (IOException e) {
                    warn.accept("Cannot settle " + jar.getFileName() + ": " + e.getMessage());
                }
            }
        });
    }

    private static String shaOf(Path jar, Consumer<String> warn) {
        try {
            return sha256(Files.readAllBytes(jar));
        } catch (IOException e) {
            warn.accept("Cannot read " + jar.getFileName() + ": " + e.getMessage());
            return "";
        }
    }
}
