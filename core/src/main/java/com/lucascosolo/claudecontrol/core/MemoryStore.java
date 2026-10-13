package com.lucascosolo.claudecontrol.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Flat bounded store. Every replacement preserves the previous file in this store. */
public final class MemoryStore {
    private static final int FILE_BYTES=65536, ACTIVE_BYTES=524288, FILE_COUNT=128;
    private final Path root;
    public MemoryStore(Path directory) throws IOException {
        root=directory.toAbsolutePath().normalize(); checkComponents(root); Files.createDirectories(root); checkRoot();
    }
    private static void checkComponents(Path path) throws IOException {
        Path p=path.getRoot(); for(Path part:path) { p=p.resolve(part); if(Files.isSymbolicLink(p)) throw new IOException("Symbolic links are not permitted in storage paths"); }
    }
    private void checkRoot() throws IOException { checkComponents(root); if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Storage directory unavailable"); }
    private Path path(String name) throws IOException {
        checkRoot(); if(name==null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}") || name.contains("..")) throw new IOException("Invalid storage name");
        Path p=root.resolve(name); if(Files.isSymbolicLink(p) || (Files.exists(p,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))) throw new IOException("Invalid storage file"); return p;
    }
    public synchronized List<String> list() throws IOException {
        checkRoot(); try(var entries=Files.list(root)) { return entries.filter(p-> !p.getFileName().toString().startsWith(".")).filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).map(p->p.getFileName().toString()).sorted().toList(); }
    }
    public synchronized String read(String name) throws IOException {
        Path p=path(name); if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS)) return "";
        if(Files.size(p)>FILE_BYTES) throw new IOException("Storage file exceeds byte limit");
        try(var channel=Files.newByteChannel(p,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) {
            var buffer=java.nio.ByteBuffer.allocate(FILE_BYTES+1); while(buffer.hasRemaining() && channel.read(buffer)>0) {}
            if(buffer.position()>FILE_BYTES) throw new IOException("Storage file exceeds byte limit"); buffer.flip(); return StandardCharsets.UTF_8.decode(buffer).toString();
        }
    }
    public synchronized void write(String name,String content) throws IOException {
        Path destination=path(name); byte[] bytes=Objects.requireNonNull(content).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>FILE_BYTES) throw new IOException("Storage file exceeds byte limit");
        List<String> active=list(); boolean replacing=Files.exists(destination,LinkOption.NOFOLLOW_LINKS);
        if(!replacing && active.size()>=FILE_COUNT) throw new IOException("Storage file count exceeded");
        long total=bytes.length; for(String item:active) if(!item.equals(name)) total+=Files.size(path(item));
        if(total>ACTIVE_BYTES) throw new IOException("Storage byte budget exceeded");
        Path staged=root.resolve(".staged-"+UUID.randomUUID());
        Files.write(staged,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        path(name); Path old=null;
        if(replacing) { old=archivePath(); Files.move(destination,old); }
        try { Files.move(staged,destination); }
        catch(IOException failure) { if(old!=null && !Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) Files.move(old,destination); throw failure; }
    }
    private Path archivePath() { return root.resolve(".archived-"+UUID.randomUUID()); }
    public synchronized void archive(String name) throws IOException { Path p=path(name); if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)) Files.move(p,archivePath()); }
}
