package com.lucascosolo.claudecontrol.core;

import com.google.gson.JsonObject;
import java.util.Set;

/** Validates deliberate owner world effects without accessing live server state. */
public record WorldEffectSpec(String world,String action,String value,int durationSeconds,long timeTicks) {
    public static WorldEffectSpec parse(JsonObject input) {
        String world=string(input,"world"),action=string(input,"action"),value=string(input,"value");
        if(world.isBlank() || world.length()>128 || world.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("A named world or all is required");
        int duration=input.has("duration_seconds")?BuildSpec.integer(input,"duration_seconds"):300;
        if(duration<1 || duration>1800)throw new IllegalArgumentException("Duration must be between 1 and 1800 seconds");
        long ticks=0;
        switch(action) {
            case "time" -> ticks=switch(value) {
                case "sunrise" -> 0L;
                case "day" -> 1000L;
                case "sunset" -> 12000L;
                case "night" -> 13000L;
                default -> throw new IllegalArgumentException("Unknown time preset");
            };
            case "weather" -> {if(!Set.of("clear","rain","thunder").contains(value))throw new IllegalArgumentException("Unknown weather preset");}
            default -> throw new IllegalArgumentException("Unknown world effect");
        }
        return new WorldEffectSpec(world,action,value,duration,ticks);
    }
    private static String string(JsonObject input,String key) {
        var value=input.get(key);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("String required: "+key);
        return value.getAsString();
    }
}
