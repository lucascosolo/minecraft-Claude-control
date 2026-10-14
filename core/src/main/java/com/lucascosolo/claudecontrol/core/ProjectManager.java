package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime adapter; ledger I/O always runs on the existing serial API worker. */
public final class ProjectManager {
    public record Candidate(String id,UUID ownerId,String ownerName){}
    private final ClaudeRuntime plugin;
    private final AtomicLong generation=new AtomicLong();
    private volatile ProjectStore store;
    private volatile String summary="Projects initializing";
    private volatile List<Candidate> candidates=List.of();
    public ProjectManager(ClaudeRuntime plugin){this.plugin=plugin;}
    public void load() throws Exception {store=new ProjectStore(plugin.getDataFolder().toPath().resolve("projects"));refresh();}
    public String summary(){return summary;}
    public List<Candidate> candidates(){return candidates;}
    public long generation(){return generation.get();}
    public void interrupt(){generation.incrementAndGet();}
    private ProjectStore ledger(){if(store==null)throw new IllegalStateException("Project ledger unavailable");return store;}
    private void refresh() throws Exception {
        StringBuilder text=new StringBuilder();List<Candidate> active=new ArrayList<>();
        for(String id:ledger().list()){
            JsonObject record=ledger().read(id),definition=record.getAsJsonObject("definition");long complete=0;
            for(JsonElement element:record.getAsJsonArray("step_states"))if(element.getAsJsonObject().get("state").getAsString().equals("complete"))complete++;
            String state=record.get("state").getAsString();StringBuilder row=new StringBuilder();
            row.append(id).append(": ").append(abbreviate(definition.get("title").getAsString(),80)).append("; ").append(state).append("; completed ").append(complete).append('/').append(record.getAsJsonArray("step_states").size()).append("; goal ").append(abbreviate(definition.get("goal").getAsString(),128)).append("; layout ").append(abbreviate(definition.get("layout").getAsString(),256));
            int shown=0;for(int index=0;index<definition.getAsJsonArray("steps").size() && shown<4;index++){JsonObject step=definition.getAsJsonArray("steps").get(index).getAsJsonObject();if(record.getAsJsonArray("step_states").get(index).getAsJsonObject().get("state").getAsString().equals("complete"))continue;row.append("; stage ").append(step.get("id").getAsString()).append(": ").append(abbreviate(step.get("description").getAsString(),48));shown++;}
            text.append(abbreviate(row.toString(),870)).append('\n');
            boolean ready=record.getAsJsonArray("step_states").asList().stream().allMatch(e->Set.of("pending","complete").contains(e.getAsJsonObject().get("state").getAsString()));
            if(state.equals("active") && ready)active.add(new Candidate(id,UUID.fromString(record.get("owner_id").getAsString()),record.get("owner_name").getAsString()));
        }
        summary=text.isEmpty()?"No saved projects":text.toString();candidates=List.copyOf(active);
    }
    private void owner(Turn turn) throws Exception {plugin.onServerThread(()->{plugin.authorizedActor(turn);if(!plugin.currentOwner(turn))throw new IllegalStateException("Current owner required");return null;});}
    private static String abbreviate(String text,int maxBytes){
        StringBuilder result=new StringBuilder();int bytes=0;
        for(int offset=0;offset<text.length();){int point=text.codePointAt(offset),size=point<=127?1:point<=2047?2:point<=65535?3:4;if(bytes+size>maxBytes)break;result.appendCodePoint(point);bytes+=size;offset+=Character.charCount(point);}return result.toString();
    }
    private void originalOwner(String id,Turn turn) throws Exception {
        owner(turn);JsonObject record=ledger().read(id);
        if(!record.get("owner_id").getAsString().equals(turn.actorId().toString()) || !record.get("owner_name").getAsString().equals(turn.actorName()))throw new ProjectStore.ValidationException("Only the original authenticated project owner can append or seal its draft");
    }
    public ToolReply handle(JsonObject args,Turn turn,GameTools tools) throws Exception {
        try {
            if(args==null)throw new IllegalArgumentException("Project arguments required");
            ExperienceSpec.keys(args,Set.of("action","id","definition","steps","step_id","offset","limit"));String action=ExperienceSpec.text(args,"action",16,true);
            if(action.equals("list")){plugin.onServerThread(()->{plugin.authorizedActor(turn);return null;});return new ToolReply(summary(),false);}
            String id=ExperienceSpec.text(args,"id",32,true);
            if(action.equals("read")){
                plugin.onServerThread(()->{plugin.authorizedActor(turn);return null;});
                String stepId=args.has("step_id")?ExperienceSpec.text(args,"step_id",32,true):null;int offset=ExperienceSpec.integer(args,"offset",0,0,Integer.MAX_VALUE),limit=ExperienceSpec.integer(args,"limit",4,1,4);
                return new ToolReply(ledger().view(id,stepId,offset,limit).toString(),false);
            }
            if(turn.event())throw new IllegalStateException("Automatic events cannot authorize projects");owner(turn);
            switch(action){
                case "save","create" -> {
                    if(!args.has("definition") || !args.get("definition").isJsonObject())throw new IllegalArgumentException("Provide definition with id,title,goal,layout,steps; create uses steps:[]");
                    JsonObject definition=args.getAsJsonObject("definition");if(!ExperienceSpec.text(definition,"id",32,true).equals(id))throw new IllegalArgumentException("Project identity mismatch");owner(turn);
                    if(action.equals("create"))ledger().create(definition,turn.actorId(),turn.actorName());else ledger().save(definition,turn.actorId(),turn.actorName());
                }
                case "append" -> {
                    if(!args.has("steps") || !args.get("steps").isJsonArray())throw new IllegalArgumentException("Append requires steps:[...] with one to four compact stages, at most6000 UTF-8 bytes");
                    originalOwner(id,turn);ledger().append(id,args.getAsJsonArray("steps"));
                }
                case "seal" -> {originalOwner(id,turn);ledger().seal(id);}
                case "start" -> ledger().start(id);
                case "pause" -> {interrupt();ledger().pause(id);}
                case "resume" -> ledger().resume(id);
                case "execute" -> {return execute(id,turn,tools,generation());}
                default -> throw new IllegalArgumentException("Unknown project action; use create,append,seal,start,list,read,pause,resume,execute or legacy save");
            }
            refresh();return new ToolReply("Project "+action+" recorded. "+summary(),false);
        }catch(ProjectStore.ValidationException invalid){return new ToolReply("PROJECT_REFUSED: "+invalid.getMessage(),true);}
        catch(IllegalArgumentException invalid){return new ToolReply("PROJECT_PLAN_INVALID: "+invalid.getMessage()+". Keep the coherent header, append one compact floor or feature per response, then seal before start. Existing stored stages were preserved.",true);}
        catch(java.io.IOException unavailable){return new ToolReply("PROJECT_STORAGE_REFUSED: Project data could not be read or persisted. Existing plans and execution receipts were preserved; inspect private storage access before retrying.",true);}
    }
    public ToolReply execute(String id,Turn turn,GameTools tools,long expected) throws Exception {
        if(expected!=generation())return new ToolReply("Project step interrupted before execution",true);
        owner(turn);JsonObject record=ledger().read(id);
        if(!record.get("owner_id").getAsString().equals(turn.actorId().toString()) || !record.get("owner_name").getAsString().equals(turn.actorName()))throw new IllegalStateException("Project steps require the original authenticated owner");
        JsonObject step=ledger().beginStep(id);refresh();ToolReply receipt;
        try {
            receipt=plugin.projectExecution(turn,()->expected==generation(),()->tools.execute(step.get("tool").getAsString(),step.getAsJsonObject("args").deepCopy(),turn));
            if(receipt==null)throw new IllegalStateException("Missing actual tool receipt");
        }catch(Exception refused){receipt=new ToolReply("Project execution interrupted or refused; inspect actual world/data before a new reviewed plan. No automatic replay.",true);}
        // A failure to persist this receipt deliberately leaves RUNNING: startup requires inspection.
        ledger().finishStep(id,step.get("id").getAsString(),!receipt.error(),receipt.text());refresh();return receipt;
    }
}
