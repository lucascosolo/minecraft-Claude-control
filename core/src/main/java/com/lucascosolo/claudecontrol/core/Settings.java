package com.lucascosolo.claudecontrol.core;

import java.util.*;

/** Immutable settings; diagnostics deliberately never include configuration values. */
public final class Settings {
    private final String apiKey, model, effort, chatPrefix;
    private final int maxTokens, maxToolIterations, maxTurnsPerMinute, maxQueuedTriggers, historyTurns, eventIntervalMinutes, initiativeIntervalSeconds;
    private final boolean eventsEnabled;
    private final Set<String> owners, deniedCommands;
    private Settings(Map<String,Object> c, Map<String,String> environment) {
        if (!string(c, "backend", "api").equals("api")) throw invalid("backend");
        String env = environment.getOrDefault("ANTHROPIC_API_KEY", "").trim();
        apiKey = env.isEmpty() ? string(c,"api-key","").trim() : env;
        if (apiKey.chars().anyMatch(Character::isISOControl)) throw invalid("api-key");
        model = string(c,"model","claude-opus-5-5").trim();
        if (!model.matches("claude-[a-zA-Z0-9.-]{1,80}")) throw invalid("model");
        effort = string(c,"effort","low").trim();
        if (!Set.of("low","medium","high","max").contains(effort)) throw invalid("effort");
        maxTokens = number(c,"max-tokens",8192,128,32768);
        maxToolIterations = number(c,"max-tool-iterations",8,1,16);
        maxTurnsPerMinute = number(c,"max-turns-per-minute",4,1,60);
        maxQueuedTriggers = number(c,"max-queued-triggers",32,1,128);
        historyTurns = number(c,"history-turns",16,1,32);
        eventIntervalMinutes = number(c,"event-interval-minutes",30,1,1440);
        initiativeIntervalSeconds = number(c,"initiative-interval-seconds",1800,60,3600);
        chatPrefix = string(c,"chat-prefix","<Claude>");
        if (chatPrefix.length()>80 || chatPrefix.chars().anyMatch(Character::isISOControl)) throw invalid("chat-prefix");
        Object enabled=c.getOrDefault("events-enabled",false);
        if (!(enabled instanceof Boolean)) throw invalid("events-enabled");
        eventsEnabled=(Boolean)enabled;
        owners = names(c,"owners"); deniedCommands = names(c,"denied-commands");
    }
    public static Settings from(Map<String,Object> config, Map<String,String> environment) { return new Settings(config,environment); }
    private static IllegalArgumentException invalid(String key) { return new IllegalArgumentException("Invalid setting: " + key); }
    private static String string(Map<String,Object> c,String key,String fallback) { Object v=c.getOrDefault(key,fallback); if (!(v instanceof String)) throw invalid(key); return (String)v; }
    private static int number(Map<String,Object> c,String key,int fallback,int min,int max) {
        Object v=c.getOrDefault(key,fallback); if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()!=n.intValue() || n.intValue()<min || n.intValue()>max) throw invalid(key); return n.intValue();
    }
    private static Set<String> names(Map<String,Object> c,String key) {
        Object v=c.getOrDefault(key,List.of()); if (!(v instanceof Collection<?> values) || values.size()>128) throw invalid(key);
        Set<String> result=new HashSet<>(); for(Object item:values) { if (!(item instanceof String s) || s.isBlank() || s.length()>128 || s.chars().anyMatch(Character::isISOControl)) throw invalid(key); result.add(s.trim().toLowerCase(Locale.ROOT)); } return Set.copyOf(result);
    }
    public boolean isOwner(UUID id,String name,boolean operator) { return operator || owners.contains(id.toString().toLowerCase(Locale.ROOT)) || owners.contains(name.toLowerCase(Locale.ROOT)); }
    public String apiKey(){return apiKey;} public String model(){return model;} public String effort(){return effort;} public String chatPrefix(){return chatPrefix;}
    public int maxTokens(){return maxTokens;} public int maxToolIterations(){return maxToolIterations;} public int maxTurnsPerMinute(){return maxTurnsPerMinute;} public int maxQueuedTriggers(){return maxQueuedTriggers;} public int historyTurns(){return historyTurns;} public int eventIntervalMinutes(){return eventIntervalMinutes;}
    public int initiativeIntervalSeconds(){return initiativeIntervalSeconds;}
    public boolean eventsEnabled(){return eventsEnabled;} public Set<String> deniedCommands(){return deniedCommands;}
    @Override public String toString(){return "Settings[backend=api, credentials="+(apiKey.isBlank()?"absent":"configured")+"]";}
}
