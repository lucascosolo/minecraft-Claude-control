package com.lucascosolo.claudecontrol.core;

import com.google.gson.JsonObject;
import java.util.Set;

/** Pure tool envelope for an explicitly invited autonomous turn; never grants authority. */
public final class InitiativePolicy {
    private InitiativePolicy(){}
    public static int nextDelaySeconds(int base,int randomOffset){
        if(base<60 || base>3600 || randomOffset<0 || randomOffset>Math.min(base/2,3600-base))throw new IllegalArgumentException("Invalid initiative delay");
        return base+randomOffset;
    }
    public static void require(String name,JsonObject args){
        if(name==null || args==null)throw new IllegalArgumentException("Invalid initiative tool");
        try {
        ExperienceSpec.bounded(args,32768);
        switch(name){
            case "observe_world" -> ExperienceSpec.keys(args,Set.of());
            case "survey_area" -> {ExperienceSpec.keys(args,Set.of("radius","x","z"));ExperienceSpec.integer(args,"radius",8,1,16);for(String key:Set.of("x","z"))if(args.has(key))BuildSpec.integer(args,key);}
            case "send_chat" -> {ExperienceSpec.keys(args,Set.of("message","private","broadcast"));ExperienceSpec.text(args,"message",1200,true);ExperienceSpec.bool(args,"private",false);if(ExperienceSpec.bool(args,"broadcast",false))throw refused();}
            case "companion" -> {
                String action=ExperienceSpec.text(args,"action",32,true);
                if(!Set.of("follow","wave","status","move","wander","design").contains(action))throw refused();
                ExperienceSpec.keys(args,action.equals("move")?Set.of("action","x","y","z","duration_seconds"):Set.of("action","duration_seconds"));
                if(action.equals("move"))for(String key:Set.of("x","y","z"))BuildSpec.integer(args,key);
                ExperienceSpec.integer(args,"duration_seconds",30,10,60);
            }
            case "memory" -> {
                ExperienceSpec.keys(args,Set.of("action","name","text"));String action=ExperienceSpec.text(args,"action",16,true);
                if(!Set.of("list","read","write").contains(action))throw refused();
                if(!action.equals("list")){String file=ExperienceSpec.text(args,"name",128,true);if(!file.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}") || file.contains(".."))throw refused();}
                if(action.equals("write"))ExperienceSpec.text(args,"text",16384,true);
            }
            case "give_item" -> {ExperienceSpec.keys(args,Set.of("material","amount"));String material=ExperienceSpec.text(args,"material",71,true);if(!BuildSpec.safeMaterial(material))throw refused();ExperienceSpec.integer(args,"amount",-1,1,16);}
            case "build","build_in_character" -> {
                ExperienceSpec.keys(args,Set.of("world","x","y","z","width","height","depth","replace_existing","operations"));
                BuildSpec spec=BuildSpec.parse(args);if(spec.replaceExisting() || spec.blocks().isEmpty() || spec.blocks().size()>16)throw refused();
            }
            case "experience" -> {
                ExperienceSpec.keys(args,Set.of("action","id","definition"));String action=ExperienceSpec.text(args,"action",16,true);
                if(!Set.of("list","read","save","reload").contains(action))throw refused();
                if(!action.equals("list") && !ExperienceSpec.text(args,"id",32,true).matches("[a-z][a-z0-9_-]{0,31}"))throw refused();
                if(action.equals("save")){if(!args.has("definition") || !args.get("definition").isJsonObject())throw refused();ExperienceSpec.parse(args.getAsJsonObject("definition"));}
            }
            default -> throw refused();
        }
        }catch(RuntimeException invalid){if(invalid instanceof IllegalArgumentException exception)throw exception;throw refused();}
    }
    private static IllegalArgumentException refused(){return new IllegalArgumentException("Tool is outside the invited initiative scope");}
}
