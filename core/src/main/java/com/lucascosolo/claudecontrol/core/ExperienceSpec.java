package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure, strict experience definition. Event contents never grant authority. */
public record ExperienceSpec(String id, String title, String description, String script,
                             List<String> scriptEvents, List<Rule> rules) {
    public static final Set<String> EVENTS = Set.of("join", "chat", "block_break", "block_place",
        "interact", "enter_area", "timer", "manual", "death", "respawn");
    public record Area(double x, double y, double z, double radius) {}
    public record Rule(String id, String event, String contains, String material, String world,
                       Area area, int threshold, boolean once, int cooldownSeconds, boolean shared,
                       String function, int intervalSeconds, JsonObject params) {
        public Rule { params = params.deepCopy(); }
        @Override public JsonObject params() { return params.deepCopy(); }
    }
    public ExperienceSpec { scriptEvents = List.copyOf(scriptEvents); rules = List.copyOf(rules); }

    public static ExperienceSpec parse(JsonObject input) {
        bounded(input, 32768);
        keys(input, Set.of("id", "title", "description", "script", "script_events", "rules"));
        String id = text(input,"id",32,true), title = text(input,"title",80,true);
        if (!id.matches("[a-z][a-z0-9_-]{0,31}")) throw invalid("Invalid experience id");
        String description = text(input,"description",512,false), script = text(input,"script",16384,false);
        List<String> events = new ArrayList<>();
        if (input.has("script_events")) {
            for (JsonElement e : array(input,"script_events")) {
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()
                    || !EVENTS.contains(e.getAsString()) || events.contains(e.getAsString())) throw invalid("Invalid script event");
                events.add(e.getAsString());
            }
        }
        List<Rule> rules = new ArrayList<>(); Set<String> ids = new HashSet<>();
        if (input.has("rules")) {
            JsonArray values = array(input,"rules");
            if (values.size()>16) throw invalid("At most 16 rules");
            for (JsonElement e : values) {
                if (!e.isJsonObject()) throw invalid("Rule must be an object");
                JsonObject r=e.getAsJsonObject();
                keys(r,Set.of("id","event","contains","material","world","area","threshold","once",
                    "cooldown_seconds","shared","function","interval_seconds","params"));
                String rid=text(r,"id",32,true), event=text(r,"event",32,true), function=text(r,"function",80,true);
                if (!rid.matches("[a-z][a-z0-9_-]{0,31}") || !ids.add(rid)) throw invalid("Invalid or duplicate rule id");
                if (!EVENTS.contains(event) || !function.matches("[A-Za-z_$][A-Za-z0-9_$]{0,79}")) throw invalid("Invalid rule event/function");
                String contains=text(r,"contains",256,false), material=text(r,"material",71,false), world=text(r,"world",128,false);
                if (!material.isEmpty() && !material.matches("[A-Z][A-Z0-9_]{0,70}")) throw invalid("Invalid material");
                Area area=null;
                if (r.has("area")) {
                    if (!r.get("area").isJsonObject()) throw invalid("Area must be an object");
                    JsonObject a=r.getAsJsonObject("area"); keys(a,Set.of("x","y","z","radius"));
                    double x=number(a,"x"),y=number(a,"y"),z=number(a,"z"),radius=number(a,"radius");
                    if (Math.abs(x)>30000000 || Math.abs(z)>30000000 || Math.abs(y)>2048 || radius<1 || radius>64) throw invalid("Area out of bounds");
                    area=new Area(x,y,z,radius);
                }
                if (event.equals("enter_area") && area==null) throw invalid("enter_area requires area");
                int threshold=integer(r,"threshold",1,1,1000), interval=0;
                if (event.equals("timer")) {
                    interval=integer(r,"interval_seconds",-1,5,3600);
                    if (threshold!=1) throw invalid("Timer threshold must be 1");
                } else if (r.has("interval_seconds")) throw invalid("Only timers have intervals");
                JsonObject params=new JsonObject();
                if (r.has("params")) {
                    if (!r.get("params").isJsonObject()) throw invalid("Params must be object");
                    params=r.getAsJsonObject("params"); bounded(params,4096);
                }
                rules.add(new Rule(rid,event,contains,material,world,area,threshold,bool(r,"once",true),
                    integer(r,"cooldown_seconds",2,1,3600),bool(r,"shared",false),function,interval,params));
            }
        }
        if (rules.isEmpty() && events.isEmpty()) throw invalid("A rule or script event is required");
        if (script.isBlank()) throw invalid("Callback script is required");
        return new ExperienceSpec(id,title,description,script,events,rules);
    }
    static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    static void keys(JsonObject o,Set<String> allowed) {
        for (String key:o.keySet()) if (!allowed.contains(key)) throw invalid("Unknown field: "+key);
    }
    static String text(JsonObject o,String key,int max,boolean required) {
        if (!o.has(key)) { if (required) throw invalid("Missing "+key); return ""; }
        JsonElement e=o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw invalid("String required: "+key);
        String s=e.getAsString();
        if (s.length()>max || (required && s.isBlank()) || (!key.equals("script") && s.chars().anyMatch(Character::isISOControl))) throw invalid("Invalid "+key);
        return s;
    }
    static int integer(JsonObject o,String key,int fallback,int min,int max) {
        int n=o.has(key)?BuildSpec.integer(o,key):fallback;
        if(n<min || n>max)throw invalid("Invalid "+key); return n;
    }
    static double number(JsonObject o,String key) {
        JsonElement e=o.get(key);
        if(e==null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())throw invalid("Number required: "+key);
        double n=e.getAsDouble(); if(!Double.isFinite(n))throw invalid("Finite number required");return n;
    }
    static boolean bool(JsonObject o,String key,boolean fallback) {
        if(!o.has(key))return fallback;
        JsonElement e=o.get(key);if(!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean())throw invalid("Boolean required: "+key);
        return e.getAsBoolean();
    }
    static JsonArray array(JsonObject o,String key) {
        if(!o.has(key) || !o.get(key).isJsonArray())throw invalid("Array required: "+key);return o.getAsJsonArray(key);
    }
    static void bounded(JsonElement value,int bytes) {
        if(value==null)throw invalid("JSON required");
        plain(value,0);
        if(value.toString().getBytes(StandardCharsets.UTF_8).length>bytes)throw invalid("JSON exceeds size limit");
    }
    private static void plain(JsonElement e,int depth) {
        if(depth>32)throw invalid("JSON too deeply nested");
        if(e.isJsonObject()) { for(var v:e.getAsJsonObject().entrySet())plain(v.getValue(),depth+1); }
        else if(e.isJsonArray()) { for(var v:e.getAsJsonArray())plain(v,depth+1); }
        else if(e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && !Double.isFinite(e.getAsDouble()))throw invalid("Nonfinite JSON number");
    }
}
