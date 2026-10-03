package com.lucascosolo.claudecontrol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/** Whether this JVM runs inside a container. Pure over the injected file and environment readers. */
public final class Container {
    private static final String[] RUNTIMES = {"docker", "containerd", "kubepods", "podman"};

    private Container() {}

    public static boolean detected() {
        return detected(Files::exists, p -> {
            try {
                return Files.readString(p);
            } catch (IOException e) {
                return null;
            }
        }, System::getenv);
    }

    public static boolean detected(Predicate<Path> exists, Function<Path, String> read, Function<String, String> env) {
        if (exists.test(Path.of("/.dockerenv"))) return true;
        String container = env.apply("container");
        if (container != null && !container.isBlank()) return true;
        String cgroup = read.apply(Path.of("/proc/1/cgroup"));
        if (cgroup == null) return false;
        String lower = cgroup.toLowerCase(Locale.ROOT);
        for (String r : RUNTIMES) if (lower.contains(r)) return true;
        return false;
    }
}
