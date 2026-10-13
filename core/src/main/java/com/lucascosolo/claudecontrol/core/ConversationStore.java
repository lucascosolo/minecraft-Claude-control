package com.lucascosolo.claudecontrol.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** Conversation storage has its own budget, separate from small human-readable memory notes. */
final class ConversationStore {
    static final int MAX_BYTES=2*1024*1024;
    private final Path root;
    ConversationStore(Path directory) throws IOException {
        root=directory.toAbsolutePath().normalize(); check(); Files.createDirectories(root); check();
    }
    private void check() throws IOException {
        Path part=root.getRoot(); for(Path component:root) { part=part.resolve(component); if(Files.isSymbolicLink(part)) throw new IOException("Conversation storage cannot use symbolic links"); }
        if(Files.exists(root,LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid conversation directory");
    }
    private Path path(String name) throws IOException {
        check(); if(name==null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}") || name.contains("..")) throw new IOException("Invalid conversation filename");
        Path result=root.resolve(name); if(Files.isSymbolicLink(result) || (Files.exists(result,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(result,LinkOption.NOFOLLOW_LINKS))) throw new IOException("Invalid conversation file"); return result;
    }
    String read(String name) throws IOException {
        Path p=path(name); if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS)) return "";
        try(var channel=Files.newByteChannel(p,java.util.Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) {
            var buffer=java.nio.ByteBuffer.allocate(MAX_BYTES+1); while(buffer.hasRemaining() && channel.read(buffer)>0) {}
            if(buffer.position()>MAX_BYTES) throw new IOException("Conversation exceeds storage budget"); buffer.flip(); return StandardCharsets.UTF_8.decode(buffer).toString();
        }
    }
    void write(String name,String text) throws IOException {
        Path destination=path(name); byte[] bytes=text.getBytes(StandardCharsets.UTF_8); if(bytes.length>MAX_BYTES) throw new IOException("Conversation exceeds storage budget");
        Path staged=root.resolve(".conversation-staged-"+UUID.randomUUID()); Files.write(staged,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        path(name); Path previous=null; if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) { previous=root.resolve(".conversation-archived-"+UUID.randomUUID()); Files.move(destination,previous); }
        try { Files.move(staged,destination); } catch(IOException failure) { if(previous!=null && !Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) Files.move(previous,destination); throw failure; }
    }
    void archive(String name) throws IOException { Path p=path(name); if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)) Files.move(p,root.resolve(".conversation-archived-"+UUID.randomUUID())); }
    void retainProgress(String text) throws IOException {
        check(); byte[] bytes=text.getBytes(StandardCharsets.UTF_8); if(bytes.length>MAX_BYTES) throw new IOException("Transcript exceeds recovery budget");
        Files.write(root.resolve(".conversation-action-record-"+UUID.randomUUID()+".json"),bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
    }
}
