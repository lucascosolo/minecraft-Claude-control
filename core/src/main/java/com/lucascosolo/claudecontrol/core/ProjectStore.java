package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded immutable plans and durable execution receipts. No Bukkit or provider access. */
public final class ProjectStore {
    public static final class ValidationException extends IOException {
        public ValidationException(String message){super(message);}
    }
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
                boolean sealed=ExperienceSpec.bool(record,"planning_complete",true);
                JsonObject definition=validateDefinition(record.getAsJsonObject("definition"),!sealed);String id=identifier(definition,"id");
                if(!file.equals(id+".json") || projects.size()>=8 || projects.containsKey(id))throw new IllegalArgumentException("Invalid project ledger identity");
                UUID.fromString(ExperienceSpec.text(record,"owner_id",36,true));ExperienceSpec.text(record,"owner_name",128,true);
                ExperienceSpec.keys(record,Set.of("definition","owner_id","owner_name","state","step_states","planning_complete"));
                String state=ExperienceSpec.text(record,"state",32,true);
                if(!Set.of("planning","saved","active","paused","complete","blocked","needs_inspection").contains(state) || sealed==state.equals("planning"))throw new IllegalArgumentException("Invalid project state");
                JsonArray statuses=record.getAsJsonArray("step_states"),steps=definition.getAsJsonArray("steps");
                if(statuses==null || statuses.size()!=steps.size())throw new IllegalArgumentException("Invalid step ledger");
                boolean interrupted=false;
                for(int i=0;i<statuses.size();i++){
                    JsonObject status=statuses.get(i).getAsJsonObject();ExperienceSpec.keys(status,Set.of("id","state","receipt"));
                    if(!identifier(status,"id").equals(identifier(steps.get(i).getAsJsonObject(),"id")))throw new IllegalArgumentException("Step identity changed");
                    String progress=ExperienceSpec.text(status,"state",32,true);
                    JsonElement receipt=status.get("receipt");if(receipt==null || !receipt.isJsonPrimitive() || !receipt.getAsJsonPrimitive().isString() || receipt.getAsString().length()>2000 || receipt.getAsString().chars().anyMatch(c->Character.isISOControl(c) && c!=10 && c!=9))throw new IllegalArgumentException("Invalid project receipt");
                    if(!Set.of("pending","running","complete","blocked","needs_inspection").contains(progress) || (!sealed && !progress.equals("pending")))throw new IllegalArgumentException("Invalid step state");
                    if(progress.equals("running")){status.addProperty("state","needs_inspection");status.addProperty("receipt","Interrupted after execution began; inspect actual world/data before a new reviewed plan.");interrupted=true;}
                }
                projects.put(id,record);
                if(interrupted){record.addProperty("state","needs_inspection");persist(id,record);}
            }
        }catch(RuntimeException invalid){throw new IOException("Project ledger validation refused",invalid);}
    }
    public static JsonObject validateDefinition(JsonObject definition){return validateDefinition(definition,false);}
    private static JsonObject validateDefinition(JsonObject definition,boolean allowEmpty){
        try {
            if(definition==null)throw new IllegalArgumentException("Missing project definition");
            ExperienceSpec.bounded(definition,60000);ExperienceSpec.keys(definition,Set.of("id","title","goal","layout","steps"));identifier(definition,"id");
            ExperienceSpec.text(definition,"title",160,true);ExperienceSpec.text(definition,"goal",4096,true);ExperienceSpec.text(definition,"layout",8192,true);
            JsonArray steps=ExperienceSpec.array(definition,"steps");if((steps.isEmpty() && !allowEmpty) || steps.size()>32)throw new IllegalArgumentException("Project requires 1..32 steps");
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
    private JsonObject record(String id) throws IOException {if(id==null || !id.matches("[a-z][a-z0-9_-]{0,31}"))throw new ValidationException("Invalid project id");JsonObject record=projects.get(id);if(record==null)throw new ValidationException("Project unavailable");return record;}
    public synchronized JsonObject save(JsonObject definition,UUID ownerId,String ownerName) throws IOException {
        return createRecord(validateDefinition(definition),ownerId,ownerName,true);
    }
    public synchronized JsonObject create(JsonObject definition,UUID ownerId,String ownerName) throws IOException {
        JsonObject valid=validateDefinition(definition,true);if(!valid.getAsJsonArray("steps").isEmpty())throw new IllegalArgumentException("Create requires steps:[]; append one to four small stages per call");
        return createRecord(valid,ownerId,ownerName,false);
    }
    private JsonObject createRecord(JsonObject valid,UUID ownerId,String ownerName,boolean sealed) throws IOException {
        String id=identifier(valid,"id");
        if(projects.containsKey(id) || projects.size()>=8)throw new ValidationException("Existing projects are immutable or project capacity reached");
        if(ownerId==null || ownerName==null || ownerName.isBlank() || ownerName.length()>128 || ownerName.chars().anyMatch(Character::isISOControl))throw new ValidationException("Invalid project owner");
        JsonObject record=new JsonObject();record.add("definition",valid);record.addProperty("owner_id",ownerId.toString());record.addProperty("owner_name",ownerName);record.addProperty("state",sealed?"saved":"planning");record.addProperty("planning_complete",sealed);
        JsonArray statuses=new JsonArray();for(JsonElement element:valid.getAsJsonArray("steps")){JsonObject status=new JsonObject();status.addProperty("id",identifier(element.getAsJsonObject(),"id"));status.addProperty("state","pending");status.addProperty("receipt","");statuses.add(status);}record.add("step_states",statuses);
        requireReceiptSpace(record);
        persist(id,record);projects.put(id,record);return record.deepCopy();
    }
    private static void requireReceiptSpace(JsonObject record) throws IOException {
        if(record.toString().getBytes(StandardCharsets.UTF_8).length+record.getAsJsonArray("step_states").size()*64+256>65536)throw new ValidationException("Project must reserve space for actual execution receipts; shorten descriptions or split into another reviewed project");
    }
    private JsonObject planning(String id) throws IOException {
        JsonObject current=record(id);if(!current.get("state").getAsString().equals("planning") || ExperienceSpec.bool(current,"planning_complete",true))throw new ValidationException("Only an unsealed planning draft accepts new stages; existing and begun plans are immutable");return current;
    }
    public synchronized JsonObject append(String id,JsonArray steps) throws IOException {
        JsonObject current=planning(id);
        if(steps==null || steps.isEmpty() || steps.size()>4 || steps.toString().getBytes(StandardCharsets.UTF_8).length>6000)throw new IllegalArgumentException("Append requires one to four stages totaling at most6000 UTF-8 bytes; send one floor or feature per call");
        JsonObject merged=current.getAsJsonObject("definition").deepCopy();for(JsonElement step:steps)merged.getAsJsonArray("steps").add(step.deepCopy());JsonObject valid=validateDefinition(merged);
        JsonObject replacement=current.deepCopy();replacement.add("definition",valid);
        for(JsonElement element:steps){JsonObject status=new JsonObject();status.addProperty("id",identifier(element.getAsJsonObject(),"id"));status.addProperty("state","pending");status.addProperty("receipt","");replacement.getAsJsonArray("step_states").add(status);}
        requireReceiptSpace(replacement);persist(id,replacement);projects.put(id,replacement);return replacement.deepCopy();
    }
    public synchronized JsonObject seal(String id) throws IOException {
        JsonObject current=planning(id);if(current.getAsJsonObject("definition").getAsJsonArray("steps").isEmpty())throw new ValidationException("Append at least one compact stage before sealing this planning draft");validateDefinition(current.getAsJsonObject("definition"));
        JsonObject replacement=current.deepCopy();replacement.addProperty("state","saved");replacement.addProperty("planning_complete",true);requireReceiptSpace(replacement);persist(id,replacement);projects.put(id,replacement);return replacement.deepCopy();
    }
    private static String clipped(String text,int maxBytes){
        StringBuilder out=new StringBuilder();int used=0;
        for(int offset=0;offset<text.length();){int point=text.codePointAt(offset);String part=new String(Character.toChars(point));int bytes=part.getBytes(StandardCharsets.UTF_8).length;if(used+bytes>maxBytes)break;out.append(part);used+=bytes;offset+=Character.charCount(point);}return out.toString();
    }
    public synchronized JsonObject view(String id,String stepId,int offset,int limit) throws IOException {
        if(offset<0 || limit<1 || limit>4)throw new IllegalArgumentException("Read offset must be nonnegative and limit between1 and4");
        JsonObject current=record(id),definition=current.getAsJsonObject("definition");JsonArray all=definition.getAsJsonArray("steps"),statuses=current.getAsJsonArray("step_states");
        JsonObject result=new JsonObject();result.addProperty("id",id);result.addProperty("title",definition.get("title").getAsString());result.addProperty("goal",clipped(definition.get("goal").getAsString(),1024));result.addProperty("layout",clipped(definition.get("layout").getAsString(),2048));result.addProperty("state",current.get("state").getAsString());result.addProperty("planning_complete",ExperienceSpec.bool(current,"planning_complete",true));result.addProperty("total_steps",all.size());
        if(stepId!=null){
            boolean found=false;for(int i=0;i<all.size();i++)if(all.get(i).getAsJsonObject().get("id").getAsString().equals(stepId)){result.add("step",all.get(i).deepCopy());result.add("step_status",statuses.get(i).deepCopy());found=true;break;}
            if(!found)throw new ValidationException("Requested project stage is unavailable; read the stage summary first");
        }else{
            JsonArray page=new JsonArray();int end=(int)Math.min(all.size(),(long)offset+limit);
            for(int i=Math.min(offset,all.size());i<end;i++){JsonObject step=all.get(i).getAsJsonObject(),status=statuses.get(i).getAsJsonObject(),row=new JsonObject();row.add("id",step.get("id").deepCopy());row.addProperty("description",clipped(step.get("description").getAsString(),256));row.add("tool",step.get("tool").deepCopy());row.add("state",status.get("state").deepCopy());row.addProperty("receipt",clipped(status.get("receipt").getAsString(),256));page.add(row);}
            result.addProperty("offset",offset);result.addProperty("limit",limit);result.add("next_offset",end<all.size()?new JsonPrimitive(end):JsonNull.INSTANCE);result.add("steps",page);
        }
        if(stepId==null && result.toString().getBytes(StandardCharsets.UTF_8).length>7500){
            result.addProperty("goal",clipped(result.get("goal").getAsString(),320));result.addProperty("layout",clipped(result.get("layout").getAsString(),640));
            for(JsonElement element:result.getAsJsonArray("steps")){JsonObject row=element.getAsJsonObject();row.addProperty("description",clipped(row.get("description").getAsString(),128));row.addProperty("receipt",clipped(row.get("receipt").getAsString(),128));}
            result.addProperty("summary_abbreviated",true);
        }
        if(result.toString().getBytes(StandardCharsets.UTF_8).length>7500)throw new ValidationException("Requested project stage detail exceeds safe receipt size; use paged stage summaries instead. Stored stage remains intact and was not truncated");
        return result;
    }
    private void persist(String id,JsonObject record) throws IOException {if(record.toString().getBytes(StandardCharsets.UTF_8).length>65536)throw new ValidationException("Project record exceeds byte budget");files.write(id+".json",record.toString());}
    private JsonObject transition(String id,Set<String> permitted,String next) throws IOException {
        JsonObject current=record(id);if(!permitted.contains(current.get("state").getAsString()))throw new ValidationException("Project requires inspection or is not eligible for this transition");
        JsonObject replacement=current.deepCopy();replacement.addProperty("state",next);persist(id,replacement);projects.put(id,replacement);return replacement.deepCopy();
    }
    public synchronized JsonObject start(String id) throws IOException {return transition(id,Set.of("saved"),"active");}
    public synchronized JsonObject pause(String id) throws IOException {return transition(id,Set.of("active","paused"),"paused");}
    public synchronized JsonObject resume(String id) throws IOException {return transition(id,Set.of("paused"),"active");}
    public synchronized JsonObject beginStep(String id) throws IOException {
        JsonObject current=record(id);if(!current.get("state").getAsString().equals("active"))throw new ValidationException("Project is not active");
        JsonArray statuses=current.getAsJsonArray("step_states");
        for(int i=0;i<statuses.size();i++){
            String state=statuses.get(i).getAsJsonObject().get("state").getAsString();if(state.equals("complete"))continue;
            if(!state.equals("pending"))throw new ValidationException("Step requires inspection; automatic replay refused");
            JsonObject replacement=current.deepCopy();replacement.getAsJsonArray("step_states").get(i).getAsJsonObject().addProperty("state","running");persist(id,replacement);projects.put(id,replacement);
            return replacement.getAsJsonObject("definition").getAsJsonArray("steps").get(i).getAsJsonObject().deepCopy();
        }
        throw new ValidationException("Project has no pending steps");
    }
    public synchronized JsonObject finishStep(String id,String stepId,boolean success,String receipt) throws IOException {
        JsonObject current=record(id),replacement=current.deepCopy();boolean found=false,all=true;
        long unfinished=current.getAsJsonArray("step_states").asList().stream().filter(e->Set.of("pending","running").contains(e.getAsJsonObject().get("state").getAsString())).count();
        int receiptBytes=(int)Math.max(0,(65536-current.toString().getBytes(StandardCharsets.UTF_8).length-256)/Math.max(1,unfinished));
        for(JsonElement element:replacement.getAsJsonArray("step_states")){
            JsonObject status=element.getAsJsonObject();if(status.get("id").getAsString().equals(stepId)){
                if(!status.get("state").getAsString().equals("running"))throw new ValidationException("Only a running step can receive a tool receipt");
                if(receipt==null)throw new ValidationException("Missing execution receipt");status.addProperty("state",success?"complete":"blocked");
                String bounded=receipt.substring(0,Math.min(2000,receipt.length())).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","");
                while(!bounded.isEmpty() && bounded.getBytes(StandardCharsets.UTF_8).length>receiptBytes)bounded=bounded.substring(0,bounded.length()-1);
                status.addProperty("receipt",bounded);found=true;
            }
            all&=status.get("state").getAsString().equals("complete");
        }
        if(!found)throw new ValidationException("Unknown execution step");if(!success)replacement.addProperty("state","blocked");else if(all)replacement.addProperty("state","complete");
        persist(id,replacement);projects.put(id,replacement);return replacement.deepCopy();
    }
}
