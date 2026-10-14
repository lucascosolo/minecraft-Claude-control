package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import java.util.*;
import java.util.function.Predicate;

/** Bounded public observations; never queues work or grants authority. Server-thread owned. */
final class NearbyConversation {
    private record Entry(UUID speaker,String name,String world,String message,long time) {}
    private final Deque<Entry> entries=new ArrayDeque<>();
    void hear(UUID speaker,String name,String world,String message,long now) {
        if(speaker==null || name==null || world==null || message==null || now<0)return;
        expire(now);
        String text=message.replaceAll("[\\p{Cntrl}]"," ").strip();
        if(text.isEmpty())return;
        entries.addLast(new Entry(speaker,name.substring(0,Math.min(32,name.length())),world,
            text.substring(0,Math.min(240,text.length())),now));
        while(entries.size()>24)entries.removeFirst();
    }
    JsonArray snapshot(String world,long now,Predicate<UUID> currentlyPermitted) {
        expire(now);JsonArray out=new JsonArray();
        for(Entry entry:entries)if(entry.world().equals(world) && currentlyPermitted.test(entry.speaker())){
            JsonObject item=new JsonObject();item.addProperty("speaker_id",entry.speaker().toString());
            item.addProperty("speaker",entry.name());item.addProperty("message",entry.message());
            item.addProperty("age_seconds",(now-entry.time())/1000);out.add(item);
        }
        return out;
    }
    private void expire(long now){entries.removeIf(entry->now<entry.time() || now-entry.time()>3_600_000);}
    void clear(){entries.clear();}
}
