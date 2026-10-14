package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded immutable plans and durable execution receipts. No Bukkit or provider access. */
public final class ProjectStore {
    private final MemoryStore files;
    private final Map<String,JsonObject> projects=new TreeMap<>();
    public ProjectStore(Path directory) throws IOException {
        files=new MemoryStore(directory);
        try(var entries=java.nio.file.Files.list(directory)){
            for(Path entry:entries.toList())if(java.nio.file.Files.isSymbolicLink(entry) || !java.nio.file.Files.isRegularFile(entry,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid project ledger entry");
        }
        try {
            for(String file:files.list()){
                if(!file.endsWith(".json"))throw new IllegalArgumentException("Unknown project ledger file");
                JsonObject record=JsonParser.parseString(files.read(file)).getAsJsonObject();
                JsonObject definition=validateDefinition(record.getAsJsonObject("definition"));String id=identifier(definition,"id");
                if(!file.equals(id+".json") || projects.size()>=8 || projects.containsKey(id))throw new IllegalArgumentException("Invalid project ledger identity");
                UUID.fromString(ExperienceSpec.text(record,"owner_id",36,true));ExperienceSpec.text(record,"owner_name",128,true);
                ExperienceSpec.keys(record,Set.of("definition","owner_id","owner_name","state","step_states"));
                String state=ExperienceSpec.text(record,"state",32,true);
                if(!Set.of("saved","active","paused","complete","blocked","needs_inspection").contains(state))throw new IllegalArgumentException("Invalid project state");
                JsonArray statuses=record.getAsJsonArray("step_states"),steps=definition.getAsJsonArray("steps");
                if(statuses==null || statuses.size()!=steps.size())throw new IllegalArgumentException("Invalid step ledger");
                boolean interrupted=false;
                for(int i=0;i<statuses.size();i++){
                    JsonObject status=statuses.get(i).getAsJsonObject();ExperienceSpec.keys(status,Set.of("id","state","receipt"));
                    if(!identifier(status,"id").equals(identifier(steps.get(i).getAsJsonObject(),"id")))throw new IllegalArgumentException("Step identity changed");
                    String progress=ExperienceSpec.text(status,"state",32,true);
                    JsonElement receipt=status.get("receipt");if(receipt==null || !receipt.isJsonPrimitive() || !receipt.getAsJsonPrimitive().isString() || receipt.getAsString().length()>2000 || receipt.getAsString().chars().anyMatch(c->Character.isISOControl(c) && c!=10 && c!=9))throw new IllegalArgumentException("Invalid project receipt");
                    if(!Set.of("pending","running","complete","blocked","needs_inspection").contains(progress))throw new IllegalArgumentException("Invalid step state");
                    if(progress.equals("running")){status.addProperty("state","needs_inspection");status.addProperty("receipt","Interrupted after execution began; inspect actual world/data before a new reviewed plan.");interrupted=true;}
                }
                projects.put(id,record);
                if(interrupted){record.addProperty("state","needs_inspection");persist(id,record);}
            }
        }catch(RuntimeException invalid){throw new IOException("Project ledger validation refused",invalid);}
    }
    public static JsonObject validateDefinition(JsonObject definition){
        try {
            if(definition==null)throw new IllegalArgumentException("Missing project definition");
            ExperienceSpec.bounded(definition,60000);ExperienceSpec.keys(definition,Set.of("id","title","goal","layout","steps"));identifier(definition,"id");
            ExperienceSpec.text(definition,"title",160,true);ExperienceSpec.text(definition,"goal",4096,true);ExperienceSpec.text(definition,"layout",8192,true);
            JsonArray steps=ExperienceSpec.array(definition,"steps");if(steps.isEmpty() || steps.size()>32)throw new IllegalArgumentException("Project requires 1..32 steps");
            Set<String> ids=new HashSet<>();long placements=0;
            for(JsonElement element:steps){
                JsonObject step=element.getAsJsonObject();ExperienceSpec.keys(step,Set.of("id","description","tool","args"));if(!ids.add(identifier(step,"id")))throw new IllegalArgumentException("Duplicate step identity");
                ExperienceSpec.text(step,"description",1024,true);String tool=ExperienceSpec.text(step,"tool",32,true);
                if(!Set.of("observe_world","survey_area","check_build","build","build_in_character","experience","memory").contains(tool))throw new IllegalArgumentException("Unapproved project step");
                if(!step.has("args") || !step.get("args").isJsonObject())throw new IllegalArgumentException("Step arguments required");JsonObject args=step.getAsJsonObject("args");
                if(Set.of("check_build","build","build_in_character").contains(tool)){
                    ExperienceSpec.keys(args,Set.of("world","x","y","z","width","height","depth","replace_existing","operations"));BuildSpec plan=BuildSpec.parse(args);
                    if(plan.replaceExisting() || (tool.equals("build_in_character") && plan.blocks().size()>16))throw new IllegalArgumentException("Unsafe project build");
                    if(!tool.equals("check_build"))placements+=plan.blocks().size();if(placements>32768)throw new IllegalArgumentException("Project placement budget exceeded");
                }else if(tool.equals("experience")){
                    ExperienceSpec.keys(args,Set.of("action","id","definition"));String action=ExperienceSpec.text(args,"action",16,true);
                    if(!Set.of("list","read","save","reload","enable","disable").contains(action))throw new IllegalArgumentException("Unsafe experience step");
                    if(!action.equals("list"))identifier(args,"id");
                    if(action.equals("save")){ExperienceSpec spec=ExperienceSpec.parse(args.getAsJsonObject("definition"));if(!spec.id().equals(identifier(args,"id")))throw new IllegalArgumentException("Experience identity mismatch");}
                }else InitiativePolicy.require(tool,args);
            }
            return definition.deepCopy();
        }catch(RuntimeException invalid){if(invalid instanceof IllegalArgumentException exception)throw exception;throw new IllegalArgumentException("Invalid project definition");}
    }
    private static String identifier(JsonObject object,String key){String id=ExperienceSpec.text(object,key,32,true);if(!id.matches("[a-z][a-z0-9_-]{0,31}"))throw new IllegalArgumentException("Invalid project identity");return id;}
    public synchronized List<String> list(){return List.copyOf(projects.keySet());}
    public synchronized JsonObject read(String id) throws IOException {return record(id).deepCopy();}
    private JsonObject record(String id) throws IOException {if(id==null || !id.matches("[a-z][a-z0-9_-]{0,31}"))throw new IOException("Invalid project id");JsonObject record=projects.get(id);if(record==null)throw new IOException("Project unavailable");return record;}
    public synchronized JsonObject save(JsonObject definition,UUID ownerId,String ownerName) throws IOException {
        JsonObject valid=validateDefinition(definition);String id=identifier(valid,"id");
        if(projects.containsKey(id) || projects.size()>=8)throw new IOException("Existing projects are immutable or project capacity reached");
        if(ownerId==null || ownerName==null || ownerName.isBlank() || ownerName.length()>128 || ownerName.chars().anyMatch(Character::isISOControl))throw new IOException("Invalid project owner");
        JsonObject record=new JsonObject();record.add("definition",valid);record.addProperty("owner_id",ownerId.toString());record.addProperty("owner_name",ownerName);record.addProperty("state","saved");
        JsonArray statuses=new JsonArray();for(JsonElement element:valid.getAsJsonArray("steps")){JsonObject status=new JsonObject();status.addProperty("id",identifier(element.getAsJsonObject(),"id"));status.addProperty("state","pending");status.addProperty("receipt","");statuses.add(status);}record.add("step_states",statuses);
        if(record.toString().getBytes(StandardCharsets.UTF_8).length+statuses.size()*64+256>65536)throw new IOException("Project must reserve space for actual execution receipts");
        persist(id,record);projects.put(id,record);return record.deepCopy();
    }
    private void persist(String id,JsonObject record) throws IOException {if(record.toString().getBytes(StandardCharsets.UTF_8).length>65536)throw new IOException("Project record exceeds byte budget");files.write(id+".json",record.toString());}
    private JsonObject transition(String id,Set<String> permitted,String next) throws IOException {
        JsonObject current=record(id);if(!permitted.contains(current.get("state").getAsString()))throw new IOException("Project requires inspection or is not eligible for this transition");
        JsonObject replacement=current.deepCopy();replacement.addProperty("state",next);persist(id,replacement);projects.put(id,replacement);return replacement.deepCopy();
    }
    public synchronized JsonObject start(String id) throws IOException {return transition(id,Set.of("saved"),"active");}
    public synchronized JsonObject pause(String id) throws IOException {return transition(id,Set.of("active","paused"),"paused");}
    public synchronized JsonObject resume(String id) throws IOException {return transition(id,Set.of("paused"),"active");}
    public synchronized JsonObject beginStep(String id) throws IOException {
        JsonObject current=record(id);if(!current.get("state").getAsString().equals("active"))throw new IOException("Project is not active");
        JsonArray statuses=current.getAsJsonArray("step_states");
        for(int i=0;i<statuses.size();i++){
            String state=statuses.get(i).getAsJsonObject().get("state").getAsString();if(state.equals("complete"))continue;
            if(!state.equals("pending"))throw new IOException("Step requires inspection; automatic replay refused");
            JsonObject replacement=current.deepCopy();replacement.getAsJsonArray("step_states").get(i).getAsJsonObject().addProperty("state","running");persist(id,replacement);projects.put(id,replacement);
            return replacement.getAsJsonObject("definition").getAsJsonArray("steps").get(i).getAsJsonObject().deepCopy();
        }
        throw new IOException("Project has no pending steps");
    }
    public synchronized JsonObject finishStep(String id,String stepId,boolean success,String receipt) throws IOException {
        JsonObject current=record(id),replacement=current.deepCopy();boolean found=false,all=true;
        long unfinished=current.getAsJsonArray("step_states").asList().stream().filter(e->Set.of("pending","running").contains(e.getAsJsonObject().get("state").getAsString())).count();
        int receiptBytes=(int)Math.max(0,(65536-current.toString().getBytes(StandardCharsets.UTF_8).length-256)/Math.max(1,unfinished));
        for(JsonElement element:replacement.getAsJsonArray("step_states")){
            JsonObject status=element.getAsJsonObject();if(status.get("id").getAsString().equals(stepId)){
                if(!status.get("state").getAsString().equals("running"))throw new IOException("Only a running step can receive a tool receipt");
                if(receipt==null)throw new IOException("Missing execution receipt");status.addProperty("state",success?"complete":"blocked");
                String bounded=receipt.substring(0,Math.min(2000,receipt.length())).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","");
                while(!bounded.isEmpty() && bounded.getBytes(StandardCharsets.UTF_8).length>receiptBytes)bounded=bounded.substring(0,bounded.length()-1);
                status.addProperty("receipt",bounded);found=true;
            }
            all&=status.get("state").getAsString().equals("complete");
        }
        if(!found)throw new IOException("Unknown execution step");if(!success)replacement.addProperty("state","blocked");else if(all)replacement.addProperty("state","complete");
        persist(id,replacement);projects.put(id,replacement);return replacement.deepCopy();
    }
}
