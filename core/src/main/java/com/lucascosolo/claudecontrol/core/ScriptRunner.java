package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.mozilla.javascript.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/** One bounded child, no shell, no pending request queue. Safe to call away from the tick thread. */
public final class ScriptRunner implements AutoCloseable {
    public record Result(JsonObject state,JsonArray actions) {}
    private static final Set<String> ACTIONS=Set.of("chat","title","sound","particle","give_item","build","world_effect","companion");
    private final ReentrantLock lock=new ReentrantLock();
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"experience-script-io");t.setDaemon(true);return t;});
    private volatile Process child;
    private volatile Process retiringChild;
    private volatile boolean closed;
    private BufferedWriter input;
    private Reader output;

    public Result run(String script,String function,JsonObject event,JsonObject state,JsonObject params) {
        if(script==null || script.isBlank() || script.length()>16384 || function==null || !function.matches("[A-Za-z_$][A-Za-z0-9_$]{0,79}"))throw ExperienceSpec.invalid("Invalid script callback");
        ExperienceSpec.bounded(event,8192);ExperienceSpec.bounded(state,32768);ExperienceSpec.bounded(params,4096);
        JsonObject request=new JsonObject();request.addProperty("op","run");request.addProperty("script",script);request.addProperty("function",function);
        request.add("event",event.deepCopy());request.add("state",state.deepCopy());request.add("params",params.deepCopy());
        JsonObject response=request(request);
        if(!response.has("state") || !response.get("state").isJsonObject() || !response.has("actions") || !response.get("actions").isJsonArray())throw new IllegalStateException("Invalid script output");
        JsonObject next=response.getAsJsonObject("state");JsonArray actions=response.getAsJsonArray("actions");
        ExperienceSpec.bounded(next,32768);validateActions(actions);
        return new Result(next.deepCopy(),actions.deepCopy());
    }
    public void validate(ExperienceSpec spec) {
        JsonObject request=new JsonObject();request.addProperty("op","validate");request.addProperty("script",spec.script());
        JsonArray functions=new JsonArray();Set<String> names=new LinkedHashSet<>();
        if(!spec.scriptEvents().isEmpty())names.add("onEvent");for(var r:spec.rules())names.add(r.function());
        names.forEach(functions::add);request.add("functions",functions);request(request);
    }
    private JsonObject request(JsonObject request) {
        ExperienceSpec.bounded(request,ScriptWorker.MAX_LINE);
        if(!lock.tryLock())throw new IllegalStateException("Script worker busy");
        try {
            if(closed)throw new IllegalStateException("Script worker closed");
            start();
            BufferedWriter childInput=input;Reader childOutput=output;
            Future<String> pending=io.submit(()->{
                childInput.write(request.toString());childInput.newLine();childInput.flush();
                return ScriptWorker.readLine(childOutput);
            });
            String line;
            try { line=pending.get(3,TimeUnit.SECONDS); }
            catch(InterruptedException ex) { pending.cancel(true);stop();Thread.currentThread().interrupt();throw new IllegalStateException("Script interrupted"); }
            catch(ExecutionException | TimeoutException ex) { pending.cancel(true);stop();throw new IllegalStateException("Script worker failed or timed out"); }
            if(line==null) {stop();throw new IllegalStateException("Script worker stopped");}
            JsonObject response;
            try { response=JsonParser.parseString(line).getAsJsonObject(); }
            catch(RuntimeException ex) {stop();throw new IllegalStateException("Invalid worker protocol");}
            if(!response.has("ok") || !response.get("ok").getAsBoolean())throw new IllegalStateException("Script rejected or budget exceeded");
            return response;
        } finally {lock.unlock();}
    }
    private synchronized void start() {
        if(closed)throw new IllegalStateException("Script worker closed");
        if(child!=null && child.isAlive())return;
        try {
            // Code sources are trusted classes, not script data. Works with both the shaded
            // plugin jar and separate test class/dependency directories.
            Set<String> locations=new LinkedHashSet<>();
            for(Class<?> type:List.of(ScriptWorker.class,Context.class,Gson.class))
                locations.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().toString());
            Path java=Path.of(System.getProperty("java.home"),"bin","java").toAbsolutePath();
            ProcessBuilder builder=new ProcessBuilder(java.toString(),"-Xms16m","-Xmx128m","-Xss512k","-XX:MaxMetaspaceSize=64m",
                "-XX:+ExitOnOutOfMemoryError","-Dfile.encoding=UTF-8","-cp",String.join(File.pathSeparator,locations),ScriptWorker.class.getName());
            builder.environment().clear();builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            child=builder.start();input=new BufferedWriter(new OutputStreamWriter(child.getOutputStream(),StandardCharsets.UTF_8));
            output=new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8);
        } catch(Exception ex) {stop();throw new IllegalStateException("Cannot start bounded script worker",ex);}
    }
    private synchronized void stop() {
        Process p=child;child=null;
        if(p!=null) {
            retiringChild=p;p.destroyForcibly();
            try {p.waitFor(1,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
        }
    }
    public boolean awaitStopped(long timeoutMillis) throws InterruptedException {return io.awaitTermination(timeoutMillis,TimeUnit.MILLISECONDS) && (child==null || !child.isAlive()) && (retiringChild==null || !retiringChild.isAlive());}
    @Override public void close() {closed=true;stop();io.shutdownNow();}

    static void validateActions(JsonArray actions) {
        if(actions.size()>16)throw ExperienceSpec.invalid("Too many emissions");
        for(JsonElement value:actions) {
            if(!value.isJsonObject())throw ExperienceSpec.invalid("Emission must be object");
            JsonObject action=value.getAsJsonObject();ExperienceSpec.bounded(action,4096);ExperienceSpec.keys(action,Set.of("type","args"));
            String type=ExperienceSpec.text(action,"type",32,true);
            if(!ACTIONS.contains(type) || !action.has("args") || !action.get("args").isJsonObject())throw ExperienceSpec.invalid("Unknown emission");
            JsonObject args=action.getAsJsonObject("args");
            // Validate the entire emission batch before returning. Bukkit names and live
            // authorization remain runtime checks; no player/broadcast targeting is accepted.
            switch(type) {
                case "chat" -> {
                    ExperienceSpec.keys(args,Set.of("message"));ExperienceSpec.text(args,"message",1200,true);
                }
                case "title" -> {
                    ExperienceSpec.keys(args,Set.of("title","subtitle"));
                    ExperienceSpec.text(args,"title",80,true);ExperienceSpec.text(args,"subtitle",160,false);
                }
                case "sound" -> {
                    ExperienceSpec.keys(args,Set.of("sound","volume","pitch"));
                    if(!Set.of("BLOCK_NOTE_BLOCK_CHIME","ENTITY_EXPERIENCE_ORB_PICKUP","BLOCK_AMETHYST_BLOCK_CHIME")
                        .contains(ExperienceSpec.text(args,"sound",80,true)))throw ExperienceSpec.invalid("Unsupported sound");
                    range(args,"volume",0,1);range(args,"pitch",0.5,2);
                }
                case "particle" -> {
                    ExperienceSpec.keys(args,Set.of("particle","count"));
                    if(!Set.of("HAPPY_VILLAGER","END_ROD","COMPOSTER").contains(ExperienceSpec.text(args,"particle",80,true)))
                        throw ExperienceSpec.invalid("Unsupported particle");
                    ExperienceSpec.integer(args,"count",1,1,32);
                }
                case "give_item" -> {
                    ExperienceSpec.keys(args,Set.of("material","amount"));
                    String material=ExperienceSpec.text(args,"material",71,true);
                    if(!material.matches("[A-Z][A-Z0-9_]{0,70}"))throw ExperienceSpec.invalid("Invalid material");
                    ExperienceSpec.integer(args,"amount",-1,1,16);
                }
                case "build" -> {
                    ExperienceSpec.keys(args,Set.of("world","x","y","z","width","height","depth","replace_existing","operations"));
                    ExperienceSpec.text(args,"world",128,true);
                    JsonArray operations=ExperienceSpec.array(args,"operations");
                    if(operations.isEmpty() || operations.size()>16)throw ExperienceSpec.invalid("Build emission requires 1..16 blocks");
                    for(var op:operations) {
                        if(!op.isJsonObject())throw ExperienceSpec.invalid("Block operation must be object");
                        ExperienceSpec.keys(op.getAsJsonObject(),Set.of("x","y","z","material"));
                    }
                    BuildSpec.parse(args);
                }
                case "world_effect" -> {
                    ExperienceSpec.keys(args,Set.of("world","action","value","duration_seconds"));WorldEffectSpec.parse(args);
                }
                case "companion" -> {
                    String command=ExperienceSpec.text(args,"action",32,true);
                    if(!Set.of("summon","follow","stay","wave","status","move","dismiss").contains(command))throw ExperienceSpec.invalid("Unsupported companion action");
                    ExperienceSpec.keys(args,command.equals("move")?Set.of("action","x","y","z"):Set.of("action"));
                    if(command.equals("move")) {
                        int x=BuildSpec.integer(args,"x"),y=BuildSpec.integer(args,"y"),z=BuildSpec.integer(args,"z");
                        if(Math.abs((long)x)>30000000 || Math.abs((long)z)>30000000 || Math.abs((long)y)>2048)throw ExperienceSpec.invalid("Move out of bounds");
                    }
                }
                default -> throw ExperienceSpec.invalid("Unknown emission");
            }
        }
    }
    private static void range(JsonObject args,String key,double min,double max) {
        if(!args.has(key))return;
        double value=ExperienceSpec.number(args,key);
        if(value<min || value>max)throw ExperienceSpec.invalid("Invalid "+key);
    }
}
