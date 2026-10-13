package com.lucascosolo.claudecontrol.core;
import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.UUID;
public record Turn(UUID actorId, String actorName, boolean privileged, String text, boolean event) {
    public Turn { Objects.requireNonNull(actorId); Objects.requireNonNull(actorName); Objects.requireNonNull(text); if(text.length()>8192 || actorName.length()>128) throw new IllegalArgumentException("Trigger exceeds limits"); }
    public String envelope(String worldReport) {
        if(worldReport==null || worldReport.length()>32768) throw new IllegalArgumentException("World report exceeds limits");
        JsonObject trigger=new JsonObject(); trigger.addProperty("actor_id",actorId.toString()); trigger.addProperty("actor_name",actorName); trigger.addProperty("privileged",privileged); trigger.addProperty("text",text); trigger.addProperty("event",event);
        JsonObject result=new JsonObject(); result.add("trigger",trigger); result.addProperty("world",worldReport); return result.toString();
    }
}
