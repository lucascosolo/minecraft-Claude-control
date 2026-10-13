package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import java.util.*;

/** Deterministic progress evaluation; the runtime owns authorization and timers. */
public final class ExperienceRules {
    private ExperienceRules() {}
    public record Call(String function,JsonObject params) {}
    public record Result(JsonObject state,List<Call> calls) {}
    public static Result evaluate(ExperienceSpec spec,JsonObject event,JsonObject prior,long nowMillis) {
        ExperienceSpec.bounded(prior,32768); ExperienceSpec.bounded(event,8192);
        if(nowMillis<0)throw ExperienceSpec.invalid("Invalid clock");
        String type=ExperienceSpec.text(event,"type",32,true);
        if(!ExperienceSpec.EVENTS.contains(type))throw ExperienceSpec.invalid("Unknown event");
        JsonObject state=prior.deepCopy();
        JsonObject progress=object(state,"_rules"); state.add("_rules",progress);
        List<Call> calls=new ArrayList<>();
        for(var rule:spec.rules()) {
            if(!rule.event().equals(type))continue;
            String player=player(event);
            if(player.isEmpty())throw ExperienceSpec.invalid("Player UUID required");
            JsonObject scopes=object(progress,rule.id()); progress.add(rule.id(),scopes);
            // Area entry belongs to a player even when progress is shared.
            String scope=rule.shared()?"shared":player;
            JsonObject p=object(scopes,scope); scopes.add(scope,p);
            ExperienceSpec.keys(p,Set.of("count","last_ms","done"));
            boolean matches=matches(rule,event);
            if(type.equals("enter_area")) {
                JsonObject entries=object(scopes,"_inside");scopes.add("_inside",entries);
                boolean was=ExperienceSpec.bool(entries,player,false);
                entries.addProperty(player,matches);
                if(was || !matches)continue;
            } else if(!matches)continue;
            if(rule.once() && ExperienceSpec.bool(p,"done",false))continue;
            long last=last(p);
            long wait=(long)(type.equals("timer")?Math.max(rule.intervalSeconds(),rule.cooldownSeconds()):rule.cooldownSeconds())*1000;
            if(last>=0 && (nowMillis<last || nowMillis-last<wait))continue;
            int count=p.has("count")?BuildSpec.integer(p,"count"):0;
            if(count<0 || count>1000)throw ExperienceSpec.invalid("Invalid progress");
            count++;
            if(count>=rule.threshold()) {
                p.addProperty("count",0);p.addProperty("last_ms",nowMillis);p.addProperty("done",rule.once());
                calls.add(new Call(rule.function(),rule.params()));
            } else p.addProperty("count",count);
        }
        ExperienceSpec.bounded(state,32768);
        return new Result(state,List.copyOf(calls));
    }
    private static JsonObject object(JsonObject o,String key) {
        if(!o.has(key))return new JsonObject();
        if(!o.get(key).isJsonObject())throw ExperienceSpec.invalid("Invalid state object");
        return o.getAsJsonObject(key);
    }
    private static long last(JsonObject p) {
        if(!p.has("last_ms"))return -1;
        JsonElement value=p.get("last_ms");
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw ExperienceSpec.invalid("Invalid progress time");
        try {
            long n=value.getAsBigDecimal().longValueExact();
            if(n<0)throw ExperienceSpec.invalid("Invalid progress time");return n;
        } catch(ArithmeticException ex) {throw ExperienceSpec.invalid("Invalid progress time");}
    }
    private static String player(JsonObject e) {
        String id=e.has("player_uuid")?ExperienceSpec.text(e,"player_uuid",36,true):"";
        if(id.isEmpty() && e.has("player") && e.get("player").isJsonObject()) {
            JsonObject p=e.getAsJsonObject("player"); if(p.has("uuid"))id=ExperienceSpec.text(p,"uuid",36,true);
        }
        if(!id.isEmpty()) { try { if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException(); }
            catch(IllegalArgumentException ex){throw ExperienceSpec.invalid("Invalid player UUID");} }
        return id;
    }
    private static boolean matches(ExperienceSpec.Rule r,JsonObject e) {
        if(!r.world().isEmpty() && !r.world().equals(string(e,"world")))return false;
        if(!r.material().isEmpty() && !r.material().equals(string(e,"material")))return false;
        if(!r.contains().isEmpty() && !string(e,"message").toLowerCase(Locale.ROOT).contains(r.contains().toLowerCase(Locale.ROOT)))return false;
        if(r.area()!=null) {
            JsonObject pos=e.has("position") && e.get("position").isJsonObject()?e.getAsJsonObject("position"):e;
            if(!pos.has("x") || !pos.has("y") || !pos.has("z"))return false;
            double x=ExperienceSpec.number(pos,"x")-r.area().x(),y=ExperienceSpec.number(pos,"y")-r.area().y(),z=ExperienceSpec.number(pos,"z")-r.area().z();
            if(x*x+y*y+z*z>r.area().radius()*r.area().radius())return false;
        }
        return true;
    }
    private static String string(JsonObject e,String key) {
        return e.has(key)?ExperienceSpec.text(e,key,512,false):"";
    }
}
