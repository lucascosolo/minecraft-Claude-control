package com.lucascosolo.claudecontrol.core;

import com.lucascosolo.claudecontrol.core.bootstrap.RuntimeClassLoader;
import org.junit.jupiter.api.Test;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeClassLoaderTest {
    private static final String CORE = "com.lucascosolo.claudecontrol.core.";
    private Path scratch() throws IOException {
        Path root = Path.of("build", "test-scratch").toAbsolutePath();
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "runtime-loader-");
    }
    private Path compiledJar(Path root, String name, Map<String, String> classes, Map<String, String> resources) throws Exception {
        Path directory = Files.createDirectory(root.resolve(name));
        Path output = Files.createDirectory(directory.resolve("classes"));
        var arguments = new java.util.ArrayList<String>(List.of("-d", output.toString()));
        for (var entry : classes.entrySet()) {
            String className = entry.getKey();
            int separator = className.lastIndexOf('.');
            Path source = directory.resolve(className.replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package " + className.substring(0, separator) + "; public class "
                + className.substring(separator + 1) + " { public static String version() { return \""
                + entry.getValue() + "\"; } }");
            arguments.add(source.toString());
        }
        if (!classes.isEmpty()) {
            var compiler = ToolProvider.getSystemJavaCompiler();
            assertNotNull(compiler, "Fixture compilation requires the build JDK");
            assertEquals(0, compiler.run(null, null, null, arguments.toArray(String[]::new)));
        }
        Path jar = root.resolve(name + ".jar");
        try (var stream = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var paths = Files.walk(output)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    stream.putNextEntry(new JarEntry(output.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, stream);
                    stream.closeEntry();
                }
            }
            for (var resource : resources.entrySet()) {
                stream.putNextEntry(new JarEntry(resource.getKey()));
                stream.write(resource.getValue().getBytes(StandardCharsets.UTF_8));
                stream.closeEntry();
            }
        }
        return jar;
    }
    private String version(ClassLoader loader, String name) throws Exception {
        return (String) loader.loadClass(name).getMethod("version").invoke(null);
    }
    @Test void identifiesOwnedRuntimeAndKeepsBootstrapAndEntrypointShared() {
        for (String name : List.of("ClaudeRuntime", "Settings", "experience.Worker", "lib.gson.Gson", "lib.rhino.Context")) {
            assertTrue(RuntimeClassLoader.runtimeClass(CORE + name), name);
        }
        for (String name : List.of(CORE + "bootstrap.RuntimeLifecycle", CORE + "ClaudeControlPlugin",
            CORE + "ClaudeControlPlugin$Helper", "org.bukkit.Server", "java.lang.String", "other.Plugin")) {
            assertFalse(RuntimeClassLoader.runtimeClass(name), name);
        }
    }
    @Test void loadsOwnedImplementationAndShadedLibrariesFromReplacementOnly() throws Exception {
        Path root = scratch();
        Map<String, String> old = new LinkedHashMap<>();
        for (String name : List.of("ClaudeRuntime", "lib.gson.Gson", "lib.rhino.Context")) old.put(CORE + name, "old");
        old.put(CORE + "RemovedRuntime", "stale");
        Path parentJar = compiledJar(root, "parent", old, Map.of());
        Map<String, String> next = new LinkedHashMap<>();
        for (String name : List.of("ClaudeRuntime", "lib.gson.Gson", "lib.rhino.Context")) next.put(CORE + name, "new");
        Path childJar = compiledJar(root, "child", next, Map.of());
        try (var parent = new URLClassLoader(new java.net.URL[]{parentJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
             var child = new RuntimeClassLoader(childJar.toUri().toURL(), parent)) {
            for (String name : next.keySet()) {
                assertEquals("new", version(child, name));
                assertEquals(child, child.loadClass(name).getClassLoader());
                assertNotSame(parent.loadClass(name), child.loadClass(name));
            }
            assertEquals("stale", version(parent, CORE + "RemovedRuntime"));
            assertThrows(ClassNotFoundException.class, () -> child.loadClass(CORE + "RemovedRuntime"));
            assertSame(String.class, child.loadClass("java.lang.String"));
        }
    }
    @Test void stableBootstrapEntrypointAndExternalTypesResolveFromParent() throws Exception {
        Path root = scratch();
        Map<String, String> old = new LinkedHashMap<>();
        for (String name : List.of(CORE + "bootstrap.RuntimeLifecycle", CORE + "ClaudeControlPlugin",
            CORE + "ClaudeControlPlugin$Helper", "fixture.platform.Platform")) old.put(name, "parent");
        Map<String, String> next = new LinkedHashMap<>();
        old.keySet().forEach(name -> next.put(name, "child"));
        Path parentJar = compiledJar(root, "parent", old, Map.of());
        Path childJar = compiledJar(root, "child", next, Map.of());
        try (var parent = new URLClassLoader(new java.net.URL[]{parentJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
             var child = new RuntimeClassLoader(childJar.toUri().toURL(), parent)) {
            for (String name : old.keySet()) {
                assertEquals("parent", version(child, name));
                assertSame(parent.loadClass(name), child.loadClass(name));
            }
        }
    }
    @Test void successiveLoadersDoNotReusePriorImplementationClasses() throws Exception {
        Path root = scratch();
        String name = CORE + "ClaudeRuntime";
        Path first = compiledJar(root, "first", Map.of(name, "first"), Map.of());
        Path second = compiledJar(root, "second", Map.of(name, "second"), Map.of());
        try (var old = new RuntimeClassLoader(first.toUri().toURL(), ClassLoader.getPlatformClassLoader());
             var next = new RuntimeClassLoader(second.toUri().toURL(), old)) {
            assertEquals("first", version(old, name));
            assertEquals("second", version(next, name));
            assertNotSame(old.loadClass(name), next.loadClass(name));
        }
    }
    @Test void resolvesReplacementResourcesBeforeParentAndPreservesFallback() throws Exception {
        Path root = scratch();
        Path parentJar = compiledJar(root, "parent", Map.of(), Map.of("config.yml", "old", "parent-only.txt", "shared"));
        Path childJar = compiledJar(root, "child", Map.of(), Map.of("config.yml", "new"));
        try (var parent = new URLClassLoader(new java.net.URL[]{parentJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
             var child = new RuntimeClassLoader(childJar.toUri().toURL(), parent)) {
            try (var stream = child.getResourceAsStream("config.yml")) {
                assertNotNull(stream);
                assertEquals("new", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            try (var stream = child.getResourceAsStream("parent-only.txt")) {
                assertNotNull(stream);
                assertEquals("shared", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertNull(child.getResource("missing.txt"));
        }
    }
}
