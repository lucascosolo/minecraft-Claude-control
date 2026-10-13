package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Managed game callbacks. No event callback invokes the provider or accepts authority from JSON. */
public final class ExperienceManager implements Listener,AutoCloseable {
    private final ClaudeControlPlugin plugin;
    private final MemoryStore store;
    private final ScriptRunner scripts=new ScriptRunner();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"ClaudeControl-experiences");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,Entry> active=new ConcurrentHashMap<>();
    private final Set<Entry> dirtyMembership=ConcurrentHashMap.newKeySet();
    private final AtomicLong epochs=new AtomicLong();
    private volatile boolean closed;
    private volatile String outcome="Starting";
    private BukkitTask timer;
    private int cursor;
    private final Map<UUID,Long> movementTimes=new HashMap<>();
    private static final Set<String> SOUNDS=Set.of("BLOCK_NOTE_BLOCK_CHIME","ENTITY_EXPERIENCE_ORB_PICKUP","BLOCK_AMETHYST_BLOCK_CHIME");
    private static final Set<String> PARTICLES=Set.of("HAPPY_VILLAGER","END_ROD","COMPOSTER");
    private static final class Entry {
        final ExperienceSpec spec;final JsonObject definition;
        final Map<UUID,Long> participants=new ConcurrentHashMap<>();
        volatile long generation;volatile boolean enabled;
        JsonObject state;
        Entry(ExperienceSpec spec,JsonObject definition,JsonObject state,long generation,boolean enabled){this.spec=spec;this.definition=definition.deepCopy();this.state=state;this.generation=generation;this.enabled=enabled;}
    }
    public ExperienceManager(ClaudeControlPlugin plugin) throws Exception {
        this.plugin=plugin;store=new MemoryStore(plugin.getDataFolder().toPath().resolve("experiences"));
    }
    public void start() {
        Bukkit.getPluginManager().registerEvents(this,plugin);
        offer(()->{
            try {
                if(!store.list().contains("shared_sparks.definition.json")) {
                    try(var input=plugin.getResource("experience-example.json")){
                        if(input==null)throw new IllegalStateException("Example missing");
                        JsonObject definition=JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                        ExperienceSpec spec=ExperienceSpec.parse(definition);scripts.validate(spec);store.write(spec.id()+".definition.json",definition.toString());
                    }
                }
                for(String name:store.list())if(name.endsWith(".definition.json")){
                    try{load(name.substring(0,name.length()-16),null);}catch(Exception failure){outcome="An experience was refused during startup";}
                }
                outcome="Managed experiences ready; players must join";
            }catch(Exception failure){outcome="Experience initialization refused";}
        });
        timer=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20,20);
    }
    private boolean offer(Runnable task) {if(closed)return false;try{worker.execute(task);return true;}catch(RejectedExecutionException full){return false;}}
    private static String id(String id){if(id==null || !id.matches("[a-z][a-z0-9_-]{0,31}"))throw new IllegalArgumentException("Invalid experience id");return id;}
    private void load(String identifier,Turn admin) throws Exception {
        id(identifier);JsonObject definition=JsonParser.parseString(store.read(identifier+".definition.json")).getAsJsonObject();
        ExperienceSpec spec=ExperienceSpec.parse(definition);if(!spec.id().equals(identifier))throw new IllegalArgumentException("ID mismatch");scripts.validate(spec);
        Entry previous=active.get(identifier);if(previous==null && active.size()>=16)throw new IllegalStateException("Experience limit");
        JsonObject record=previous==null?readRecord(identifier):record(previous);
        JsonObject state=record.has("state")?record.getAsJsonObject("state").deepCopy():new JsonObject();ExperienceSpec.bounded(state,32768);
        Entry next=new Entry(spec,definition,state,epochs.incrementAndGet(),!record.has("enabled") || ExperienceSpec.bool(record,"enabled",true));
        if(previous==null && record.has("participants"))for(JsonElement value:record.getAsJsonArray("participants")){
            if(next.participants.size()>=32)throw new IllegalArgumentException("Participant limit");next.participants.put(UUID.fromString(value.getAsString()),epochs.incrementAndGet());
        }
        plugin.onServerThread(()->{if(admin!=null)requireOwner(admin);if(closed)throw new IllegalStateException("Stopped");if(previous!=null){next.participants.putAll(previous.participants);next.enabled=previous.enabled;previous.generation=epochs.incrementAndGet();}active.put(identifier,next);return null;});
    }
    private JsonObject readRecord(String identifier) throws Exception {String text=store.read(identifier+".state.json");if(text.isBlank())return new JsonObject();JsonObject record=JsonParser.parseString(text).getAsJsonObject();ExperienceSpec.bounded(record,32768);return record;}
    private JsonObject record(Entry entry) {JsonObject record=new JsonObject();record.addProperty("enabled",entry.enabled);record.add("state",entry.state.deepCopy());JsonArray members=new JsonArray();entry.participants.keySet().stream().sorted().forEach(uuid->members.add(uuid.toString()));record.add("participants",members);ExperienceSpec.bounded(record,32768);return record;}
    private void persist(Entry entry) throws Exception {store.write(entry.spec.id()+".state.json",record(entry).toString());}
    private void requireOwner(Turn turn){if(!plugin.currentOwner(turn))throw new IllegalStateException("Current owner required");}
    public ToolReply tool(JsonObject args,Turn turn) throws Exception {
        // API worker waits on this same serial queue; storage and script validation never run on ticks.
        CompletableFuture<ToolReply> result=new CompletableFuture<>();
        if(!offer(()->{if(result.isCancelled())return;try{result.complete(admin(args,turn));}catch(Exception failure){result.complete(new ToolReply("Experience operation refused; prior active definition retained",true));}}))return new ToolReply("Experience queue full",true);
        try{return result.get(20,TimeUnit.SECONDS);}catch(TimeoutException | InterruptedException failure){result.cancel(false);throw failure;}
    }
    private ToolReply admin(JsonObject args,Turn turn) throws Exception {
        String action=ExperienceSpec.text(args,"action",16,true);
        if(turn.event() && !Set.of("list","read").contains(action))throw new IllegalStateException("Automatic API events cannot administer experiences");
        plugin.onServerThread(()->{plugin.authorizedActor(turn);if(!Set.of("list","read").contains(action))requireOwner(turn);return null;});
        if(action.equals("list"))return new ToolReply(list(),false);
        String identifier=id(ExperienceSpec.text(args,"id",32,true));
        if(action.equals("read")){Entry e=active.get(identifier);return new ToolReply(e==null?"Experience unavailable":e.definition.toString(),e==null);}
        if(action.equals("save")){
            JsonObject definition=args.getAsJsonObject("definition");ExperienceSpec spec=ExperienceSpec.parse(definition);if(!spec.id().equals(identifier))throw new IllegalArgumentException("ID mismatch");scripts.validate(spec);
            plugin.onServerThread(()->{requireOwner(turn);return null;});store.write(identifier+".definition.json",definition.toString());return new ToolReply("Validated definition staged; reload to activate",false);
        }
        if(action.equals("reload")){load(identifier,turn);return new ToolReply("Experience reloaded; participant state retained",false);}
        Entry e=active.get(identifier);if(e==null)throw new IllegalArgumentException("Unknown experience");
        if(!Set.of("enable","disable","reset").contains(action))throw new IllegalArgumentException("Unknown action");
        plugin.onServerThread(()->{requireOwner(turn);e.generation=epochs.incrementAndGet();if(action.equals("reset"))e.state=new JsonObject();else e.enabled=action.equals("enable");return null;});persist(e);
        return new ToolReply("Experience "+action+" complete",false);
    }
    private String list(){return active.values().stream().sorted(Comparator.comparing(e->e.spec.id())).map(e->e.spec.id()+": "+e.spec.title()+" ("+(e.enabled?"enabled":"disabled")+", "+e.participants.size()+" joined)").reduce((a,b)->a+"\n"+b).orElse("No managed experiences");}
    public boolean command(CommandSender sender,String[] args) {
        if(!(sender instanceof Player player) || !player.hasPermission("claudecontrol.ask")){sender.sendMessage("Use addons from an authenticated permitted player account.");return true;}
        try {
            String action=args.length>1?args[1].toLowerCase(Locale.ROOT):"list";
            if(action.equals("list")){player.sendMessage(list());return true;}
            if(action.equals("status")){player.sendMessage(outcome+"; queued "+worker.getQueue().size());return true;}
            String identifier=id(args.length>2?args[2]:null);Entry e=active.get(identifier);
            if(Set.of("join","leave","trigger").contains(action)){
                if(e==null)throw new IllegalArgumentException("Unknown experience");
                if(action.equals("leave")){e.participants.remove(player.getUniqueId());membershipChanged(e);player.sendMessage("Left "+identifier+"; queued effects blocked.");return true;}
                if(action.equals("join")){
                    if(!e.enabled)throw new IllegalArgumentException("Experience disabled");if(!e.participants.containsKey(player.getUniqueId()) && e.participants.size()>=32)throw new IllegalArgumentException("Participant limit");
                    e.participants.putIfAbsent(player.getUniqueId(),epochs.incrementAndGet());
                    membershipChanged(e);event(player,"join",new JsonObject(),identifier);player.sendMessage("Joined "+e.spec.title()+". "+e.spec.description());return true;
                }
                if(!e.participants.containsKey(player.getUniqueId()))throw new IllegalArgumentException("Join first");event(player,"manual",new JsonObject(),identifier);player.sendMessage("Experience trigger queued.");return true;
            }
            Turn turn=new Turn(player.getUniqueId(),player.getName(),plugin.settings().isOwner(player.getUniqueId(),player.getName(),player.isOp()),"Explicit managed experience administration",false);
            requireOwner(turn);if(!Set.of("reload","enable","disable","reset").contains(action))throw new IllegalArgumentException("Invalid action");
            // Invalidate disable/reset immediately, before their storage operation is queued.
            if(e!=null && Set.of("disable","reset").contains(action)){e.generation=epochs.incrementAndGet();if(action.equals("disable")){e.enabled=false;membershipChanged(e);}}
            JsonObject input=new JsonObject();input.addProperty("action",action);input.addProperty("id",identifier);
            if(!offer(()->{try{ToolReply reply=admin(input,turn);tell(turn,reply.text());}catch(Exception failure){tell(turn,"Experience change refused; previous definition retained.");}}))player.sendMessage("Experience queue full; retry shortly.");
        }catch(Exception failure){player.sendMessage("Usage: /claude addons list|status|join|leave|trigger|reload|enable|disable|reset <id>. Join first; administration requires a current owner.");}
        return true;
    }
    private void tell(Turn turn,String text){if(!closed)Bukkit.getScheduler().runTask(plugin,()->{Player p=Bukkit.getPlayer(turn.actorId());if(p!=null)p.sendMessage(text);});}
    private void membershipChanged(Entry entry){dirtyMembership.add(entry);if(offer(()->{if(!dirtyMembership.remove(entry))return;try{Entry current=active.get(entry.spec.id());if(current!=null)persist(current);}catch(Exception failure){dirtyMembership.add(entry);outcome="Membership persistence failed";}}))return;outcome="Membership save queued for retry";}
    private boolean valid(Entry entry,long generation,UUID uuid,long membership){return !closed && !plugin.isPaused() && active.get(entry.spec.id())==entry && entry.enabled && entry.generation==generation && Objects.equals(entry.participants.get(uuid),membership);}
    private void event(Player player,String type,JsonObject fields,String only) {event(player,type,fields,only,player.getLocation());}
    private void event(Player player,String type,JsonObject fields,String only,Location pos) {
        if(closed || plugin.isPaused() || !player.hasPermission("claudecontrol.ask"))return;
        JsonObject event=fields.deepCopy();event.addProperty("type",type);event.addProperty("player_uuid",player.getUniqueId().toString());event.addProperty("player_name",player.getName());event.addProperty("world",pos.getWorld().getName());
        JsonObject position=new JsonObject();position.addProperty("x",pos.getX());position.addProperty("y",pos.getY());position.addProperty("z",pos.getZ());event.add("position",position);
        Turn turn=new Turn(player.getUniqueId(),player.getName(),plugin.settings().isOwner(player.getUniqueId(),player.getName(),player.isOp()),"Managed experience callback",false);
        for(Entry entry:active.values()){
            if(only!=null && !entry.spec.id().equals(only))continue;
            Long membership=entry.participants.get(turn.actorId());if(!entry.enabled || membership==null)continue;
            if(!entry.spec.scriptEvents().contains(type) && entry.spec.rules().stream().noneMatch(rule->rule.event().equals(type)))continue;
            long generation=entry.generation;
            if(Set.of("timer","enter_area").contains(type) && worker.getQueue().size()>=16)continue;
            offer(()->process(entry,generation,membership,turn,event));
        }
    }
    private void process(Entry entry,long generation,long membership,Turn turn,JsonObject event) {
        if(!valid(entry,generation,turn.actorId(),membership))return;
        try {
            plugin.onServerThread(()->{if(!valid(entry,generation,turn.actorId(),membership))throw new IllegalStateException("Stale callback");plugin.authorizedActor(turn);return null;});
            ExperienceRules.Result rules=ExperienceRules.evaluate(entry.spec,event,entry.state,System.currentTimeMillis());JsonObject next=rules.state();JsonElement progress=next.get("_rules").deepCopy();next.remove("_rules");JsonArray actions=new JsonArray();
            List<ExperienceRules.Call> calls=new ArrayList<>(rules.calls());if(entry.spec.scriptEvents().contains(event.get("type").getAsString()))calls.add(new ExperienceRules.Call("onEvent",new JsonObject()));
            for(var call:calls){ScriptRunner.Result result=scripts.run(entry.spec.script(),call.function(),event,next,call.params());next=result.state();next.remove("_rules");for(var action:result.actions())actions.add(action);if(actions.size()>16)throw new IllegalArgumentException("Event emission budget exceeded");}
            next.add("_rules",progress);ExperienceSpec.bounded(next,32768);ScriptRunner.validateActions(actions);for(var action:actions)validate(action.getAsJsonObject());
            plugin.onServerThread(()->{
                if(!valid(entry,generation,turn.actorId(),membership))throw new IllegalStateException("Stale callback");plugin.authorizedActor(turn);
                List<BuildSpec> plans=new ArrayList<>();
                int companionMutations=0;
                for(JsonElement element:actions){JsonObject action=element.getAsJsonObject();String type=action.get("type").getAsString();JsonObject args=action.getAsJsonObject("args");
                    if(type.equals("companion") && !args.get("action").getAsString().equals("status") && ++companionMutations>1)throw new IllegalArgumentException("Only one companion mutation per callback");
                    if(Set.of("give_item","build","world_effect","companion").contains(type))plugin.gameTools().preflightExperienceAction(type,args,turn);
                    if(type.equals("build")){BuildSpec plan=BuildSpec.parse(args);for(BuildSpec other:plans)if(plan.world().equals(other.world()) && (long)plan.x()< (long)other.x()+other.width() && (long)other.x()< (long)plan.x()+plan.width() && (long)plan.y()< (long)other.y()+other.height() && (long)other.y()< (long)plan.y()+plan.height() && (long)plan.z()< (long)other.z()+other.depth() && (long)other.z()< (long)plan.z()+plan.depth())throw new IllegalArgumentException("Overlapping build emissions");plans.add(plan);}
                }return null;
            });
            if(!valid(entry,generation,turn.actorId(),membership))return;
            // Commit progress before dispatch: a partial effect failure is never blindly replayed.
            JsonObject old=entry.state;entry.state=next;try{if(!next.equals(old))persist(entry);}catch(Exception failure){entry.state=old;throw failure;}
            for(var action:actions){if(!valid(entry,generation,turn.actorId(),membership))break;emit(entry,generation,membership,turn,action.getAsJsonObject());}
            outcome="Last experience callback completed";
        }catch(Exception failure){outcome="Experience callback refused; inspect partial effects before retrying";}
    }
    private void validate(JsonObject action) {
        String type=action.get("type").getAsString();JsonObject args=action.getAsJsonObject("args");
        switch(type){
            case "chat" -> {ExperienceSpec.keys(args,Set.of("message"));ExperienceSpec.text(args,"message",1200,true);}
            case "title" -> {ExperienceSpec.keys(args,Set.of("title","subtitle"));ExperienceSpec.text(args,"title",80,true);ExperienceSpec.text(args,"subtitle",160,false);}
            case "sound" -> {ExperienceSpec.keys(args,Set.of("sound","volume","pitch"));if(!SOUNDS.contains(ExperienceSpec.text(args,"sound",80,true)))throw new IllegalArgumentException("Invalid sound");number(args,"volume",1,0,1);number(args,"pitch",1,.5,2);}
            case "particle" -> {ExperienceSpec.keys(args,Set.of("particle","count"));if(!PARTICLES.contains(ExperienceSpec.text(args,"particle",80,true)))throw new IllegalArgumentException("Invalid particle");ExperienceSpec.integer(args,"count",8,1,32);}
            default -> GameTools.validateExperienceTool(type,args);
        }
    }
    private static double number(JsonObject args,String key,double fallback,double min,double max){double n=args.has(key)?ExperienceSpec.number(args,key):fallback;if(n<min || n>max)throw new IllegalArgumentException("Invalid "+key);return n;}
    private void emit(Entry entry,long generation,long membership,Turn turn,JsonObject action) throws Exception {
        String type=action.get("type").getAsString();JsonObject args=action.getAsJsonObject("args");
        if(Set.of("give_item","build","world_effect","companion").contains(type)){
            ToolReply result=plugin.gameTools().experienceAction(type,args,turn,()->valid(entry,generation,turn.actorId(),membership));if(result.error())throw new IllegalStateException("Effect refused");return;
        }
        plugin.onServerThread(()->{
            if(!valid(entry,generation,turn.actorId(),membership))throw new IllegalStateException("Stale callback");Player player=plugin.authorizedActor(turn);
            switch(type){
                case "chat" -> player.sendMessage(plugin.settings().chatPrefix()+args.get("message").getAsString());
                case "title" -> player.sendTitle(args.get("title").getAsString(),ExperienceSpec.text(args,"subtitle",160,false),10,50,10);
                case "sound" -> player.playSound(player.getLocation(),Sound.valueOf(args.get("sound").getAsString()),(float)number(args,"volume",1,0,1),(float)number(args,"pitch",1,.5,2));
                case "particle" -> player.spawnParticle(Particle.valueOf(args.get("particle").getAsString()),player.getLocation().add(0,1,0),ExperienceSpec.integer(args,"count",8,1,32),.5,.5,.5,0);
                default -> throw new IllegalArgumentException("Unknown effect");
            }return null;
        });
    }
    private void tick(){if(closed)return;for(Entry e:dirtyMembership)if(dirtyMembership.remove(e)){if(!offer(()->{try{Entry current=active.get(e.spec.id());if(current!=null)persist(current);}catch(Exception failure){dirtyMembership.add(e);outcome="Membership persistence failed";}}))dirtyMembership.add(e);}if(plugin.isPaused())return;var players=Bukkit.getOnlinePlayers().stream().filter(p->active.values().stream().anyMatch(e->e.enabled && e.participants.containsKey(p.getUniqueId()))).toList();if(players.isEmpty())return;for(int i=0;i<Math.min(4,players.size());i++){Player p=players.get(Math.floorMod(cursor++,players.size()));event(p,"timer",new JsonObject(),null);event(p,"enter_area",new JsonObject(),null);}}
    @EventHandler(ignoreCancelled=true) public void move(PlayerMoveEvent e){Location to=e.getTo();if(to==null || (e.getFrom().getBlockX()==to.getBlockX() && e.getFrom().getBlockY()==to.getBlockY() && e.getFrom().getBlockZ()==to.getBlockZ() && e.getFrom().getWorld().equals(to.getWorld())))return;if(active.values().stream().noneMatch(entry->entry.enabled && entry.participants.containsKey(e.getPlayer().getUniqueId())))return;long now=System.currentTimeMillis();long last=movementTimes.getOrDefault(e.getPlayer().getUniqueId(),0L);if(now-last<500)return;movementTimes.put(e.getPlayer().getUniqueId(),now);event(e.getPlayer(),"enter_area",new JsonObject(),null,to);}
    @EventHandler public void quit(PlayerQuitEvent e){movementTimes.remove(e.getPlayer().getUniqueId());}
    @EventHandler(ignoreCancelled=true) public void chat(AsyncPlayerChatEvent e){UUID uuid=e.getPlayer().getUniqueId();String message=e.getMessage();String bounded=message.substring(0,Math.min(256,message.length())).replaceAll("[\\p{Cntrl}]","");Bukkit.getScheduler().runTask(plugin,()->{Player p=Bukkit.getPlayer(uuid);if(p!=null){JsonObject fields=new JsonObject();fields.addProperty("message",bounded);event(p,"chat",fields,null);}});}
    @EventHandler public void join(PlayerJoinEvent e){event(e.getPlayer(),"join",new JsonObject(),null);}
    @EventHandler(ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){JsonObject fields=new JsonObject();fields.addProperty("material",e.getBlock().getType().name());event(e.getPlayer(),"block_break",fields,null);}
    @EventHandler(ignoreCancelled=true) public void place(BlockPlaceEvent e){JsonObject fields=new JsonObject();fields.addProperty("material",e.getBlock().getType().name());event(e.getPlayer(),"block_place",fields,null);}
    @EventHandler(ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(e.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND || e.getClickedBlock()==null || e.getAction()!=org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)return;JsonObject fields=new JsonObject();fields.addProperty("material",e.getClickedBlock().getType().name());fields.addProperty("interaction",e.getAction().name());event(e.getPlayer(),"interact",fields,null);}
    @EventHandler public void death(PlayerDeathEvent e){event(e.getEntity(),"death",new JsonObject(),null);}
    @EventHandler public void respawn(PlayerRespawnEvent e){Bukkit.getScheduler().runTask(plugin,()->event(e.getPlayer(),"respawn",new JsonObject(),null));}
    @Override public void close(){closed=true;for(Entry e:active.values())e.generation=epochs.incrementAndGet();if(timer!=null)timer.cancel();HandlerList.unregisterAll(this);scripts.close();worker.shutdownNow();}
}
