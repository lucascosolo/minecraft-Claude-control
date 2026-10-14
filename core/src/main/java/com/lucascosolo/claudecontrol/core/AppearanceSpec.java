package com.lucascosolo.claudecontrol.core;

import com.google.gson.JsonObject;
import java.util.Set;

/** Bounded cosmetic preferences, never entity identity or a remote texture URL. */
public record AppearanceSpec(String account,String preset) {
    public AppearanceSpec {
        if(account==null || (!account.isEmpty() && !account.matches("[A-Za-z0-9_]{1,16}")))
            throw new IllegalArgumentException("Use a Minecraft account name, or an empty account to reset the skin");
        if(preset==null || !Set.of("plain","hatter","explorer").contains(preset))throw new IllegalArgumentException("Unknown appearance preset");
    }
    public static AppearanceSpec defaults(){return new AppearanceSpec("","hatter");}
    public static AppearanceSpec parse(JsonObject input,AppearanceSpec current) {
        ExperienceSpec.keys(input,Set.of("action","account","preset"));
        if(input.has("action") && !text(input,"action").equals("appearance"))throw new IllegalArgumentException("Expected appearance action");
        if(!input.has("account") && !input.has("preset"))throw new IllegalArgumentException("Specify account or preset");
        return new AppearanceSpec(input.has("account")?text(input,"account"):current.account,
            input.has("preset")?text(input,"preset"):current.preset);
    }
    private static String text(JsonObject input,String key) {
        var value=input.get(key);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Appearance values must be strings");
        return value.getAsString();
    }
    public JsonObject json(){JsonObject value=new JsonObject();value.addProperty("account",account);value.addProperty("preset",preset);return value;}
}
