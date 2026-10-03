package com.lucascosolo.claudecontrol;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static check of a plugin's class files before install: reads each constant pool and flags references to process, network,
 * reflection and server-internal classes, and string constants naming other plugins' data or server files. One finding per
 * offending class. Catches mistakes and careless code, not a determined adversary.
 */
public final class JarScan {
    private static final List<String> CLASS_EXACT = List.of("java/lang/Runtime", "sun/misc/Unsafe");
    private static final List<String> CLASS_PREFIX = List.of("java/lang/ProcessBuilder", "java/net/", "java/lang/reflect/",
            "net/minecraft/", "org/bukkit/craftbukkit/");
    private static final List<String> STRINGS = List.of("NomadCairn", "NomadLabs", "ClaudeControl", "ops.json", "server.properties", "..");
    private static final Pattern DESCRIPTOR_CLASS = Pattern.compile("L([A-Za-z0-9_$/]+)[;<]");

    private JarScan() {}

    public static List<String> scan(Map<String, byte[]> classFiles) {
        List<String> findings = new ArrayList<>();
        for (var e : classFiles.entrySet()) {
            Set<String> hits;
            try {
                hits = scanClass(e.getValue());
            } catch (IOException | RuntimeException ex) {
                findings.add(e.getKey() + ": cannot read the class file (" + ex.getClass().getSimpleName() + ")");
                continue;
            }
            if (!hits.isEmpty()) findings.add(e.getKey() + ": " + String.join("; ", hits));
        }
        return findings;
    }

    private static Set<String> scanClass(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("bad magic");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        if (count > in.available()) throw new IOException("constant pool count larger than the class file");
        String[] utf8 = new String[count];
        List<Integer> classIdx = new ArrayList<>(), stringIdx = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 7 -> classIdx.add(in.readUnsignedShort());
                case 8 -> stringIdx.add(in.readUnsignedShort());
                case 16, 19, 20 -> in.readUnsignedShort();
                case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
                case 5, 6 -> { in.readLong(); i++; }
                default -> throw new IOException("unknown constant tag " + tag);
            }
        }
        skipRest(in);
        Set<String> classes = new LinkedHashSet<>();
        for (int idx : classIdx) {
            String n = utf8[idx];
            if (n == null) throw new IOException("bad class index");
            if (n.startsWith("[")) addDescriptorClasses(n, classes); else classes.add(n);
        }
        for (String u : utf8) if (u != null) addDescriptorClasses(u, classes);
        Set<String> hits = new LinkedHashSet<>();
        for (String c : classes) {
            if (CLASS_EXACT.contains(c) || CLASS_PREFIX.stream().anyMatch(c::startsWith)) hits.add("references " + c);
        }
        for (int idx : stringIdx) {
            String s = utf8[idx];
            if (s == null) throw new IOException("bad string index");
            for (String token : STRINGS) if (s.contains(token)) hits.add("string \"" + s + "\" contains " + token);
        }
        return hits;
    }

    private static void addDescriptorClasses(String s, Set<String> out) {
        Matcher m = DESCRIPTOR_CLASS.matcher(s);
        while (m.find()) out.add(m.group(1));
    }

    /** Walks the rest of the class file so a truncated one is reported as unreadable. */
    private static void skipRest(DataInputStream in) throws IOException {
        in.readUnsignedShort();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int interfaces = in.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) in.readUnsignedShort();
        for (int members = 0; members < 2; members++) {
            int n = in.readUnsignedShort();
            for (int i = 0; i < n; i++) {
                in.readUnsignedShort();
                in.readUnsignedShort();
                in.readUnsignedShort();
                skipAttributes(in);
            }
        }
        skipAttributes(in);
        if (in.available() > 0) throw new IOException("extra bytes after the class file");
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            in.readUnsignedShort();
            long len = in.readInt() & 0xffffffffL;
            if (len > in.available()) throw new IOException("attribute longer than the class file");
            in.skipNBytes(len);
        }
    }
}
