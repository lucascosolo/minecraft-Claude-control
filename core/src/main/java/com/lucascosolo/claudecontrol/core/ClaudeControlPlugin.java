package com.lucascosolo.claudecontrol.core;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** API-only Paper companion. Network and durable storage run on one bounded worker. */
public final class ClaudeControlPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final Pattern ADDRESS = Pattern.compile("(?i)(?:^|\\s)(?:hey[ ,]+)?claude(?:\\b|[:,])");
    private volatile Settings settings;
    private volatile ApiAgent agent;
    private volatile boolean paused;
    private volatile boolean stopping;
    private volatile String lastOutcome="No API turn yet";
    private BlockingQueue<Runnable> queue;
    private ExecutorService worker;
    private MemoryStore memory;
    private GameTools tools;
    private Companion companion;
    private ExperienceManager experiences;
    public ExperienceManager experiences(){return experiences;}
    GameTools gameTools(){return tools;}
    private BukkitTask eventTimer;
    private final Map<UUID,ArrayDeque<Long>> rates=new HashMap<>();
    private final ArrayDeque<Long> serverRates=new ArrayDeque<>();
    private final Set<FutureTask<?>> pending=ConcurrentHashMap.newKeySet();
    public Settings settings() {return settings;}
    public Companion companion(){return companion;}
    public boolean isPaused(){return paused || stopping;}
    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            settings=readSettings();
            memory=new MemoryStore(getDataFolder().toPath().resolve("memory"));
            tools=new GameTools(this,memory,new CommandPolicy(settings.deniedCommands()));
            agent=createAgent(settings,readPrompt());
            queue=new ArrayBlockingQueue<>(settings.maxQueuedTriggers());
            worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ClaudeControl-api");t.setDaemon(true);return t;});
            worker.submit(()->{
                while(!stopping) try {queue.take().run();}
                catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                catch(Exception e){lastOutcome="Worker operation failed; consult configuration";getLogger().warning(lastOutcome);}
            });
            companion=new Companion(this);
            experiences=new ExperienceManager(this);
            experiences.start();
            queue.offer(()->{try{companion.persistIdentity();}catch(Exception e){lastOutcome="Companion identity persistence failed";}});
            Objects.requireNonNull(getCommand("claude")).setExecutor(this);
            Bukkit.getPluginManager().registerEvents(this,this);
            configureEvents();
            getLogger().info("ClaudeControl API core loaded; API " + (settings.apiKey().isBlank()?"inactive (no key)":"configured") + "; automatic events " + (settings.eventsEnabled()?"enabled":"off"));
        } catch(Exception e) {
            getLogger().severe("ClaudeControl configuration or storage initialization failed; check private config and writable plugin data directory. No API request was sent.");
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    private Settings readSettings() {
        Map<String,Object> values=new LinkedHashMap<>();
        for(String key:getConfig().getKeys(true)) {
            Object value=getConfig().get(key);
            if(!(value instanceof org.bukkit.configuration.ConfigurationSection)) values.put(key,value);
        }
        return Settings.from(values,System.getenv());
    }
    private String readPrompt() throws IOException {
        return resource("system-prompt.md") + "\n\n" + resource("core-rules.md");
    }
    private String resource(String name) throws IOException {
        try(InputStream in=getResource(name)) {
            if(in==null)throw new IOException("Missing bundled prompt");
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private ApiAgent createAgent(Settings next,String prompt) throws Exception {
        ApiAgent.Transport transport=ApiAgent.httpTransport(next);
        try{return new ApiAgent(next,prompt,GameTools.schemas(),transport,getDataFolder().toPath().resolve("conversation.json"));}
        catch(Exception failure){transport.close();throw failure;}
    }
    @EventHandler(ignoreCancelled=true) public void onChat(AsyncPlayerChatEvent event) {
        String message=event.getMessage();
        if(!ADDRESS.matcher(message).find())return;
        // Player identity and privileges are resolved only on the server thread.
        Player addressedPlayer=event.getPlayer();
        Bukkit.getScheduler().runTask(this,()->{Player player=Bukkit.getPlayer(addressedPlayer.getUniqueId());if(player!=null)submitTurn(player,message,false);});
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        String action=args.length==0?"status":args[0].toLowerCase(Locale.ROOT);
        if(action.equals("addons")) return experiences.command(sender,args);
        if(action.equals("companion")) {
            if(!(sender instanceof Player player) || !player.hasPermission("claudecontrol.ask")){sender.sendMessage("Control the companion from an authenticated permitted player account.");return true;}
            com.google.gson.JsonObject input=new com.google.gson.JsonObject();input.addProperty("action",args.length>1?args[1].toLowerCase(Locale.ROOT):"status");
            try {
                if(input.get("action").getAsString().equals("move")) {
                    if(args.length!=5)throw new IllegalArgumentException();
                    input.addProperty("x",Integer.parseInt(args[2]));input.addProperty("y",Integer.parseInt(args[3]));input.addProperty("z",Integer.parseInt(args[4]));
                }
                Turn turn=new Turn(player.getUniqueId(),player.getName(),settings.isOwner(player.getUniqueId(),player.getName(),player.isOp()),"Explicit companion control",false);
                if(Set.of("stay","status","dismiss").contains(input.get("action").getAsString())) {
                    sender.sendMessage(settings.chatPrefix()+companion.action(input,turn).text());return true;
                }
                if(!queue.offer(()->{
                    try{ToolReply result=tools.execute("companion",input,turn);tellPlayer(turn.actorId(),settings.chatPrefix()+result.text());}
                    catch(Exception e){tellPlayer(turn.actorId(),"Claude's companion action was refused; check loaded safe ground and permissions.");}
                }))sender.sendMessage("Claude's queue is full; retry shortly.");
            }catch(Exception e){sender.sendMessage("Usage: /claude companion summon|follow|stay|dismiss|wave|status|move <x> <y> <z>");}
            return true;
        }
        if(action.equals("ask")) {
            if(!(sender instanceof Player player)){sender.sendMessage("Ask Claude from an authenticated player account.");return true;}
            if(args.length<2){sender.sendMessage("Usage: /claude ask <message>");return true;}
            submitTurn(player,String.join(" ",Arrays.copyOfRange(args,1,args.length)),false);return true;
        }
        boolean owner=!(sender instanceof Player p) || settings.isOwner(p.getUniqueId(),p.getName(),p.isOp());
        if(!owner){sender.sendMessage("Claude administration requires an operator or configured owner. Use /claude ask <message>.");return true;}
        switch(action) {
            case "status" -> sender.sendMessage("Claude: " + (paused?"paused":settings.apiKey().isBlank()?"inactive; set api-key in private config.yml":"ready")
                + "; API model " + settings.model() + "; queue " + queue.size() + "; events " + (settings.eventsEnabled()?"on":"off") + "; " + lastOutcome
                + "; last-turn tokens in/out " + agent.lastUsage().inputTokens()+"/"+agent.lastUsage().outputTokens());
            case "pause" -> {paused=true;companion.stop("Paused by owner");sender.sendMessage("Claude paused. A turn already in progress may finish.");}
            case "resume" -> {paused=false;sender.sendMessage(settings.apiKey().isBlank()?"No API key configured. Add it to private config.yml, then /claude reload.":"Claude resumed.");}
            case "reload" -> reloadAgent(sender);
            case "reset" -> {
                if(!queue.offer(()->{try{requireCurrentAdmin(sender);agent.reset();tell(sender,"Conversation archived and reset. Durable memory kept.");}
                    catch(Exception e){tell(sender,"Conversation reset failed; existing state was preserved.");}}))sender.sendMessage("Claude queue is full; retry later.");
            }
            case "event" -> {
                Player actor=sender instanceof Player p?p:Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
                if(actor==null)sender.sendMessage("An online player is required.");else submitTurn(actor,eventText(),true);
            }
            default -> sender.sendMessage("Usage: /claude ask|status|pause|resume|reload|reset|event");
        }
        return true;
    }
    private void reloadAgent(CommandSender sender) {
        try {
            reloadConfig();Settings next=readSettings();String prompt=readPrompt();
            if(!queue.offer(()->{
                try {
                    requireCurrentAdmin(sender);
                    ApiAgent replacement=createAgent(next,prompt);
                    GameTools replacementTools=new GameTools(this,memory,new CommandPolicy(next.deniedCommands()));
                    ApiAgent previous=agent;
                    settings=next;agent=replacement;tools=replacementTools;
                    previous.close();
                    onServerThread(()->{companion.reload();configureEvents();return null;});
                    tell(sender,"Claude settings reloaded after the active turn. Queue capacity changes apply at server restart.");
                }catch(Exception e){tell(sender,"Reload failed; previous active settings retained where possible.");}
            }))sender.sendMessage("Claude queue is full; reload not queued.");
        }catch(Exception e){sender.sendMessage("Invalid Claude config; previous active settings retained. Check API-only settings and limits.");}
    }
    private void submitTurn(Player player,String text,boolean event) {
        if(!player.hasPermission("claudecontrol.ask")){if(!event)player.sendMessage("You do not have permission to ask Claude.");return;}
        if(stopping || paused){if(!event)player.sendMessage("Claude is paused.");return;}
        if(settings.apiKey().isBlank()){if(!event)player.sendMessage("Claude needs an API key in plugins/ClaudeControl/config.yml. An owner can then /claude reload.");return;}
        if(text.isBlank() || text.length()>4000){if(!event)player.sendMessage("Please use a message between 1 and 4000 characters.");return;}
        long now=System.currentTimeMillis();var times=rates.computeIfAbsent(player.getUniqueId(),id->new ArrayDeque<>());
        while(!times.isEmpty() && times.peekFirst()<now-60_000)times.removeFirst();
        while(!serverRates.isEmpty() && serverRates.peekFirst()<now-60_000)serverRates.removeFirst();
        if(serverRates.size()>=settings.maxTurnsPerMinute()){if(!event)player.sendMessage("Claude's server request limit is reached; try again in a minute.");return;}
        if(times.size()>=settings.maxTurnsPerMinute()){if(!event)player.sendMessage("Claude is taking a breather; try again in a minute.");return;}
        Turn turn=new Turn(player.getUniqueId(),player.getName(),settings.isOwner(player.getUniqueId(),player.getName(),player.isOp()),text,event);
        if(!queue.offer(()->process(turn))){if(!event)player.sendMessage("Claude's request queue is full; try shortly.");return;}
        times.addLast(now);serverRates.addLast(now);
    }
    private record RequestContext(Turn turn,String report) {}
    private void process(Turn turn) {
        if(stopping || paused)return;
        AtomicBoolean spoke=new AtomicBoolean();
        try {
            RequestContext context=onServerThread(()->{
                Player p=authorizedActor(turn);
                Turn current=new Turn(p.getUniqueId(),p.getName(),currentOwner(turn),turn.text(),turn.event());
                return new RequestContext(current,GameTools.snapshot(p));
            });
            String reply=agent.runTurn(context.turn(),context.report(),(name,args,identity)->{
                if(stopping || paused)return new ToolReply("Claude paused or stopped; action refused",true);
                ToolReply result=tools.execute(name,args,identity);
                if(name.equals("send_chat") && !result.error())spoke.set(true);return result;
            });
            if(!spoke.get() && reply!=null && !reply.isBlank())tellPlayer(turn.actorId(),settings.chatPrefix()+reply.substring(0,Math.min(1200,reply.length())));
            lastOutcome="Last turn completed";
        }catch(Exception e){
            String safe=e.getMessage()==null?"":e.getMessage();
            if(safe.contains("HTTP 401") || safe.contains("HTTP 403")){paused=true;lastOutcome="Authentication rejected; paused";}
            else lastOutcome="Turn failed; existing world actions may already have completed";
            if(!stopping)tellPlayer(turn.actorId(),"Claude: "+lastOutcome+". An owner can check /claude status.");
            getLogger().warning(lastOutcome);
        }
    }
    private void configureEvents() {
        if(eventTimer!=null){eventTimer.cancel();eventTimer=null;}
        if(!settings.eventsEnabled())return;
        long ticks=settings.eventIntervalMinutes()*60L*20L;
        eventTimer=Bukkit.getScheduler().runTaskTimer(this,()->{
            if(paused || Bukkit.getOnlinePlayers().isEmpty())return;
            Player actor=Bukkit.getOnlinePlayers().iterator().next();submitTurn(actor,eventText(),true);
        },ticks,ticks);
    }
    private String eventText(){return "Offer one small optional story hook or idea connected to our world. Only send_chat, observe_world and survey_area are allowed. Do not alter the world or invent events that occurred.";}
    private void tell(CommandSender sender,String text) {
        if(stopping)return;Bukkit.getScheduler().runTask(this,()->sender.sendMessage(text));
    }
    private void tellPlayer(UUID id,String text) {
        if(stopping)return;Bukkit.getScheduler().runTask(this,()->{Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage(text);});
    }
    private void requireCurrentAdmin(CommandSender sender) throws Exception {
        onServerThread(()->{
            if(sender instanceof Player p && (!p.isOnline() || !settings.isOwner(p.getUniqueId(),p.getName(),p.isOp())))
                throw new IllegalStateException("Administration is no longer authorized");
            return null;
        });
    }
    Player authorizedActor(Turn turn) {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Identity checks require the server thread");
        Player player=Bukkit.getPlayer(turn.actorId());
        if(player==null || !player.isOnline() || !player.getName().equals(turn.actorName())
            || !player.hasPermission("claudecontrol.ask")) throw new IllegalStateException("Actor is offline or no longer authorized");
        return player;
    }
    boolean currentOwner(Turn turn) {
        Player player=authorizedActor(turn);
        return turn.privileged() && settings.isOwner(player.getUniqueId(),player.getName(),player.isOp());
    }
    public <T> T onServerThread(Callable<T> action) throws Exception {
        if(stopping)throw new IllegalStateException("Plugin stopped");
        if(Bukkit.isPrimaryThread())return action.call();
        FutureTask<T> future=new FutureTask<>(()->{
            if(stopping)throw new IllegalStateException("Plugin stopped");return action.call();
        });
        pending.add(future);BukkitTask task=Bukkit.getScheduler().runTask(this,future);
        try{return future.get(10,TimeUnit.SECONDS);}
        catch(TimeoutException|InterruptedException e){future.cancel(false);task.cancel();throw e;}
        catch(ExecutionException e){if(e.getCause() instanceof Exception ex)throw ex;throw new IllegalStateException("Server action failed");}
        finally{pending.remove(future);}
    }
    @Override public void onDisable() {
        stopping=true;paused=true;
        if(eventTimer!=null)eventTimer.cancel();
        if(experiences!=null)experiences.close();
        if(companion!=null)companion.close();
        pending.forEach(future->future.cancel(false));pending.clear();
        Bukkit.getScheduler().cancelTasks(this);
        if(worker!=null)worker.shutdownNow();
        if(agent!=null)agent.close(); // Nonblocking transport shutdown; never wait for networking on the tick thread.
        if(queue!=null)queue.clear();
    }
}
