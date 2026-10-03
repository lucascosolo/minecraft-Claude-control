package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarScanTest {
    @TempDir Path tmp;

    /** Compiles package-less classes (name -> source body) and returns "Name.class" -> bytes. */
    private Map<String, byte[]> compile(Map<String, String> bodies) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "tests must run on a JDK");
        Path src = Files.createTempDirectory(tmp, "src"), out = Files.createTempDirectory(tmp, "out");
        List<String> args = new ArrayList<>(List.of("-proc:none", "-nowarn", "-d", out.toString()));
        for (var e : bodies.entrySet()) {
            Path f = src.resolve(e.getKey() + ".java");
            Files.writeString(f, "public class " + e.getKey() + " {\n" + e.getValue() + "\n}\n");
            args.add(f.toString());
        }
        assertEquals(0, javac.run(null, null, null, args.toArray(new String[0])), "test source failed to compile");
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(out)) {
            for (Path p : s.filter(Files::isRegularFile).toList())
                classes.put(out.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
        }
        return classes;
    }

    private List<String> scanOne(String name, String body) throws IOException {
        Map<String, byte[]> c = compile(Map.of(name, body));
        assertEquals(Set.of(name + ".class"), c.keySet());
        return JarScan.scan(c);
    }

    private void assertSingleFinding(String name, String body) throws IOException {
        List<String> f = scanOne(name, body);
        assertEquals(1, f.size(), f.toString());
        assertTrue(f.get(0).contains(name + ".class"), f.get(0));
    }

    private void assertSingleStringFinding(String name, String literal) throws IOException {
        List<String> f = scanOne(name, "public String v() { return \"" + literal + "\"; }");
        assertEquals(1, f.size(), f.toString());
        assertTrue(f.get(0).contains(name + ".class"), f.get(0));
        assertTrue(f.get(0).contains(literal), f.get(0));
    }

    @Test void harmlessClassIsClean() throws IOException {
        assertEquals(List.of(), scanOne("Harmless",
                "public java.util.List<String> v(java.io.File dataFolder) {\n"
                        + "  java.util.List<String> l = new java.util.ArrayList<>();\n"
                        + "  l.add(new java.io.File(dataFolder, \"claudewelcome\").getName());\n"
                        + "  return l;\n}"));
    }

    @Test void runtimeExecFlagged() throws IOException {
        assertSingleFinding("UsesRuntime", "public void v() throws Exception { Runtime.getRuntime().exec(\"ls\"); }");
    }

    @Test void processBuilderFlagged() throws IOException {
        assertSingleFinding("UsesPb", "public Object v() { return new ProcessBuilder(\"ls\"); }");
    }

    @Test @SuppressWarnings("deprecation") void javaNetUrlFlagged() throws IOException {
        assertSingleFinding("UsesUrl", "public Object v() throws Exception { return new java.net.URL(\"http://example.com\"); }");
    }

    @Test void httpClientFlagged() throws IOException {
        assertSingleFinding("UsesHttp", "public Object v() { return java.net.http.HttpClient.newHttpClient(); }");
    }

    @Test void reflectionFlagged() throws IOException {
        assertSingleFinding("UsesReflect",
                "public Object v() throws Exception { java.lang.reflect.Method m = Object.class.getMethod(\"toString\"); return m.invoke(\"x\"); }");
    }

    @Test void sensitiveStringConstantsFlagged() throws IOException {
        assertSingleStringFinding("StrCairn", "NomadCairn");
        assertSingleStringFinding("StrLabs", "NomadLabs");
        assertSingleStringFinding("StrControl", "ClaudeControl");
        assertSingleStringFinding("StrOps", "ops.json");
        assertSingleStringFinding("StrProps", "server.properties");
        assertSingleStringFinding("StrDots", "..");
    }

    @Test void stringContainingTokenAnywhereIsFlagged() throws IOException {
        assertSingleStringFinding("StrEmbedded", "../plugins/NomadLabs/data.yml");
    }

    @Test void multipleClassesReportOnlyOffenders() throws IOException {
        Map<String, byte[]> c = compile(new LinkedHashMap<>(Map.of(
                "Good", "public int v() { return 1; }",
                "Bad", "public void v() throws Exception { Runtime.getRuntime().exec(\"ls\"); }",
                "Bad2", "public String v() { return \"server.properties\"; }")));
        List<String> f = JarScan.scan(c);
        assertTrue(f.stream().anyMatch(x -> x.contains("Bad.class")), f.toString());
        assertTrue(f.stream().anyMatch(x -> x.contains("Bad2.class")), f.toString());
        assertTrue(f.stream().noneMatch(x -> x.contains("Good")), f.toString());
        assertEquals(2, f.size(), f.toString());
    }

    @Test void emptyInputIsClean() { assertEquals(List.of(), JarScan.scan(Map.of())); }

    @Test void garbageBytesGiveUnreadableFindingNotException() {
        for (byte[] junk : new byte[][] {{1, 2, 3}, {}, "not a class file at all".getBytes()}) {
            List<String> f = assertDoesNotThrow(() -> JarScan.scan(Map.of("Junk.class", junk)));
            assertEquals(1, f.size(), f.toString());
            assertTrue(f.get(0).contains("Junk.class"), f.get(0));
            assertTrue(f.get(0).toLowerCase().contains("read"), f.get(0));
        }
    }

    @Test void truncatedClassGivesFindingNotException() throws IOException {
        byte[] good = compile(Map.of("Trunc", "public int v() { return 1; }")).get("Trunc.class");
        byte[] cut = Arrays.copyOf(good, good.length / 3);
        List<String> f = assertDoesNotThrow(() -> JarScan.scan(Map.of("Trunc.class", cut)));
        assertEquals(1, f.size(), f.toString());
        assertTrue(f.get(0).contains("Trunc.class"));
    }
}
