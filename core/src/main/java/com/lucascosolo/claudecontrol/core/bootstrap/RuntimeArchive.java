package com.lucascosolo.claudecontrol.core.bootstrap;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Append-only private runtime snapshots. Validate every entry before running any replacement. */
public final class RuntimeArchive {
    private static final String PREFIX="com/lucascosolo/claudecontrol/core/";
    private static final long MAX_BYTES=64L*1024*1024;
    private RuntimeArchive(){}
    public static boolean stableEntry(String name){return name.equals("plugin.yml") || name.startsWith(PREFIX+"bootstrap/") || name.equals(PREFIX+"ClaudeControlPlugin.class") || name.startsWith(PREFIX+"ClaudeControlPlugin$");}
    private static void noSymlinks(Path path) throws IOException {
        Path absolute=path.toAbsolutePath().normalize();Path part=absolute.getRoot();
        for(Path component:absolute){part=part.resolve(component);if(Files.isSymbolicLink(part))throw new IOException("Runtime paths cannot traverse symlinks");}
    }
    public static Map<String,byte[]> validate(Path jar) throws IOException {
        noSymlinks(jar);
        if(!Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS) || Files.size(jar)>MAX_BYTES)throw new IOException("Runtime jar must be a bounded regular file");
        Map<String,byte[]> stable=new TreeMap<>();Set<String> names=new HashSet<>();long total=0;boolean runtime=false;
        try(JarFile entries=new JarFile(jar.toFile(),true)){
            var enumeration=entries.entries();
            while(enumeration.hasMoreElements()){
                var entry=enumeration.nextElement();String name=entry.getName();
                if(!names.add(name) || name.startsWith("/") || name.contains("..") || name.contains("\\"))throw new IOException("Invalid runtime jar entry");
                if(entry.isDirectory())continue;
                try(var stream=entries.getInputStream(entry)){
                    byte[] bytes=stream.readNBytes((int)Math.min(MAX_BYTES-total+1,Integer.MAX_VALUE));total+=bytes.length;
                    if(total>MAX_BYTES)throw new IOException("Runtime jar expanded size exceeded");
                    if(stableEntry(name)){
                        if(name.equals("plugin.yml"))bytes=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replaceAll("(?m)^version:[^\\r\\n]*(?:\\r?\\n|$)","").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        stable.put(name,bytes);
                    }
                }
                runtime|=name.equals(PREFIX+"ClaudeRuntime.class");
            }
        }
        if(!runtime || !stable.containsKey(PREFIX+"ClaudeControlPlugin.class") || !stable.containsKey(PREFIX+"bootstrap/RuntimeLifecycle.class"))throw new IOException("Not a supported ClaudeControl runtime jar");
        return stable;
    }
    public static void compatible(Path baseline,Path replacement) throws IOException {
        Map<String,byte[]> old=validate(baseline),next=validate(replacement);
        if(!old.keySet().equals(next.keySet()) || old.entrySet().stream().anyMatch(e->!Arrays.equals(e.getValue(),next.get(e.getKey()))))
            throw new IOException("Bootstrap changed; one server restart is required");
    }
    public static Path snapshot(Path source,Path archive) throws IOException {
        validate(source);noSymlinks(archive);Files.createDirectories(archive);
        try{Files.setPosixFilePermissions(archive,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));}
        catch(UnsupportedOperationException ignored){}
        Path copy=archive.resolve("runtime-"+UUID.randomUUID()+".jar");
        Files.copy(source,copy);validate(copy);
        try{Files.setPosixFilePermissions(copy,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}
        catch(UnsupportedOperationException ignored){}
        return copy;
    }
}
