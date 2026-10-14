package com.lucascosolo.claudecontrol.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.entity.LookAnchor;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** One persistent visible companion; all live methods run on the server thread. */
public final class Companion {
    private final ClaudeRuntime plugin;
    private final NamespacedKey tag;
    private final MemoryStore registry;
    private final Object identityLock=new Object();
    private final CompanionWater water;
    private volatile UUID knownId;
    private volatile AppearanceSpec appearance=AppearanceSpec.defaults();
    private AppearanceSpec savedAppearance;
    private long lastExpression=System.currentTimeMillis(),lastCuriosity=System.currentTimeMillis();
    private UUID observedFriend;
    private Location observedFriendPosition;
    private record BodyPose(UUID bodyId,UUID worldId,String world,double x,double y,double z) {
        JsonObject json() {
            JsonObject p=new JsonObject();p.addProperty("body_uuid",bodyId.toString());
            if(worldId!=null)p.addProperty("world_uuid",worldId.toString());
            p.addProperty("world",world);p.addProperty("x",x);p.addProperty("y",y);p.addProperty("z",z);return p;
        }
    }
    private volatile BodyPose lastPose;
    private BodyPose savedPose;
    private long recallGeneration;
    private boolean recallPending;
    private long lastAutomaticRecall;
    private Mannequin body;
    private BukkitTask timer;
    private Turn controller;
    private boolean following;
    private boolean keepCompany=true;
    private int presenceTicks;
    private List<CompanionPlan.WalkCell> terrain=List.of();
    private int terrainStep;
    private boolean jumping;
    private long wanderUntil;
    private long listeningUntil;
    private long designUntil;
    private long lastCatchUp;
    private Location jumpStart;
    private List<CompanionPlan.Cell> route=List.of();
    private int step,level,ticks,stalled;
    private long deadline;
    private double lastDistance=Double.MAX_VALUE;
    private BuildJob job;
    private String outcome="Not summoned";
    private static final class BuildJob {
        final UUID id=UUID.randomUUID();final BuildSpec spec;final Map<BuildSpec.BlockPlacement,Material> materials;
        final CompletableFuture<ToolReply> result=new CompletableFuture<>();
        int placed,index;final List<BuildSpec.BlockPlacement> operations;
        BuildJob(BuildSpec spec,Map<BuildSpec.BlockPlacement,Material> materials){this.spec=spec;this.materials=materials;operations=List.copyOf(materials.keySet());}
    }
    public Companion(ClaudeRuntime plugin) throws Exception {
        this.plugin=plugin;tag=new NamespacedKey(plugin.host(),"claude_companion");
        registry=new MemoryStore(plugin.getDataFolder().toPath().resolve("companion"));
        String cosmetics=registry.read("appearance.json").trim();
        if(!cosmetics.isEmpty()){appearance=AppearanceSpec.parse(JsonParser.parseString(cosmetics).getAsJsonObject(),appearance);savedAppearance=appearance;}
        String saved=registry.read("body-uuid.txt").trim();if(!saved.isEmpty())knownId=UUID.fromString(saved);
        String pose=registry.read("body-location.json").trim();
        if(!pose.isEmpty()) {
            JsonObject p=JsonParser.parseString(pose).getAsJsonObject();
            UUID recordedWorld=p.has("world_uuid") && !p.get("world_uuid").isJsonNull()?UUID.fromString(p.get("world_uuid").getAsString()):null;
            BodyPose parsed=new BodyPose(UUID.fromString(p.get("body_uuid").getAsString()),recordedWorld,
                p.get("world").getAsString(),p.get("x").getAsDouble(),p.get("y").getAsDouble(),p.get("z").getAsDouble());
            if(!Double.isFinite(parsed.x) || !Double.isFinite(parsed.y) || !Double.isFinite(parsed.z)
                || Math.abs(parsed.x)>29_999_984 || Math.abs(parsed.z)>29_999_984)throw new IllegalArgumentException("Invalid companion position record");
            if(parsed.bodyId.equals(knownId)) {
                lastPose=parsed;savedPose=parsed;
                if(recordedWorld==null) {
                    World loaded=Bukkit.getWorld(parsed.world);
                    if(loaded!=null)lastPose=new BodyPose(parsed.bodyId,loaded.getUID(),parsed.world,parsed.x,parsed.y,parsed.z);
                }
            }
        }
        water=new CompanionWater(plugin,registry,identityLock);
        adopt();
        timer=Bukkit.getScheduler().runTaskTimer(plugin.host(),this::tick,1,1);
    }
    public void persistIdentity() throws Exception {
        synchronized(identityLock) {
            UUID id=knownId;if(id!=null && !registry.read("body-uuid.txt").trim().equals(id.toString()))registry.write("body-uuid.txt",id.toString());
            BodyPose pose=lastPose;
            if(pose!=null && pose.bodyId.equals(id) && !pose.equals(savedPose)) {
                registry.write("body-location.json",pose.json().toString());savedPose=pose;
            }
            water.persistIdentity();
            AppearanceSpec cosmetics=appearance;
            if(!cosmetics.equals(savedAppearance)){registry.write("appearance.json",cosmetics.json().toString());savedAppearance=cosmetics;}
        }
    }
    private void rememberPose() {
        if(body==null || knownId==null || !body.getUniqueId().equals(knownId))return;
        try {
            Location p=body.getLocation();lastPose=new BodyPose(knownId,p.getWorld().getUID(),p.getWorld().getName(),p.getX(),p.getY(),p.getZ());
        }catch(RuntimeException unavailable){ /* Retain the last immutable loaded position. */ }
    }
    private void adopt() {
        rememberPose();
        if(body!=null && body.isValid() && !body.isDead())return;
        body=null;
        for(World world:Bukkit.getWorlds()) for(Entity entity:world.getEntities()) {
            if(entity instanceof Mannequin candidate && candidate.getPersistentDataContainer().has(tag,PersistentDataType.BYTE)) {
                if(knownId==null || candidate.getUniqueId().equals(knownId)) {
                    body=candidate;knownId=candidate.getUniqueId();configure(candidate);rememberPose();outcome="Standing by";return;
                }
            }
        }
    }
    private void configure(Mannequin entity) {
        entity.customName(Component.text("Claude"));entity.setCustomNameVisible(true);
        entity.setDescription(Component.text("Your Minecraft companion"));entity.setInvulnerable(true);
        entity.setCanPickupItems(false);entity.setPersistent(true);entity.setRemoveWhenFarAway(false);
        entity.setAI(false);entity.setGravity(false);entity.setCollidable(false);entity.setImmovable(true);
        CompanionAppearance.apply(entity,appearance);
    }
    public ToolReply action(JsonObject input,Turn turn) {
        Player actor=plugin.authorizedActor(turn);String action=input.get("action").getAsString();
        if(action.equals("status"))return new ToolReply(status(),false);
        if(turn.event())return new ToolReply("Automatic events cannot control Claude's body",true);
        if(plugin.isPaused() && !Set.of("stay","dismiss").contains(action))return new ToolReply("Claude is paused; an owner must resume before controlling the body",true);
        if(action.equals("dismiss") && !plugin.currentOwner(turn))return new ToolReply("Only a currently authorized owner may dismiss Claude's body",true);
        if(job!=null && !Set.of("stay","dismiss").contains(action))return new ToolReply("Claude is finishing a physical building job; use status",true);
        if(job!=null && controller!=null && !controller.actorId().equals(turn.actorId()) && !plugin.currentOwner(turn))
            return new ToolReply("Only the initiating player or an owner can stop this building job",true);
        adopt();
        if(action.equals("recall"))return recall(turn);
        if((recallPending || body==null || !body.isValid()) && Set.of("stay","dismiss").contains(action) && plugin.currentOwner(turn)) {
            keepCompany=false;stop("Existing body recovery stopped by owner");
            if(action.equals("stay"))return new ToolReply("Claude will stay; pending recovery was canceled",false);
            if(body==null || !body.isValid())return new ToolReply("Pending recovery was canceled; the unloaded body remains registered and was not removed",true);
        }
        if(action.equals("summon")) {
            if(body!=null)return new ToolReply("Claude already has a loaded body: "+status(),false);
            // A saved or other tagged body may be unloaded. Refuse another body instead of loading chunks.
            if(knownId!=null || anyTaggedLoaded())return new ToolReply("Claude's persistent body is elsewhere or unloaded; visit its area rather than spawning another",true);
            Location spot=findSummon(actor);
            if(spot==null)return new ToolReply("No safe, clear, loaded standing spot beside the player",true);
            body=actor.getWorld().spawn(spot,Mannequin.class,entity->{
                entity.getPersistentDataContainer().set(tag,PersistentDataType.BYTE,(byte)1);configure(entity);
            });knownId=body.getUniqueId();rememberPose();keepCompany=true;outcome="Summoned; keeping you company";
            return new ToolReply("Claude appeared beside you. "+status(),false);
        }
        if(body==null || !body.isValid())return new ToolReply("Claude's body is not loaded; summon first or visit its existing area",true);
        if(!body.getWorld().equals(actor.getWorld()) || body.getLocation().distanceSquared(actor.getLocation())>32*32)
            return new ToolReply("Control Claude while nearby in the same world",true);
        switch(action) {
            case "appearance" -> {
                if(!plugin.currentOwner(turn))return new ToolReply("Only a currently authorized owner may change the companion's appearance",true);
                if(jumping || step<route.size() || terrainStep<terrain.size() || water.aboard(body))
                    return new ToolReply("Wait for the body to finish moving before changing its appearance",true);
                AppearanceSpec next=AppearanceSpec.parse(input,appearance);
                CompanionAppearance.apply(body,next);appearance=next;plugin.queueCompanionPersistence();
                return new ToolReply("Appearance applied to the existing body: "+next.preset()+" outfit; "+
                    (next.account().isEmpty()?"native default skin":"skin requested for "+next.account()+"; clients resolve the account, so unknown accounts may keep a default skin"),false);
            }
            case "dismiss" -> {
                stop("Body dismissed; completed physical placements remain");
                synchronized(identityLock) {
                    try {water.dismiss(body);registry.write("body-uuid.txt","");}
                    catch(Exception failure) {return new ToolReply("Could not preserve the body identity change; Claude's body was kept",true);}
                    body.remove();body=null;knownId=null;lastPose=null;savedPose=null;outcome="Dismissed";
                }
                return new ToolReply("Claude's body was dismissed. Memory and builds are preserved; summon again whenever you like",false);
            }
            case "stay" -> {keepCompany=false;finishJob("Stopped by request",true);halt("Staying here");}
            case "wave" -> {halt("Giving you my attention");listeningUntil=System.currentTimeMillis()+3000;look(actor.getEyeLocation());body.swingMainHand();outcome="Waved";}
            case "follow" -> {listeningUntil=0;keepCompany=true;halt("Keeping you company");controller=turn;following=true;deadline=Long.MAX_VALUE;}
            case "boat" -> {
                halt("Preparing owned boat travel");
                try {
                    if(!water.start(body))return new ToolReply("No available owned boat and clear loaded surface water beside Claude",true);
                }catch(Exception failed){return new ToolReply("Owned boat identity could not be preserved; no further boat travel started",true);}
                keepCompany=true;controller=turn;following=true;deadline=Long.MAX_VALUE;
            }
            case "wander" -> {
                int duration=burstDuration(input);
                listeningUntil=0;halt("Exploring nearby for "+duration+" seconds");keepCompany=true;
                controller=turn;following=true;deadline=Long.MAX_VALUE;wanderUntil=System.currentTimeMillis()+duration*1000L;
            }
            case "design" -> {
                int duration=burstDuration(input);
                halt("Designing experiences; focused for "+duration+" seconds");
                designUntil=System.currentTimeMillis()+duration*1000L;look(actor.getEyeLocation());
            }
            case "move" -> {
                int x=BuildSpec.integer(input,"x"),y=BuildSpec.integer(input,"y"),z=BuildSpec.integer(input,"z");
                int duration=burstDuration(input);
                listeningUntil=0;halt("Planning walk");controller=turn;deadline=System.currentTimeMillis()+duration*1000L;
                if(!planTerrain(x,y,z))return new ToolReply("No bounded safe loaded route; Claude stayed put",true);outcome="Walking and jumping where safe";
            }
            default -> {return new ToolReply("Unknown companion action",true);}
        }
        return new ToolReply(status(),false);
    }
    private ToolReply recall(Turn turn) {
        return recall(turn,false);
    }
    private ToolReply recall(Turn turn,boolean automatic) {
        Player actor=plugin.authorizedActor(turn);
        if(!plugin.currentOwner(turn))return new ToolReply("Only a currently authorized owner may recall the existing body",true);
        if(knownId==null)return new ToolReply("There is no registered body to recall; summon first",true);
        if(recallPending)return new ToolReply("Existing body recovery is already pending",false);
        if(body!=null && body.isValid()) {
            catchUp(actor);return new ToolReply(status(),false);
        }
        BodyPose pose=lastPose;
        if(pose==null || !pose.bodyId.equals(knownId))return new ToolReply("The existing body has no verified saved position; preserve its UUID and restore its position record first",true);
        World source=pose.worldId==null?Bukkit.getWorld(pose.world):Bukkit.getWorld(pose.worldId);
        if(source==null || !source.getName().equals(pose.world) || pose.y<source.getMinHeight() || pose.y>=source.getMaxHeight())
            return new ToolReply("Recorded body world or position is unavailable; no chunk was loaded",true);
        halt("Recalling the existing body from its recorded chunk");
        recallPending=true;
        long generation=recallGeneration;UUID expected=knownId;
        int chunkX=((int)Math.floor(pose.x))>>4,chunkZ=((int)Math.floor(pose.z))>>4;
        CompletableFuture<Chunk> loading;
        try {loading=source.getChunkAtAsync(chunkX,chunkZ,false).orTimeout(10,java.util.concurrent.TimeUnit.SECONDS);}
        catch(RuntimeException unavailable){recallPending=false;return new ToolReply("Recorded existing chunk could not be requested safely",true);}
        loading.whenComplete((chunk,failure)->{
            if(!plugin.host().isEnabled())return;
            Bukkit.getScheduler().runTask(plugin.host(),()->{
                if(generation!=recallGeneration || !expected.equals(knownId))return;
                boolean ticket=false;
                try {
                    Player owner=plugin.authorizedActor(turn);
                    if(plugin.isPaused() || !plugin.currentOwner(turn))throw new IllegalStateException("Recall authorization changed");
                    if(failure!=null || chunk==null || !chunk.isLoaded())throw new IllegalStateException("Recorded existing chunk unavailable");
                    ticket=chunk.addPluginChunkTicket(plugin.host());
                    Mannequin found=null;
                    for(Entity entity:chunk.getEntities())
                        if(entity.getUniqueId().equals(expected) && entity instanceof Mannequin candidate
                            && candidate.getPersistentDataContainer().has(tag,PersistentDataType.BYTE))found=candidate;
                    if(found==null || !found.isValid() || found.isDead())throw new IllegalStateException("Exact registered body not present");
                    for(World world:Bukkit.getWorlds())for(Entity entity:world.getEntities())
                        if(entity instanceof Mannequin && entity.getPersistentDataContainer().has(tag,PersistentDataType.BYTE)
                            && !entity.getUniqueId().equals(expected))throw new IllegalStateException("Another tagged body is loaded");
                    if(!plugin.currentOwner(turn) || generation!=recallGeneration || water.foreignVehicle(found))
                        throw new IllegalStateException("Recall no longer safe");
                    body=found;configure(body);catchUp(owner);rememberPose();plugin.queueCompanionPersistence();
                    if(!automatic)owner.sendMessage(plugin.settings().chatPrefix()+status());
                }catch(Exception refused) {
                    Player owner=Bukkit.getPlayer(turn.actorId());
                    if(!automatic && owner!=null)owner.sendMessage(plugin.settings().chatPrefix()+"Recall stopped safely: verify the saved body position and clear loaded ground or water beside you.");
                }finally {
                    if(ticket && chunk!=null)chunk.removePluginChunkTicket(plugin.host());
                    if(generation==recallGeneration)recallPending=false;
                }
            });
        });
        return new ToolReply("Recall queued for the one recorded existing chunk; no replacement body or new terrain will be created",false);
    }
    /** Read-only batch admission for managed experiences; never adopts or spawns. */
    void preflightExperience(JsonObject input,Turn turn) {
        Player actor=plugin.authorizedActor(turn);
        String action=ExperienceSpec.text(input,"action",32,true);
        if(!Set.of("status","stay","wave","summon","dismiss").contains(action))
            throw new IllegalArgumentException("Experience callbacks support immediate companion actions only");
        if(action.equals("status"))return;
        if(turn.event() || (plugin.isPaused() && !Set.of("stay","dismiss").contains(action)))
            throw new IllegalArgumentException("Companion action is not currently permitted");
        boolean owner=plugin.currentOwner(turn);
        if(action.equals("dismiss") && !owner)throw new IllegalArgumentException("Dismiss requires an owner");
        if(job!=null && (!Set.of("stay","dismiss").contains(action)
            || (controller!=null && !controller.actorId().equals(turn.actorId()) && !owner)))
            throw new IllegalArgumentException("Physical job prevents this companion action");
        boolean loaded=body!=null && body.isValid() && !body.isDead();
        if(action.equals("summon")) {
            if(loaded)return;
            if(knownId!=null || anyTaggedLoaded() || findSummon(actor)==null)
                throw new IllegalArgumentException("No safe summon; existing body may be unloaded");
            return;
        }
        if(!loaded || !body.getWorld().equals(actor.getWorld())
            || body.getLocation().distanceSquared(actor.getLocation())>32*32)
            throw new IllegalArgumentException("Companion must be loaded and nearby");
    }
    private boolean anyTaggedLoaded() {
        for(World w:Bukkit.getWorlds())for(Entity e:w.getEntities())if(e.getPersistentDataContainer().has(tag,PersistentDataType.BYTE))return true;return false;
    }
    private Location findSummon(Player actor) {
        Location p=actor.getLocation();World w=actor.getWorld();int y=p.getBlockY();
        for(var candidate:CompanionLanding.candidates(p.getBlockX(),y,p.getBlockZ())) {
            int x=candidate.x(),cy=candidate.y(),z=candidate.z();
            if(!safeCell(w,x,cy,z))continue;
            boolean occupied=false;
            for(Entity entity:w.getNearbyEntities(new org.bukkit.util.BoundingBox(x+0.15,cy,z+0.15,x+0.85,cy+1.9,z+0.85)))
                if(entity instanceof LivingEntity && (knownId==null || !knownId.equals(entity.getUniqueId()))){occupied=true;break;}
            if(!occupied)return new Location(w,x+0.5,cy,z+0.5,p.getYaw(),0);
        }return null;
    }
    private boolean plan(int x,int z) {
        Location here=body.getLocation();level=here.getBlockY();
        var start=new CompanionPlan.Cell(here.getBlockX(),here.getBlockZ());var goal=new CompanionPlan.Cell(x,z);
        if(start.equals(goal)){route=List.of();return true;}
        route=CompanionPlan.route(start,goal,cell->safeCell(body.getWorld(),cell.x(),level,cell.z()));step=0;stalled=0;lastDistance=Double.MAX_VALUE;
        return !route.isEmpty();
    }
    private void tick() {
        if(++presenceTicks%20==0){rememberPose();plugin.queueCompanionPersistence();}
        if(plugin.isPaused()){water.park(body);return;}
        try {
            if(presenceTicks%20==0 && job==null && (controller==null || following))keepNearby();
            if(controller==null)return;
            Player actor=plugin.authorizedActor(controller);
            if(plugin.isPaused() || body==null || !body.isValid() || body.isDead()
                || !body.getWorld().equals(actor.getWorld()) || body.getLocation().distanceSquared(actor.getLocation())>32*32
                || System.currentTimeMillis()>deadline)throw new IllegalStateException("Actor, body or time limit unavailable");
            if(water.foreignVehicle(body))throw new IllegalStateException("Body is in an unrelated vehicle");
            if(water.aboard(body)){water.tick(body);return;}
            Location here=body.getLocation();
            if(!jumping && !safeCell(body.getWorld(),here.getBlockX(),here.getBlockY(),here.getBlockZ()))throw new IllegalStateException("Unsafe or unloaded standing cell");
            if(terrainStep<terrain.size()){walkTerrainStep();return;}
            if(step<route.size()){walkStep();return;}
            body.setVelocity(new Vector());body.setImmovable(true);
            if(job!=null){buildStep();return;}
            if(!following)halt("Walk completed");
        } catch(Exception e) {finishJob("Stopped: actor permission, safe route, body or time limit changed",true);halt("Stopped safely; request a new route");}
    }
    /** Local companionship, independent of paid conversations. Never loads chunks or changes a player's position. */
    private void keepNearby() throws Exception {
        if(!keepCompany || System.currentTimeMillis()<Math.max(listeningUntil,designUntil))return;
        if(recallPending)return;
        adopt();
        if(body==null || !body.isValid() || body.isDead()) {
            recoverExistingBody();return;
        }
        Location here=body.getLocation();Player nearest=null;double distance=Double.MAX_VALUE;
        for(Player candidate:body.getWorld().getPlayers()) {
            if(!candidate.isOnline() || !candidate.hasPermission("claudecontrol.ask") || candidate.getGameMode()==GameMode.SPECTATOR)continue;
            double next=here.distanceSquared(candidate.getLocation());
            if(next<distance){nearest=candidate;distance=next;}
        }
        if(nearest==null) {
            for(Player candidate:Bukkit.getOnlinePlayers())
                if(candidate.hasPermission("claudecontrol.ask") && candidate.getGameMode()!=GameMode.SPECTATOR) {nearest=candidate;distance=0;break;}
        }
        if(nearest==null){halt("Waiting for friends to return");return;}
        boolean sameWorld=body.getWorld().equals(nearest.getWorld());
        if(!sameWorld || distance>24*24) {
            if(CompanionPresence.catchUp(sameWorld,distance,false,System.currentTimeMillis(),lastCatchUp))catchUp(nearest);
            else halt("Waiting before catching up to friends");
            return;
        }
        Turn nearby=new Turn(nearest.getUniqueId(),nearest.getName(),false,"Keeping nearby players company",false);
        plugin.authorizedActor(nearby);
        controller=nearby;following=true;deadline=Long.MAX_VALUE;
        if(jumping)return;
        if(water.follow(body,nearest)) {route=List.of();terrain=List.of();step=0;terrainStep=0;outcome="Traveling with you in Claude's own boat";return;}
        long now=System.currentTimeMillis();
        Location friendPosition=nearest.getLocation();
        boolean playerMoving=observedFriendPosition==null || !nearest.getUniqueId().equals(observedFriend)
            || !friendPosition.getWorld().equals(observedFriendPosition.getWorld())
            || friendPosition.distanceSquared(observedFriendPosition)>0.09 || nearest.isSprinting() || nearest.isFlying();
        observedFriend=nearest.getUniqueId();observedFriendPosition=friendPosition.clone();
        if(wanderUntil>now) {
            // Walking friends set the pace: drop curiosity and resume ordinary following.
            if(playerMoving){wanderUntil=0;terrain=List.of();terrainStep=0;}
            else {
                if(terrainStep<terrain.size())return;
                if(presenceTicks%40!=0)return;
                var random=java.util.concurrent.ThreadLocalRandom.current();Location anchor=nearest.getLocation();
                for(int attempt=0;attempt<4;attempt++) {
                    int x=anchor.getBlockX()+random.nextInt(-6,7),z=anchor.getBlockZ()+random.nextInt(-6,7);
                    for(int dy:new int[]{0,1,-1,2,-2}) {
                        int y=anchor.getBlockY()+dy;
                        if(safeCell(body.getWorld(),x,y,z) && planTerrain(x,y,z)) {outcome="Exploring nearby; local plan active";return;}
                    }
                }
                look(nearest.getEyeLocation());return;
            }
        }
        wanderUntil=0;
        if(distance<=9) {
            route=List.of();step=0;terrain=List.of();terrainStep=0;body.setVelocity(new Vector());body.setImmovable(true);
            look(nearest.getEyeLocation());
            try {localExpression(nearest,distance,playerMoving,now);}
            catch(RuntimeException unavailable){ /* Optional expression cannot stop companionship. */ }
            if(!playerMoving && now>=lastCuriosity && now-lastCuriosity>=45_000) {
                lastCuriosity=now;wanderUntil=now+8_000;return;
            }
            outcome="Keeping you company";return;
        }
        Location target=nearest.getLocation();
        for(int[] delta:new int[][]{{2,0},{-2,0},{0,2},{0,-2}})
            for(int dy:new int[]{0,1,-1})
                if(planTerrain(target.getBlockX()+delta[0],target.getBlockY()+dy,target.getBlockZ()+delta[1])) {outcome="Walking with you";return;}
        if(CompanionPresence.catchUp(true,distance,false,now,lastCatchUp))catchUp(nearest);
        else {terrain=List.of();terrainStep=0;outcome="Waiting before a safe catch-up";}
    }
    private void recoverExistingBody() {
        long now=System.currentTimeMillis();
        if(!keepCompany || plugin.isPaused() || job!=null || recallPending || knownId==null || lastPose==null
            || !lastPose.bodyId.equals(knownId) || now<lastAutomaticRecall || now-lastAutomaticRecall<15_000)return;
        for(Player owner:Bukkit.getOnlinePlayers()) {
            if(!owner.isOnline() || !owner.hasPermission("claudecontrol.ask") || owner.getGameMode()==GameMode.SPECTATOR)continue;
            Turn identity=new Turn(owner.getUniqueId(),owner.getName(),true,"Recovering the invited existing companion body",false);
            if(!plugin.currentOwner(identity))continue;
            lastAutomaticRecall=now;
            try {recall(identity,true);}catch(RuntimeException refused){recallPending=false;}
            return;
        }
    }
    private void catchUp(Player friend) {
        Turn identity=new Turn(friend.getUniqueId(),friend.getName(),false,"Keeping friends company",false);
        plugin.authorizedActor(identity);
        Location spot=findSummon(friend);
        halt("Catching up to friends");
        route=List.of();step=0;terrain=List.of();terrainStep=0;body.setVelocity(new Vector());body.setImmovable(true);
        try {
            if(water.foreignVehicle(body)){outcome="Waiting; body is aboard an unrelated vehicle";return;}
            if(spot==null && water.catchUp(body,friend)) {
                lastCatchUp=System.currentTimeMillis();controller=identity;following=true;deadline=Long.MAX_VALUE;
                rememberPose();outcome="Caught up in Claude's own boat";return;
            }
        }catch(Exception refused){outcome="Waiting; owned boat identity or safe water unavailable";return;}
        if(spot==null){outcome="Waiting for safe ground near you";return;}
        boolean moved=false;
        try {moved=water.aboard(body)?water.land(body,spot):body.teleport(spot);}
        catch(Exception refused){outcome="Waiting; owned boat identity could not be preserved";return;}
        if(moved) {
            lastCatchUp=System.currentTimeMillis();controller=identity;following=true;deadline=Long.MAX_VALUE;
            body.getWorld().spawnParticle(Particle.END_ROD,spot.clone().add(0,1,0),8,0.2,0.4,0.2,0.01);
            look(friend.getEyeLocation());outcome="Caught up beside you";
            rememberPose();
        }
    }
    public boolean readyForPlan() {
        return keepCompany && !plugin.isPaused() && body!=null && body.isValid() && !body.isDead()
            && job==null && System.currentTimeMillis()>=Math.max(listeningUntil,designUntil);
    }
    public boolean near(Player actor) {
        return body!=null && body.isValid() && !body.isDead() && actor.isOnline()
            && actor.getGameMode()!=GameMode.SPECTATOR && body.getWorld().equals(actor.getWorld())
            && body.getLocation().distanceSquared(actor.getLocation())<=32*32;
    }
    public JsonObject snapshot() {
        JsonObject state=new JsonObject();boolean loaded=body!=null && body.isValid() && !body.isDead();
        state.addProperty("loaded",loaded);state.addProperty("invited",knownId!=null);
        state.addProperty("keeps_company",keepCompany);state.addProperty("outcome",outcome);
        state.add("appearance",appearance.json());
        long now=System.currentTimeMillis();
        String mode=!loaded?(knownId==null?"absent":"unloaded"):plugin.isPaused()?"paused":water.aboard(body)?"boating":job!=null?"building":
            designUntil>now?"designing":listeningUntil>now?"listening":wanderUntil>now?"exploring":
            terrainStep<terrain.size() || step<route.size()?"walking":keepCompany?"keeping_company":"staying";
        state.addProperty("mode",mode);
        state.add("vehicle",water.snapshot(body));
        if(!loaded && lastPose!=null && lastPose.bodyId.equals(knownId))state.add("last_known_position",lastPose.json());
        state.addProperty("plan_remaining_seconds",Math.max(0,(Math.max(wanderUntil,designUntil)-now)/1000));
        if(loaded) {
            Location p=body.getLocation();state.addProperty("world",body.getWorld().getName());
            state.addProperty("x",p.getX());state.addProperty("y",p.getY());state.addProperty("z",p.getZ());
            state.addProperty("yaw",p.getYaw());state.addProperty("pitch",p.getPitch());state.addProperty("jumping",jumping);
            if(terrainStep<terrain.size()) {
                var goal=terrain.getLast();JsonObject destination=new JsonObject();
                destination.addProperty("x",goal.x());destination.addProperty("y",goal.y());destination.addProperty("z",goal.z());
                state.add("walking_destination",destination);
            }
            if(job!=null){state.addProperty("placed_blocks",job.placed);state.addProperty("planned_blocks",job.operations.size());}
        }
        return state;
    }
    static int burstDuration(JsonObject input) {
        int seconds=input.has("duration_seconds")?BuildSpec.integer(input,"duration_seconds"):30;
        if(seconds<10 || seconds>60)throw new IllegalArgumentException("Local plans require 10 to 60 seconds");
        return seconds;
    }
    private boolean planTerrain(int x,int y,int z) {
        Location here=body.getLocation();World world=body.getWorld();
        var start=new CompanionPlan.WalkCell(here.getBlockX(),here.getBlockY(),here.getBlockZ());
        var goal=new CompanionPlan.WalkCell(x,y,z);
        terrain=CompanionPlan.terrainRoute(start,goal,c->safeCell(world,c.x(),c.y(),c.z()),c->clearCell(world,c.x(),c.y(),c.z()));
        terrainStep=0;stalled=0;lastDistance=Double.MAX_VALUE;
        return !terrain.isEmpty();
    }
    private void walkTerrainStep() {
        var target=terrain.get(terrainStep);Location here=body.getLocation();World world=body.getWorld();
        if(!safeCell(world,target.x(),target.y(),target.z()))throw new IllegalStateException("Terrain route changed");
        double dx=target.x()+0.5-here.getX(),dz=target.z()+0.5-here.getZ();double distance=Math.sqrt(dx*dx+dz*dz);
        if(distance<0.12 && Math.abs(here.getY()-target.y())<0.1 && (!jumping || body.isOnGround())) {
            terrainStep++;jumping=false;body.setGravity(false);body.setVelocity(new Vector());body.setImmovable(true);stalled=0;return;
        }
        if(++stalled>100)throw new IllegalStateException("Jump or walk stalled");
        double vx=0,vz=0;
        if(Math.abs(dx)>0.04)vx=Math.copySign(Math.min(0.12,Math.abs(dx)),dx);
        else if(Math.abs(dz)>0.04)vz=Math.copySign(Math.min(0.12,Math.abs(dz)),dz);
        int feet=here.getBlockY();
        if(!clearCell(world,here.getBlockX(),feet,here.getBlockZ())
            || !clearCell(world,here.getBlockX(),feet+1,here.getBlockZ()))throw new IllegalStateException("Jump column obstructed");
        body.setImmovable(false);
        if(!jumping && target.y()!=feet) {
            if(target.y()>feet && (!clearCell(world,here.getBlockX(),feet+2,here.getBlockZ())
                || !clearCell(world,target.x(),target.y()+2,target.z())))throw new IllegalStateException("Jump headroom changed");
            jumpStart=here.clone();jumping=true;body.setGravity(true);body.setVelocity(new Vector(vx,target.y()>feet?0.42:0,vz));
        } else body.setVelocity(new Vector(vx,jumping?body.getVelocity().getY():0,vz));
        lookAlongRoute(target.x()+0.5,target.z()+0.5);
    }
    private static boolean clearCell(World world,int x,int y,int z) {
        try {GameTools.checkLocation(world,x,y,z);return world.getBlockAt(x,y,z).getType().isAir();}
        catch(IllegalArgumentException ignored){return false;}
    }
    public void interruptPlan(Turn incoming) {
        listeningUntil=System.currentTimeMillis()+60_000;
        if(job!=null && controller!=null && (controller.actorId().equals(incoming.actorId()) || plugin.currentOwner(incoming))) {
            finishJob("Interrupted by a new request; completed placements preserved",true);halt("Listening to your request");
        } else if(job==null && (following || wanderUntil>0 || terrainStep<terrain.size()))halt("Listening to your request");
    }
    public void requestFinished() {listeningUntil=0;lastCuriosity=System.currentTimeMillis();}
    private void walkStep() {
        Location here=body.getLocation();var target=route.get(step);
        if(here.getBlockY()!=level || !safeCell(body.getWorld(),target.x(),level,target.z()))throw new IllegalStateException("Route changed");
        double dx=target.x()+0.5-here.getX(),dz=target.z()+0.5-here.getZ();double distance=Math.sqrt(dx*dx+dz*dz);
        if(distance<0.06){step++;lastDistance=Double.MAX_VALUE;stalled=0;body.setVelocity(new Vector());return;}
        if(distance>=lastDistance-0.001){if(++stalled>60)throw new IllegalStateException("Body movement blocked");}else stalled=0;
        lastDistance=distance;
        // Advance one axis at a time; never cut a corner between cardinal cells.
        double vx=0,vz=0;
        if(Math.abs(dx)>0.04)vx=Math.copySign(Math.min(0.12,Math.abs(dx)),dx);
        else vz=Math.copySign(Math.min(0.12,Math.abs(dz)),dz);
        Location next=here.clone().add(vx,0,vz);
        if(!safeCell(body.getWorld(),next.getBlockX(),level,next.getBlockZ()))throw new IllegalStateException("Next cell unsafe");
        body.setImmovable(false);body.setVelocity(new Vector(vx,0,vz));
        lookAlongRoute(target.x()+0.5,target.z()+0.5);
    }
    public CompletableFuture<ToolReply> build(BuildSpec spec,Map<BuildSpec.BlockPlacement,Material> materials,Turn turn) {
        Player actor=plugin.authorizedActor(turn);adopt();
        if(job!=null || body==null || !body.isValid() || !body.getWorld().equals(actor.getWorld()) || !body.getWorld().getName().equals(spec.world()))
            return CompletableFuture.completedFuture(new ToolReply("Claude must have one loaded idle body in this world",true));
        if(materials.size()>16)return CompletableFuture.completedFuture(new ToolReply("Physical building supports at most 16 placements per job",true));
        if(body.getLocation().distanceSquared(actor.getLocation())>32*32)return CompletableFuture.completedFuture(new ToolReply("Stay within 32 blocks of Claude",true));
        // Check every operation has a currently safe reachable stand before starting the first placement.
        Location start=body.getLocation();
        for(var op:materials.keySet()) if(findStand(start,spec,op)==null)
            return CompletableFuture.completedFuture(new ToolReply("A block has no reachable same-level standing position within 4.5 blocks",true));
        halt("Physical build starting");controller=turn;deadline=System.currentTimeMillis()+60_000;
        job=new BuildJob(spec,materials);outcome="Building physically; job "+job.id;
        return job.result;
    }
    private CompanionPlan.Cell findStand(Location start,BuildSpec spec,BuildSpec.BlockPlacement op) {
        double bx=spec.x()+op.x()+0.5,by=spec.y()+op.y()+0.5,bz=spec.z()+op.z()+0.5;
        int y=start.getBlockY();World w=start.getWorld();var origin=new CompanionPlan.Cell(start.getBlockX(),start.getBlockZ());
        List<CompanionPlan.Cell> candidates=new ArrayList<>();
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++) {
            int x=(int)Math.floor(bx)+dx,z=(int)Math.floor(bz)+dz;
            // Standing outside the entire build volume prevents later blocks from trapping the companion.
            if(x>=spec.x() && x<spec.x()+spec.width() && z>=spec.z() && z<spec.z()+spec.depth())continue;
            double distance=Math.pow(x+0.5-bx,2)+Math.pow(y+1.4-by,2)+Math.pow(z+0.5-bz,2);
            if(distance<=4.5*4.5 && safeCell(w,x,y,z))candidates.add(new CompanionPlan.Cell(x,z));
        }
        candidates.sort(Comparator.comparingInt(c->Math.abs(c.x()-origin.x())+Math.abs(c.z()-origin.z())));
        int attempted=0;
        for(var candidate:candidates) {
            if(++attempted>8)break;
            if(candidate.equals(origin) || !CompanionPlan.route(origin,candidate,c->safeCell(w,c.x(),y,c.z())).isEmpty())return candidate;
        }return null;
    }
    private void buildStep() {
        if(++ticks%8!=0)return;
        if(job.index>=job.operations.size()){finishJob("Completed",false);halt("Physical build completed");return;}
        var op=job.operations.get(job.index);Location here=body.getLocation();
        var stand=findStand(here,job.spec,op);if(stand==null)throw new IllegalStateException("Stand became unreachable");
        if(here.getBlockX()!=stand.x() || here.getBlockZ()!=stand.z()) {
            if(!plan(stand.x(),stand.z()))throw new IllegalStateException("Build route blocked");return;
        }
        int x=job.spec.x()+op.x(),y=job.spec.y()+op.y(),z=job.spec.z()+op.z();
        GameTools.checkLocation(body.getWorld(),x,y,z);var block=body.getWorld().getBlockAt(x,y,z);
        if(!GameTools.replaceable(block))throw new IllegalStateException("Placement target changed; no overwrite");
        GameTools.requireUnoccupied(body.getWorld(),x,y,z);
        if(body.getEyeLocation().distanceSquared(new Location(body.getWorld(),x+0.5,y+0.5,z+0.5))>4.5*4.5)
            throw new IllegalStateException("Block beyond physical reach");
        body.getEquipment().setItemInMainHand(new ItemStack(job.materials.get(op)));
        look(new Location(body.getWorld(),x+0.5,y+0.5,z+0.5));body.swingMainHand();
        block.setType(job.materials.get(op),false);job.placed++;job.index++;
        ConnectedBlocks.refresh(block);
    }
    private void finishJob(String reason,boolean error) {
        if(job==null)return;BuildJob current=job;job=null;
        String result=reason+": physical job "+current.id+" placed "+current.placed+" of "+current.operations.size()
            +" blocks in "+current.spec.world()+" at "+current.spec.x()+","+current.spec.y()+","+current.spec.z()+". Completed placements remain; do not blindly repeat.";
        current.result.complete(new ToolReply(result,error));
        if(controller!=null){Player actor=Bukkit.getPlayer(controller.actorId());if(actor!=null)actor.sendMessage(plugin.settings().chatPrefix()+result);}
    }
    private void halt(String reason) {
        recallGeneration++;recallPending=false;water.park(body);
        boolean settle=jumping;
        if(jumping && jumpStart!=null && body!=null && body.isValid()
            && safeCell(jumpStart.getWorld(),jumpStart.getBlockX(),jumpStart.getBlockY(),jumpStart.getBlockZ()))settle=!body.teleport(jumpStart);
        following=false;controller=null;route=List.of();step=0;terrain=List.of();terrainStep=0;wanderUntil=0;designUntil=0;outcome=reason;
        if(body!=null && body.isValid()){body.setVelocity(new Vector());body.setImmovable(!settle);body.setGravity(settle);jumping=false;}
    }
    private void look(Location target) {body.lookAt(target.getX(),target.getY(),target.getZ(),LookAnchor.EYES);}
    private void lookAlongRoute(double x,double z) {
        Location eyes=body.getEyeLocation();
        // Waypoints below eye height made pitch oscillate as each block was approached.
        // Keep a level gaze and avoid turning back toward an almost reached waypoint.
        if(Math.hypot(x-eyes.getX(),z-eyes.getZ())<0.2)return;
        body.lookAt(x,eyes.getY(),z,LookAnchor.EYES);
    }
    private void localExpression(Player friend,double distance,boolean playerMoving,long now) {
        if(!CompanionWhimsy.allowed(keepCompany,plugin.isPaused(),job!=null || recallPending
            || now<Math.max(listeningUntil,designUntil),jumping || step<route.size() || terrainStep<terrain.size(),
            playerMoving,distance,now,lastExpression) || water.aboard(body) || water.foreignVehicle(body))return;
        Location p=body.getLocation();
        if(!safeCell(body.getWorld(),p.getBlockX(),p.getBlockY(),p.getBlockZ()))return;
        lastExpression=now;
        int choice=java.util.concurrent.ThreadLocalRandom.current().nextInt(4);
        if(choice==0) {
            // A brief sideways glance at eye height; ordinary following restores attention next tick.
            body.lookAt(p.getX()+2,body.getEyeLocation().getY(),p.getZ()-2,LookAnchor.EYES);
        } else {
            look(friend.getEyeLocation());body.swingMainHand();
            if(choice>=2)friend.spawnParticle(Particle.END_ROD,body.getEyeLocation(),5,0.2,0.15,0.2,0.005);
            if(choice==3)friend.playSound(p,"minecraft:block.amethyst_block.chime",SoundCategory.NEUTRAL,0.12f,1.35f);
        }
    }
    /** Optional expression for powerful tools; never spawns, changes jobs or diverts a walk. */
    public void gesture(Turn turn) {
        try {
            Player actor=plugin.authorizedActor(turn);
            long now=System.currentTimeMillis();
            if(body==null || !body.isValid() || !keepCompany || plugin.isPaused() || jumping
                || now<Math.max(listeningUntil,designUntil) || now<lastExpression || now-lastExpression<25_000
                || job!=null || step<route.size() || terrainStep<terrain.size()
                || !body.getWorld().equals(actor.getWorld()) || body.getLocation().distanceSquared(actor.getLocation())>32*32)return;
            Location p=body.getLocation();
            if(!body.getWorld().isChunkLoaded(p.getBlockX()>>4,p.getBlockZ()>>4))return;
            lastExpression=now;
            look(actor.getEyeLocation());body.swingMainHand();
            body.getWorld().spawnParticle(Particle.END_ROD,body.getEyeLocation(),6,0.15,0.15,0.15,0.01);
        } catch(RuntimeException ignored) { /* The requested effect does not depend on the optional gesture. */ }
    }
    public void stop(String reason) {finishJob(reason,true);halt(reason);}
    public void close() {stop("Plugin stopped; physical work paused");if(timer!=null){timer.cancel();timer=null;}}
    public void reload() {
        stop("Settings reloaded; physical movement stopped");if(timer!=null)timer.cancel();
        timer=Bukkit.getScheduler().runTaskTimer(plugin.host(),this::tick,1,1);
    }
    public String status() {
        if(body==null || !body.isValid())return knownId==null?"Claude has not been summoned":"Claude's persistent body is unloaded";
        Location p=body.getLocation();return outcome+" in "+body.getWorld().getName()+" at "+p.getBlockX()+","+p.getBlockY()+","+p.getBlockZ();
    }
    private static boolean safeCell(World w,int x,int y,int z) {
        try {
            GameTools.checkLocation(w,x,y,z);GameTools.checkLocation(w,x,y+1,z);GameTools.checkLocation(w,x,y-1,z);
            Material floor=w.getBlockAt(x,y-1,z).getType();
            return floor.isSolid() && floor.isOccluding() && GameTools.safeLanding(floor)
                && w.getBlockAt(x,y,z).getType().isAir() && w.getBlockAt(x,y+1,z).getType().isAir();
        }catch(IllegalArgumentException e){return false;}
    }
}
