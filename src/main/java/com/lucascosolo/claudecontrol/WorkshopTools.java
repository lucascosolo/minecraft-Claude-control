package com.lucascosolo.claudecontrol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/** The workshop tools: install (with hot replace), list, disable Claude-written plugins, and warned restarts. */
public final class WorkshopTools {
    /** A successful install: loaded now (loadedAs is the server's plugin name, maybe with a revision suffix) or staged for a restart. */
    public record InstallOutcome(String name, String version, String sha256, boolean loadedAtRuntime, String loadedAs, String note) {
        String text() {
            return loadedAtRuntime ? "Installed, loaded and enabled " + name + " " + version + (note.isEmpty() ? "." : " (" + note + ").")
                    : name + " " + version + " is installed but only takes effect after a server restart: " + note;
        }
    }

    /** The plugin itself failed while enabling; its jar is quarantined and no restart would help. */
    private static final class PluginBroken extends Exception {
        PluginBroken(String message) { super(message); }
    }

    private static final String ACTIVATING = ".activating";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,32}");
    private static final Pattern REVISION = Pattern.compile("^(.*)_r(\\d+)$");
    private final Plugin plugin;
    private final Path workshop, pluginsDir, quarantine, restartFile;
    private final Logger log;
    private final Set<String> awaitingRestart = new LinkedHashSet<>();
    private volatile RestartLimiter restarts;
    private JsonFileStore restartStore;
    private final Path libDir;
    private PluginLibrary library;
    private final Map<String, Instant> shelveRetry = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Function<String, String> watchInfo = n -> "";
    /** Null while workshop mode is active, else why it is not. */
    private volatile String offReason = "Workshop mode is off.";

    public WorkshopTools(Plugin plugin, Path workshop, Path pluginsDir, Path restartFile) {
        this.plugin = plugin;
        this.workshop = workshop;
        this.pluginsDir = pluginsDir;
        this.quarantine = pluginsDir.resolve("claude-quarantine");
        this.libDir = pluginsDir.resolve("claude-library");
        this.restartFile = restartFile;
        this.log = plugin.getLogger();
    }

    public void setLibrary(PluginLibrary library) { this.library = library; }

    PluginLibrary library() { return library; }

    public void setMode(boolean active, String offReason) { this.offReason = active ? null : offReason; }

    public void setWatchInfo(Function<String, String> info) { this.watchInfo = info; }

    /** New limits keep the restart history, which is persisted so the budget survives the restarts it counts. */
    public synchronized void configureRestarts(int minMinutesBetween, int maxPerDay) {
        RestartLimiter l = new RestartLimiter(Clock.systemUTC(), minMinutesBetween, maxPerDay);
        restartStore = new JsonFileStore(restartFile, Clock.systemUTC());
        List<Instant> past = new ArrayList<>();
        try {
            String raw = Files.exists(restartFile) ? Files.readString(restartFile) : "";
            if (!raw.isBlank() && !raw.stripLeading().startsWith("{")) {
                for (String line : raw.split("\\R")) if (!line.isBlank()) past.add(Instant.parse(line.strip()));
                saveRestartHistory(past);
            } else {
                past.addAll(JsonFileStore.entries(restartStore.read().path("restarts"), n -> Instant.parse(n.asText())));
                if (restartStore.lastCorrupt() != null) log.warning("Restart history was unreadable; moved to " + restartStore.lastCorrupt());
            }
        } catch (IOException | RuntimeException e) {
            log.warning("Cannot read " + restartFile + ": " + e.getMessage());
        }
        l.restore(past);
        restarts = l;
    }

    private void saveRestartHistory(List<Instant> times) {
        var root = JsonFileStore.emptyObject();
        var arr = root.putArray("restarts");
        times.forEach(t -> arr.add(t.toString()));
        restartStore.write(root);
    }

    public void requireActive() throws GameTools.ToolError {
        String why = offReason;
        if (why != null) throw new GameTools.ToolError(why);
    }

    static String base(String pluginName) {
        Matcher m = REVISION.matcher(pluginName);
        return m.matches() ? m.group(1) : pluginName;
    }

    private static int revision(String pluginName) {
        Matcher m = REVISION.matcher(pluginName);
        return m.matches() ? Integer.parseInt(m.group(2)) : 1;
    }

    static boolean claudeWritten(String name) {
        String b = base(name);
        return b.startsWith("Claude") && !b.equals("ClaudeControl");
    }

    /** What install_plugin says about the plugin for the catalogue; maySafe is whether an owner asked in the turn (C8). */
    public record Meta(String until, String purpose, String usage, boolean changesWorld, boolean cleansUp, boolean safeForEvents, String writtenFor, boolean maySafe) {}

    public InstallOutcome install(String name, Meta meta) throws GameTools.ToolError, InterruptedException {
        requireActive();
        if (name == null || !NAME.matcher(name).matches()) throw new GameTools.ToolError("name must match [A-Za-z0-9_]{3,32}.");
        if (meta.until() != null) {
            try {
                PluginLibrary.checkUntil(meta.until());
            } catch (IllegalArgumentException e) {
                throw new GameTools.ToolError(e.getMessage());
            }
        }
        boolean isNew = library.get(name).isEmpty();
        if (isNew && (blank(meta.purpose()) || blank(meta.usage())))
            throw new GameTools.ToolError("First install of " + name + ": give purpose (one line) and usage (commands, triggers, options) for the plugin library.");
        Path libs = workshop.resolve(name).resolve("build").resolve("libs");
        List<Path> jars;
        try (Stream<Path> s = Files.list(libs)) {
            jars = s.filter(p -> p.toString().endsWith(".jar")).toList();
        } catch (IOException e) {
            throw new GameTools.ToolError("No workshop/" + name + "/build/libs/; build the plugin first.");
        }
        if (jars.size() != 1) throw new GameTools.ToolError("workshop/" + name + "/build/libs/ must hold exactly one jar, found " + jars.size() + ".");
        byte[] jar;
        // Read once: the checks and the install all use these bytes, so swapping the file afterwards changes nothing.
        try (InputStream in = Files.newInputStream(jars.getFirst())) {
            jar = in.readNBytes(JarBytes.MAX_JAR + 1);
        } catch (IOException e) {
            throw new GameTools.ToolError("Cannot read " + jars.getFirst().getFileName() + ": " + e.getMessage());
        }
        if (isNew) {
            try {
                library.add(name, meta.purpose(), meta.usage(), meta.changesWorld(), meta.cleansUp(), meta.writtenFor());
            } catch (IllegalArgumentException e) {
                throw new GameTools.ToolError(e.getMessage());
            }
        }
        InstallOutcome o;
        try {
            o = checkAndLoad(name, jar);
        } catch (GameTools.ToolError | InterruptedException | RuntimeException e) {
            if (isNew) library.discard(name);
            throw e;
        }
        String extra = "";
        try {
            PluginLibrary.Entry e = library.get(name).orElseThrow();
            if (e.state().equals("shelved")) {
                library.activate(name, e.sha256(), meta.until());
                Files.deleteIfExists(libDir.resolve(name + ".jar"));
            } else library.setLifetime(name, meta.until());
            if (meta.safeForEvents()) {
                String refused = LibraryGate.safeForEventsRefusal(meta.maySafe(), library.get(name).orElseThrow().changesWorld());
                if (refused == null) library.setSafeForEvents(name, true);
                else extra = refused;
            }
        } catch (IOException | RuntimeException e) {
            log.warning("Plugin library not updated for " + name + ": " + e.getMessage());
        }
        writeLifetimes();
        return extra.isEmpty() ? o : new InstallOutcome(o.name(), o.version(), o.sha256(), o.loadedAtRuntime(), o.loadedAs(),
                o.note().isEmpty() ? extra : o.note() + "; " + extra);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    /** The checks and the load shared by install and activate: manifest allowlist, jar scan, then a hot load (or hot replace) of these bytes. */
    private InstallOutcome checkAndLoad(String name, byte[] jar) throws GameTools.ToolError, InterruptedException {
        PluginManifest.Manifest m;
        String sha;
        try {
            Map<String, byte[]> entries = JarBytes.read(jar);
            if (entries.containsKey("paper-plugin.yml")) throw new GameTools.ToolError("paper-plugin.yml is not allowed; use plugin.yml.");
            byte[] yml = entries.get("plugin.yml");
            if (yml == null) throw new GameTools.ToolError("The jar has no plugin.yml.");
            m = PluginManifest.validate(new String(yml, StandardCharsets.UTF_8), Set.of());
            if (!m.name().equals(name)) throw new GameTools.ToolError("plugin.yml says name: " + m.name() + ", but the plugin is " + name + ".");
            Map<String, byte[]> classes = new LinkedHashMap<>();
            entries.forEach((k, v) -> { if (k.endsWith(".class")) classes.put(k, v); });
            List<String> findings = JarScan.scan(classes);
            if (!findings.isEmpty()) {
                log.warning("[audit] INSTALL refused for " + name + ": " + String.join(" | ", findings));
                throw new GameTools.ToolError("Refused: the jar scan found\n" + String.join("\n", findings));
            }
            sha = sha256(jar);
        } catch (PluginManifest.Invalid e) {
            throw new GameTools.ToolError("plugin.yml: " + e.getMessage());
        } catch (JarBytes.Rejected e) {
            throw new GameTools.ToolError("Refused: " + e.getMessage());
        }
        Plugin old = loaded(name);
        log.info("[audit] INSTALL " + name + " " + m.version() + " sha256 " + sha.substring(0, 12) + (old == null ? "" : ", replacing " + old.getName()));
        try {
            if (old == null) {
                Path target = pluginsDir.resolve(name + ".jar");
                writeJar(target, jar);
                onGlobal(() -> loadAndEnable(target, null));
                return done(new InstallOutcome(name, m.version(), sha, true, name, ""));
            }
            try {
                String as = onGlobal(() -> hotReplace(old, jar, name));
                return done(new InstallOutcome(name, m.version(), sha, true, as, "hot-replaced the running version"));
            } catch (GameTools.ToolError e) {
                if (e.getCause() instanceof PluginBroken) throw e;
                // The server refused a runtime replace: the old instance is off and its jar quarantined, so the new jar loads on restart.
                for (Path j : jars(pluginsDir)) {
                    String n = nameOf(j);
                    if (n != null && base(n).equals(name)) moveToQuarantine(j);
                }
                writeJar(pluginsDir.resolve(name + ".jar"), jar);
                synchronized (awaitingRestart) { awaitingRestart.add(name); }
                log.warning("[audit] INSTALL " + name + ": hot replace failed (" + e.getMessage() + "); awaiting a restart");
                return new InstallOutcome(name, m.version(), sha, false, name, "hot replace failed (" + e.getMessage()
                        + "). Ask the players, then use restart_server.");
            }
        } catch (IOException e) {
            throw new GameTools.ToolError("Cannot copy the jar: " + e.getMessage());
        }
    }

    /** Moves one jar out of plugins/claude-held/ into plugins/ and hot-loads it. Blocks on the global thread: call it off-tick. */
    public InstallOutcome loadHeld(Path jar) throws Exception {
        String name = nameOf(jar);
        if (name == null) throw new GameTools.ToolError(jar.getFileName() + " is not a readable plugin jar");
        if (loaded(base(name)) != null) throw new GameTools.ToolError(base(name) + " is already loaded");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        Path target = Files.move(jar, pluginsDir.resolve(jar.getFileName()));
        String as = onGlobal(() -> loadAndEnable(target, null));
        return new InstallOutcome(base(name), "", sha, true, as, "returned from claude-held");
    }

    private InstallOutcome done(InstallOutcome o) {
        log.info("[audit] INSTALL " + o.name() + " " + o.version() + " loaded and enabled" + (o.loadedAs().equals(o.name()) ? "" : " as " + o.loadedAs()));
        return o;
    }

    /** On the global region thread. Throws PluginBroken (jar quarantined) when the plugin's own enable fails. */
    private String loadAndEnable(Path jar, Plugin old) throws Exception {
        PluginManager pm = Bukkit.getPluginManager();
        Plugin p = pm.loadPlugin(jar.toFile());
        if (p == null || p == old) throw new IllegalStateException("the server did not load a new instance of " + jar.getFileName());
        pm.enablePlugin(p);
        if (!p.isEnabled()) {
            Path q = moveToQuarantine(jar);
            log.warning("[audit] INSTALL " + base(p.getName()) + " failed to enable; moved to " + q);
            throw new PluginBroken(base(p.getName()) + " failed while enabling (see its exception in the server log); the jar was moved to plugins/claude-quarantine/.");
        }
        return p.getName();
    }

    /**
     * Disable the old instance, then load the new jar under the next revision name (ClaudeHello_r2): Folia 26.1.2 refuses a second
     * plugin of the same name in one run ("duplicate plugin identifier"), verified live.
     */
    private String hotReplace(Plugin old, byte[] newJar, String base) throws Exception {
        PluginManager pm = Bukkit.getPluginManager();
        Path oldJar = jarOf(old.getName());
        if (old.isEnabled()) pm.disablePlugin(old);
        String rev = base + "_r" + nextRevision(base);
        if (oldJar != null) moveToQuarantine(oldJar);
        Path revJar = pluginsDir.resolve(rev + ".jar");
        rewriteName(newJar, revJar, rev);
        return loadAndEnable(revJar, old);
    }

    private int nextRevision(String base) {
        int max = 1;
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) if (base(p.getName()).equals(base)) max = Math.max(max, revision(p.getName()));
        for (Path jar : jars(pluginsDir)) {
            String n = nameOf(jar);
            if (n != null && base(n).equals(base)) max = Math.max(max, revision(n));
        }
        return max + 1;
    }

    private static void rewriteName(byte[] src, Path dst, String newName) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(src)); OutputStream os = Files.newOutputStream(dst);
             ZipOutputStream out = new ZipOutputStream(os)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                out.putNextEntry(new ZipEntry(e.getName()));
                byte[] bytes = in.readAllBytes();
                if (e.getName().equals("plugin.yml")) {
                    bytes = new String(bytes, StandardCharsets.UTF_8).replaceFirst("(?m)^name:.*$", "name: " + newName).getBytes(StandardCharsets.UTF_8);
                }
                out.write(bytes);
                out.closeEntry();
            }
        }
    }

    /** The loaded instance for a base name, preferring an enabled one. */
    private static Plugin loaded(String base) {
        Plugin best = null;
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (!base(p.getName()).equals(base)) continue;
            if (best == null || (p.isEnabled() && !best.isEnabled()) || (p.isEnabled() == best.isEnabled() && revision(p.getName()) > revision(best.getName()))) best = p;
        }
        return best;
    }

    /** Writes under a non-.jar temporary name and renames, so the plugin loader never sees a half-written jar in plugins/. */
    private static void writeJar(Path target, byte[] jar) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + "." + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong()) + ".tmp");
        try {
            Files.write(tmp, jar);
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Rewrites a jar into plugins/claude-library/<base>.jar under its base plugin name (replacing an older copy), deletes the source, returns the stored sha-256. */
    private String intoLibrary(Path src, String base) throws IOException {
        Files.createDirectories(libDir);
        Path dest = libDir.resolve(base + ".jar"), tmp = libDir.resolve(base + ".jar.tmp");
        rewriteName(Files.readAllBytes(src), tmp, base);
        Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (!src.equals(dest)) Files.delete(src);
        return sha256(Files.readAllBytes(dest));
    }

    /** One line "<epochSeconds> <Base>" per active plugin with a lifetime, for entrypoint.sh to shelve at boot what ran out while the server was down. */
    private void writeLifetimes() {
        try {
            Files.createDirectories(libDir);
            StringBuilder sb = new StringBuilder();
            for (PluginLibrary.Entry e : library.list()) if (e.state().equals("active") && e.until() != null) sb.append(e.until().getEpochSecond()).append(' ').append(e.name()).append('\n');
            Files.writeString(libDir.resolve("lifetimes.txt"), sb.toString());
        } catch (IOException e) {
            log.warning("Cannot write lifetimes.txt: " + e.getMessage());
        }
    }

    /** Hot-disables the plugin, moves its newest jar into the library and records it as shelved. Players are not interrupted. */
    public String shelve(String name) throws GameTools.ToolError, InterruptedException {
        requireActive();
        if (name == null || !NAME.matcher(name).matches() || !claudeWritten(name)) throw new GameTools.ToolError("Only plugins whose name starts with Claude (not ClaudeControl) can be shelved.");
        String b = base(name);
        if (library.get(b).filter(e -> e.state().equals("shelved")).isPresent()) throw new GameTools.ToolError(b + " is already shelved.");
        PluginManager pm = Bukkit.getPluginManager();
        List<Plugin> instances = new ArrayList<>();
        for (Plugin p : pm.getPlugins()) if (base(p.getName()).equals(b)) instances.add(p);
        List<Path> mine = new ArrayList<>();
        for (Path j : jars(pluginsDir)) {
            String n = nameOf(j);
            if (n != null && base(n).equals(b)) mine.add(j);
        }
        if (mine.isEmpty()) throw new GameTools.ToolError("No jar of " + b + " in plugins/ to shelve.");
        mine.sort((x, y) -> Integer.compare(revision(nameOf(y)), revision(nameOf(x))));
        PluginLibrary.Entry prev = library.get(b).orElse(null);
        if (prev == null) library.add(b, "not recorded", "not recorded", false, false, "unknown");
        try {
            library.begin(b, "shelving");
            onGlobal(() -> {
                for (Plugin p : instances) if (p.isEnabled()) pm.disablePlugin(p);
                return null;
            });
            String sha = intoLibrary(mine.getFirst(), b);
            for (Path older : mine.subList(1, mine.size())) moveToQuarantine(older);
            library.shelve(b, sha);
        } catch (GameTools.ToolError | InterruptedException | IOException | RuntimeException x) {
            if (prev == null) library.discard(b);
            else library.restore(prev);
            String disabled = prev != null && prev.state().equals("active") ? reenable(b, instances, pm) : "";
            writeLifetimes();
            if (!disabled.isEmpty()) throw new GameTools.ToolError("Cannot shelve " + b + ": " + x.getMessage() + ". " + disabled);
            if (x instanceof GameTools.ToolError t) throw t;
            if (x instanceof InterruptedException i) throw i;
            throw new GameTools.ToolError("Cannot shelve " + b + ": " + x.getMessage());
        }
        writeLifetimes();
        log.info("[audit] SHELVE " + b);
        return "Shelved " + b + ": disabled and kept in the plugin library; activate_plugin brings it back.";
    }

    /** E10: after a failed shelve of an active plugin, turns its instances back on; if any stays off, the one-minute lifetime retry shelves it again. */
    private String reenable(String b, List<Plugin> instances, PluginManager pm) {
        boolean off;
        try {
            off = onGlobal(() -> {
                boolean stillOff = false;
                for (Plugin p : instances) {
                    if (!p.isEnabled()) pm.enablePlugin(p);
                    stillOff |= !p.isEnabled();
                }
                return stillOff;
            });
        } catch (GameTools.ToolError | InterruptedException | RuntimeException y) {
            if (y instanceof InterruptedException) Thread.currentThread().interrupt();
            off = true;
        }
        if (!off) return "";
        library.expireNow(b);
        writeLifetimes();
        log.warning("[audit] SHELVE " + b + " failed and the plugin is disabled; the lifetime retry will shelve it within a minute");
        return b + " is disabled and will be shelved by the retry within a minute";
    }

    /** Brings a shelved plugin back through the same checks as an install, as a hot load of the next revision. */
    public InstallOutcome activate(String name, String until) throws GameTools.ToolError, InterruptedException {
        requireActive();
        PluginLibrary.Entry e = library.get(name == null ? "" : name).orElseThrow(() -> new GameTools.ToolError("No plugin " + name + " in the plugin library."));
        if (!e.state().equals("shelved")) throw new GameTools.ToolError(name + " is already active.");
        Path stored = libDir.resolve(name + ".jar");
        byte[] jar;
        try {
            jar = Files.readAllBytes(stored);
        } catch (IOException x) {
            throw new GameTools.ToolError("Cannot read the shelved jar " + stored.getFileName() + ": " + x.getMessage());
        }
        String sha = sha256(jar);
        if (!sha.equals(e.sha256())) throw new GameTools.ToolError("the stored jar's sha-256 differs from the one recorded when it was shelved");
        try {
            if (until != null) PluginLibrary.checkUntil(until);
        } catch (IllegalArgumentException x) {
            throw new GameTools.ToolError(x.getMessage());
        }
        library.begin(name, "activating");
        try {
            writeMarker(name, sha);
            InstallOutcome o = checkAndLoad(name, jar);
            library.activateAndDrop(name, sha, until, stored);
            writeLifetimes();
            log.info("[audit] ACTIVATE " + name);
            return o;
        } catch (GameTools.ToolError | InterruptedException | IOException | RuntimeException x) {
            library.shelve(name, sha);
            writeLifetimes();
            if (x instanceof GameTools.ToolError t) throw t;
            if (x instanceof InterruptedException i) throw i;
            throw new GameTools.ToolError("Cannot activate " + name + ": " + x.getMessage());
        } finally {
            try {
                Files.deleteIfExists(libDir.resolve(name + ACTIVATING));
            } catch (IOException ignored) {
            }
        }
    }

    private void writeMarker(String name, String sha) throws IOException {
        Path marker = libDir.resolve(name + ACTIVATING), tmp = libDir.resolve(name + ACTIVATING + ".tmp");
        if (Files.isSymbolicLink(marker) || Files.isSymbolicLink(tmp)) throw new IOException(marker.getFileName() + " is a symlink");
        Files.writeString(tmp, sha + "\n");
        Files.move(tmp, marker, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** Called by a timer: shelves every active plugin whose lifetime ran out. A failure is retried a minute later; the lifetime stays (C3). */
    public void shelveExpired() {
        Instant now = Instant.now();
        for (PluginLibrary.Entry e : library.expired(now)) {
            Instant next = shelveRetry.get(e.name());
            if (next != null && now.isBefore(next)) continue;
            try {
                log.info("[audit] LIFETIME over for " + e.name() + "; shelving");
                shelve(e.name());
                shelveRetry.remove(e.name());
            } catch (GameTools.ToolError | RuntimeException x) {
                log.warning("[audit] Cannot shelve " + e.name() + " when its lifetime ended (retrying in a minute): " + x.getMessage());
                shelveRetry.put(e.name(), now.plusSeconds(60));
            } catch (InterruptedException x) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** At start: entrypoint.sh moved the jars named in shelved-at-boot.txt into the library; the reconcile records them as shelved. */
    public void recordShelvedAtBoot() {
        Path list = libDir.resolve("shelved-at-boot.txt");
        try {
            if (!Files.isRegularFile(list, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
            for (String line : Files.readAllLines(list)) {
                if (NAME.matcher(line.strip()).matches()) log.info("[audit] SHELVE " + line.strip() + " at boot (lifetime ended while the server was down)");
            }
            Files.delete(list);
        } catch (IOException e) {
            log.warning("Cannot read shelved-at-boot.txt: " + e.getMessage());
        }
    }

    /** C2 at plugin start: the folders are the truth; library jars get their base name back, then the catalogue is corrected. */
    public void reconcileLibrary() {
        java.util.Map<String, String> lib = new java.util.TreeMap<>();
        for (Path jar : jars(libDir)) {
            String n = nameOf(jar), b = jar.getFileName().toString().replaceFirst("\\.jar$", "");
            if (n == null || !claudeWritten(b) || !NAME.matcher(b).matches() || Files.isSymbolicLink(jar)) continue;
            try {
                lib.put(b, n.equals(b) ? sha256(Files.readAllBytes(jar)) : intoLibrary(jar, b));
            } catch (IOException e) {
                log.warning("Cannot read " + jar.getFileName() + " in the plugin library: " + e.getMessage());
            }
        }
        LibrarySettle.settle(pluginsDir, libDir, lib, this::markerSha, jar -> {
            String n = nameOf(jar), b = n == null ? null : base(n);
            return b != null && claudeWritten(b) ? b : null;
        }, this::moveToQuarantine, log::warning);
        try (Stream<Path> s = Files.list(libDir)) {
            for (Path m : s.filter(x -> x.getFileName().toString().endsWith(ACTIVATING) && Files.isRegularFile(x, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList()) Files.deleteIfExists(m);
        } catch (IOException ignored) {
        }
        java.util.Map<String, String> inPlugins = new java.util.TreeMap<>();
        for (Path jar : jars(pluginsDir)) {
            String n = nameOf(jar);
            if (n == null || !claudeWritten(n)) continue;
            try {
                String sha = sha256(Files.readAllBytes(jar)), b = base(n);
                if (!inPlugins.containsKey(b) || sha.equals(lib.get(b))) inPlugins.put(b, sha);
            } catch (IOException e) {
                log.warning("Cannot read " + jar.getFileName() + ": " + e.getMessage());
            }
        }
        var folders = new PluginLibrary.Folders(bases(pluginsDir), lib, bases(pluginsDir.resolve("claude-held")), bases(quarantine), inPlugins);
        for (String fix : library.reconcile(folders)) log.info("[audit] RECONCILE " + fix);
        writeLifetimes();
    }

    private String markerSha(String base) {
        Path m = libDir.resolve(base + ACTIVATING);
        try {
            return Files.isRegularFile(m, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? Files.readString(m).strip() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private Set<String> bases(Path dir) {
        Set<String> out = new java.util.TreeSet<>();
        for (Path jar : jars(dir)) {
            String n = nameOf(jar);
            if (n != null && claudeWritten(n)) out.add(base(n));
        }
        return out;
    }

    /** /claude plugins delete: a shelved plugin's jar goes to quarantine, its source stays, the catalogue entry goes. */
    public String deleteShelved(String name) throws GameTools.ToolError {
        PluginLibrary.Entry e = library.get(name).orElseThrow(() -> new GameTools.ToolError("No plugin " + name + " in the plugin library."));
        if (!e.state().equals("shelved")) throw new GameTools.ToolError(name + " is active; shelve it first.");
        try {
            Path jar = libDir.resolve(name + ".jar");
            if (Files.exists(jar)) moveToQuarantine(jar);
        } catch (IOException x) {
            throw new GameTools.ToolError("Cannot move the jar: " + x.getMessage());
        }
        library.delete(name);
        log.info("[audit] LIBRARY DELETE " + name);
        return "Deleted " + name + " from the library; its jar is in plugins/claude-quarantine/ and its source is kept in workshop/" + name + "/.";
    }

    public String list() {
        List<String> lines = new ArrayList<>();
        Set<String> shown = new LinkedHashSet<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            String b = base(p.getName());
            if (!claudeWritten(b) || !shown.add(b)) continue;
            Plugin best = loaded(b);
            String watch = watchInfo.apply(b);
            lines.add(b + " " + best.getPluginMeta().getVersion() + ": " + (best.isEnabled() ? "enabled" : "disabled") + (watch.isEmpty() ? "" : "; " + watch) + purposeOf(b));
        }
        synchronized (awaitingRestart) {
            for (String n : awaitingRestart) if (shown.add(n)) lines.add(n + ": pending restart");
        }
        for (Path jar : jars(Bukkit.getUpdateFolderFile().toPath())) {
            String n = nameOf(jar);
            if (n != null && claudeWritten(n) && shown.add(base(n))) lines.add(base(n) + ": pending restart");
        }
        for (String b : bases(pluginsDir.resolve("claude-held"))) if (shown.add(b)) lines.add(b + ": held, not loaded" + purposeOf(b));
        for (Path jar : jars(quarantine)) {
            String n = nameOf(jar);
            if (n != null && claudeWritten(n) && shown.add(base(n))) lines.add(base(n) + ": quarantined; " + watchInfo.apply(base(n)));
        }
        for (PluginLibrary.Entry e : library.list()) {
            if (e.state().equals("shelved")) lines.add(e.name() + ": shelved; used " + e.timesUsed() + " time(s)" + purposeOf(e.name()));
        }
        return lines.isEmpty() ? "No Claude-written plugins." : String.join("\n", lines);
    }

    private String purposeOf(String name) {
        return library.get(name).map(e -> " — " + e.purpose() + " (usage: " + e.usage() + ")").orElse("");
    }

    /** Disables every instance of the plugin and moves its jars to quarantine. Also the owner's kill switch; call it off tick threads or on the global one. */
    public String disable(String name) throws GameTools.ToolError, InterruptedException {
        if (name == null || !NAME.matcher(name).matches() || !claudeWritten(name)) {
            throw new GameTools.ToolError("Only plugins whose name starts with Claude (not ClaudeControl) can be disabled here.");
        }
        String b = base(name);
        PluginManager pm = Bukkit.getPluginManager();
        List<Plugin> instances = new ArrayList<>();
        for (Plugin p : pm.getPlugins()) if (base(p.getName()).equals(b)) instances.add(p);
        onGlobal(() -> {
            for (Plugin p : instances) if (p.isEnabled()) pm.disablePlugin(p);
            return null;
        });
        List<Path> moved = new ArrayList<>();
        try {
            for (Path dir : List.of(pluginsDir, Bukkit.getUpdateFolderFile().toPath())) {
                for (Path jar : jars(dir)) {
                    String n = nameOf(jar);
                    if (n != null && base(n).equals(b)) moved.add(moveToQuarantine(jar));
                }
            }
        } catch (IOException e) {
            throw new GameTools.ToolError("Cannot move the jar: " + e.getMessage());
        }
        synchronized (awaitingRestart) { awaitingRestart.remove(b); }
        library.get(b).filter(e -> e.state().equals("active")).ifPresent(e -> { library.shelve(b, "quarantined"); writeLifetimes(); });
        if (instances.isEmpty() && moved.isEmpty()) throw new GameTools.ToolError("No plugin named " + b + ".");
        log.info("[audit] DISABLE " + b + ", quarantined " + moved);
        return (instances.isEmpty() ? b + " was not loaded" : "Disabled " + b) + "; " + moved.size() + " jar(s) moved to plugins/claude-quarantine/.";
    }

    public String disableTool(String name) throws GameTools.ToolError, InterruptedException {
        requireActive();
        return disable(name);
    }

    /** At start: only the newest revision of each Claude plugin stays; older revision jars (and their instances) go to quarantine. */
    public void cleanStaleRevisions() {
        Map<String, List<Path>> byBase = new LinkedHashMap<>();
        for (Path jar : jars(pluginsDir)) {
            String n = nameOf(jar);
            if (n != null && claudeWritten(n)) byBase.computeIfAbsent(base(n), k -> new ArrayList<>()).add(jar);
        }
        for (List<Path> group : byBase.values()) {
            if (group.size() < 2) continue;
            group.sort((a, c) -> Integer.compare(revision(nameOf(c)), revision(nameOf(a))));
            for (Path stale : group.subList(1, group.size())) {
                try {
                    Plugin p = Bukkit.getPluginManager().getPlugin(nameOf(stale));
                    if (p != null && p.isEnabled()) Bukkit.getPluginManager().disablePlugin(p);
                    log.info("Moved stale revision " + stale.getFileName() + " to " + moveToQuarantine(stale) + ".");
                } catch (IOException | RuntimeException e) {
                    log.warning("Cannot move stale revision " + stale + ": " + e.getMessage());
                }
            }
        }
    }

    Integer restartsLeft() { return restarts == null ? null : restarts.leftToday(); }

    public String restart(String reason) throws GameTools.ToolError {
        requireActive();
        if (reason == null || reason.isBlank()) throw new GameTools.ToolError("restart_server needs a reason that players will see.");
        RestartLimiter l = restarts;
        synchronized (this) {
            if (!l.tryAcquire()) {
                long s = l.secondsUntilAllowed();
                throw new GameTools.ToolError("Refused: the server allows " + l.describeLimits() + "; the next restart is allowed in "
                        + (s >= 3600 ? s / 3600 + " h " + s % 3600 / 60 + " min" : s / 60 + " min " + s % 60 + " s") + ".");
            }
            try {
                saveRestartHistory(l.history());
            } catch (RuntimeException e) {
                log.warning("Cannot save the restart history: " + e.getMessage());
            }
        }
        List<String> waiting;
        synchronized (awaitingRestart) { waiting = List.copyOf(awaitingRestart); }
        log.info(CommandRunner.clean("[audit] RESTART requested: " + reason + "; plugins awaiting it: " + waiting));
        var global = Bukkit.getGlobalRegionScheduler();
        for (int left : new int[] {30, 10, 5}) {
            Component text = Component.text("[Server] Restarting in " + left + " seconds: " + reason);
            global.runDelayed(plugin, t -> Bukkit.broadcast(text), Math.max(1, (30 - left) * 20L));
        }
        global.runDelayed(plugin, t -> {
            log.info("Restarting now (save and stop) for: " + reason);
            Bukkit.shutdown();
        }, 30 * 20L);
        return "Restart announced with a 30-second countdown; the server then saves, stops, and the container starts it again"
                + (waiting.isEmpty() ? "." : ", loading " + String.join(", ", waiting) + ".");
    }

    private static List<Path> jars(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private Path jarOf(String pluginName) {
        for (Path jar : jars(pluginsDir)) if (pluginName.equals(nameOf(jar))) return jar;
        return null;
    }

    /** The plugin.yml name of a jar (parsed like validate does), or null. */
    static String nameOf(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry yml = z.getEntry("plugin.yml");
            if (yml == null) return null;
            try (InputStream in = z.getInputStream(yml)) {
                return PluginManifest.nameOf(new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8));
            }
        } catch (IOException | PluginManifest.Invalid ignored) {
            return null;
        }
    }

    private Path moveToQuarantine(Path jar) throws IOException {
        Files.createDirectories(quarantine);
        Path to = quarantine.resolve(jar.getFileName());
        if (Files.exists(to)) to = quarantine.resolve(System.currentTimeMillis() + "-" + jar.getFileName());
        return Files.move(jar, to);
    }

    /**
     * Runs work on the global region thread and waits for it; directly when already on it. Never call it from another tick
     * thread (a player's region): schedule onto the global region instead. A ToolError's cause is the work's exception.
     */
    private <T> T onGlobal(Callable<T> work) throws GameTools.ToolError, InterruptedException {
        if (Bukkit.isGlobalTickThread()) {
            try {
                return work.call();
            } catch (Throwable t) {
                throw error(t);
            }
        }
        if (Bukkit.isPrimaryThread()) {
            throw new GameTools.ToolError("internal: workshop call from a region thread");
        }
        CompletableFuture<T> f = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                f.complete(work.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw error(e.getCause());
        } catch (TimeoutException e) {
            throw new GameTools.ToolError("timed out on the global region thread");
        }
    }

    private static GameTools.ToolError error(Throwable t) {
        GameTools.ToolError e = new GameTools.ToolError(t instanceof PluginBroken ? t.getMessage() : t.toString());
        e.initCause(t);
        return e;
    }
}
