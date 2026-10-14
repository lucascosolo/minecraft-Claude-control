package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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
            String state=record.get("state").getAsString();
            text.append(id).append(": ").append(definition.get("title").getAsString()).append("; ").append(state).append("; completed ").append(complete).append('/').append(record.getAsJsonArray("step_states").size()).append("; goal ").append(definition.get("goal").getAsString()).append("; layout ").append(definition.get("layout").getAsString()).append('\n');
            boolean ready=record.getAsJsonArray("step_states").asList().stream().allMatch(e->Set.of("pending","complete").contains(e.getAsJsonObject().get("state").getAsString()));
            if(state.equals("active") && ready)active.add(new Candidate(id,UUID.fromString(record.get("owner_id").getAsString()),record.get("owner_name").getAsString()));
        }
        summary=text.isEmpty()?"No saved projects":text.substring(0,Math.min(8192,text.length()));candidates=List.copyOf(active);
    }
    private void owner(Turn turn) throws Exception {plugin.onServerThread(()->{plugin.authorizedActor(turn);if(!plugin.currentOwner(turn))throw new IllegalStateException("Current owner required");return null;});}
    public ToolReply handle(JsonObject args,Turn turn,GameTools tools) throws Exception {
        ExperienceSpec.keys(args,Set.of("action","id","definition"));String action=ExperienceSpec.text(args,"action",16,true);
        if(action.equals("list")){plugin.onServerThread(()->{plugin.authorizedActor(turn);return null;});return new ToolReply(summary(),false);}
        String id=ExperienceSpec.text(args,"id",32,true);
        if(action.equals("read")){plugin.onServerThread(()->{plugin.authorizedActor(turn);return null;});return new ToolReply(ledger().read(id).toString(),false);}
        if(turn.event())throw new IllegalStateException("Automatic events cannot authorize projects");owner(turn);
        switch(action){
            case "save" -> {JsonObject definition=ProjectStore.validateDefinition(args.getAsJsonObject("definition"));if(!definition.get("id").getAsString().equals(id))throw new IllegalArgumentException("Project identity mismatch");owner(turn);ledger().save(definition,turn.actorId(),turn.actorName());}
            case "start" -> ledger().start(id);
            case "pause" -> {interrupt();ledger().pause(id);}
            case "resume" -> ledger().resume(id);
            case "execute" -> {return execute(id,turn,tools,generation());}
            default -> throw new IllegalArgumentException("Unknown project action");
        }
        refresh();return new ToolReply("Project "+action+" recorded. "+summary(),false);
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
