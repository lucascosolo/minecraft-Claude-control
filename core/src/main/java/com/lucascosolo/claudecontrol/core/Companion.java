package com.lucascosolo.claudecontrol.core;

import com.google.gson.JsonObject;
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
    private final ClaudeControlPlugin plugin;
    private final NamespacedKey tag;
    private final MemoryStore registry;
    private volatile UUID knownId;
    private Mannequin body;
    private BukkitTask timer;
    private Turn controller;
    private boolean following;
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
    public Companion(ClaudeControlPlugin plugin) throws Exception {
        this.plugin=plugin;tag=new NamespacedKey(plugin,"claude_companion");
        registry=new MemoryStore(plugin.getDataFolder().toPath().resolve("companion"));
        String saved=registry.read("body-uuid.txt").trim();if(!saved.isEmpty())knownId=UUID.fromString(saved);
        adopt();
        timer=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);
    }
    public void persistIdentity() throws Exception {
        UUID id=knownId;if(id!=null && !registry.read("body-uuid.txt").trim().equals(id.toString()))registry.write("body-uuid.txt",id.toString());
    }
    private void adopt() {
        if(body!=null && body.isValid() && !body.isDead())return;
        body=null;
        for(World world:Bukkit.getWorlds()) for(Entity entity:world.getEntities()) {
            if(entity instanceof Mannequin candidate && candidate.getPersistentDataContainer().has(tag,PersistentDataType.BYTE)) {
                if(knownId==null || candidate.getUniqueId().equals(knownId)) {
                    body=candidate;knownId=candidate.getUniqueId();configure(candidate);outcome="Standing by";return;
                }
            }
        }
    }
    private void configure(Mannequin entity) {
        entity.customName(Component.text("Claude"));entity.setCustomNameVisible(true);
        entity.setDescription(Component.text("Your Minecraft companion"));entity.setInvulnerable(true);
        entity.setCanPickupItems(false);entity.setPersistent(true);entity.setRemoveWhenFarAway(false);
        entity.setAI(false);entity.setGravity(false);entity.setCollidable(false);entity.setImmovable(true);
    }
    public ToolReply action(JsonObject input,Turn turn) {
        Player actor=plugin.authorizedActor(turn);String action=input.get("action").getAsString();
        if(action.equals("status"))return new ToolReply(status(),false);
        if(turn.event())return new ToolReply("Automatic events cannot control Claude's body",true);
        if(plugin.isPaused() && !action.equals("stay"))return new ToolReply("Claude is paused; an owner must resume before controlling the body",true);
        if(job!=null && !action.equals("stay"))return new ToolReply("Claude is finishing a physical building job; use status",true);
        if(job!=null && controller!=null && !controller.actorId().equals(turn.actorId()) && !plugin.currentOwner(turn))
            return new ToolReply("Only the initiating player or an owner can stop this building job",true);
        adopt();
        if(action.equals("summon")) {
            if(body!=null)return new ToolReply("Claude already has a loaded body: "+status(),false);
            // A saved or other tagged body may be unloaded. Refuse another body instead of loading chunks.
            if(knownId!=null || anyTaggedLoaded())return new ToolReply("Claude's persistent body is elsewhere or unloaded; visit its area rather than spawning another",true);
            Location spot=findSummon(actor);
            if(spot==null)return new ToolReply("No safe, clear, loaded standing spot beside the player",true);
            body=actor.getWorld().spawn(spot,Mannequin.class,entity->{
                entity.getPersistentDataContainer().set(tag,PersistentDataType.BYTE,(byte)1);configure(entity);
            });knownId=body.getUniqueId();outcome="Summoned";
            return new ToolReply("Claude appeared beside you. "+status(),false);
        }
        if(body==null || !body.isValid())return new ToolReply("Claude's body is not loaded; summon first or visit its existing area",true);
        if(!body.getWorld().equals(actor.getWorld()) || body.getLocation().distanceSquared(actor.getLocation())>32*32)
            return new ToolReply("Control Claude while nearby in the same world",true);
        switch(action) {
            case "stay" -> {finishJob("Stopped by request",true);halt("Staying here");}
            case "wave" -> {look(actor.getEyeLocation());body.swingMainHand();outcome="Waved";}
            case "follow" -> {halt("Following nearby actor");controller=turn;following=true;deadline=Long.MAX_VALUE;}
            case "move" -> {
                int x=BuildSpec.integer(input,"x"),y=BuildSpec.integer(input,"y"),z=BuildSpec.integer(input,"z");
                if(y!=body.getLocation().getBlockY())return new ToolReply("Claude currently walks on one level; no stairs or jumping",true);
                halt("Planning walk");controller=turn;deadline=System.currentTimeMillis()+60_000;
                if(!plan(x,z))return new ToolReply("No bounded safe loaded route; Claude stayed put",true);outcome="Walking";
            }
            default -> {return new ToolReply("Unknown companion action",true);}
        }
        return new ToolReply(status(),false);
    }
    private boolean anyTaggedLoaded() {
        for(World w:Bukkit.getWorlds())for(Entity e:w.getEntities())if(e.getPersistentDataContainer().has(tag,PersistentDataType.BYTE))return true;return false;
    }
    private Location findSummon(Player actor) {
        Location p=actor.getLocation();World w=actor.getWorld();int y=p.getBlockY();
        for(int[] delta:new int[][]{{2,0},{-2,0},{0,2},{0,-2},{3,0},{0,3}}) {
            int x=p.getBlockX()+delta[0],z=p.getBlockZ()+delta[1];
            if(safeCell(w,x,y,z))return new Location(w,x+0.5,y,z+0.5,p.getYaw(),0);
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
        if(controller==null)return;
        try {
            Player actor=plugin.authorizedActor(controller);
            if(plugin.isPaused() || body==null || !body.isValid() || body.isDead()
                || !body.getWorld().equals(actor.getWorld()) || body.getLocation().distanceSquared(actor.getLocation())>32*32
                || System.currentTimeMillis()>deadline)throw new IllegalStateException("Actor, body or time limit unavailable");
            Location here=body.getLocation();
            if(!safeCell(body.getWorld(),here.getBlockX(),here.getBlockY(),here.getBlockZ()))throw new IllegalStateException("Unsafe or unloaded standing cell");
            if(step<route.size()){walkStep();return;}
            body.setVelocity(new Vector());body.setImmovable(true);
            if(job!=null){buildStep();return;}
            if(following) {
                if(++ticks%20!=0)return;
                Location target=actor.getLocation();
                if(target.getBlockY()!=here.getBlockY())throw new IllegalStateException("Follow target left this walking level");
                if(here.distanceSquared(target)<=9)return;
                // Stand beside the player, avoiding occupation of the player's exact cell.
                boolean planned=false;
                for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
                    if(plan(target.getBlockX()+d[0],target.getBlockZ()+d[1])){planned=true;break;}
                if(!planned)throw new IllegalStateException("Follow route blocked or beyond bounded range");
            } else halt("Walk completed");
        } catch(Exception e) {finishJob("Stopped: actor permission, safe route, body or time limit changed",true);halt("Stopped safely; request a new route");}
    }
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
        look(new Location(body.getWorld(),target.x()+0.5,level+1.4,target.z()+0.5));
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
        if(body.getEyeLocation().distanceSquared(new Location(body.getWorld(),x+0.5,y+0.5,z+0.5))>4.5*4.5)
            throw new IllegalStateException("Block beyond physical reach");
        body.getEquipment().setItemInMainHand(new ItemStack(job.materials.get(op)));
        look(new Location(body.getWorld(),x+0.5,y+0.5,z+0.5));body.swingMainHand();
        block.setType(job.materials.get(op),false);job.placed++;job.index++;
    }
    private void finishJob(String reason,boolean error) {
        if(job==null)return;BuildJob current=job;job=null;
        String result=reason+": physical job "+current.id+" placed "+current.placed+" of "+current.operations.size()
            +" blocks in "+current.spec.world()+" at "+current.spec.x()+","+current.spec.y()+","+current.spec.z()+". Completed placements remain; do not blindly repeat.";
        current.result.complete(new ToolReply(result,error));
        if(controller!=null){Player actor=Bukkit.getPlayer(controller.actorId());if(actor!=null)actor.sendMessage(plugin.settings().chatPrefix()+result);}
    }
    private void halt(String reason) {
        following=false;controller=null;route=List.of();step=0;outcome=reason;
        if(body!=null && body.isValid()){body.setVelocity(new Vector());body.setImmovable(true);}
    }
    private void look(Location target) {body.lookAt(target.getX(),target.getY(),target.getZ(),LookAnchor.EYES);}
    /** Optional expression for powerful tools; never spawns, changes jobs or diverts a walk. */
    public void gesture(Turn turn) {
        try {
            Player actor=plugin.authorizedActor(turn);
            if(body==null || !body.isValid() || job!=null || step<route.size()
                || !body.getWorld().equals(actor.getWorld()) || body.getLocation().distanceSquared(actor.getLocation())>32*32)return;
            Location p=body.getLocation();
            if(!body.getWorld().isChunkLoaded(p.getBlockX()>>4,p.getBlockZ()>>4))return;
            look(actor.getEyeLocation());body.swingMainHand();
            body.getWorld().spawnParticle(Particle.END_ROD,body.getEyeLocation(),6,0.15,0.15,0.15,0.01);
        } catch(RuntimeException ignored) { /* The requested effect does not depend on the optional gesture. */ }
    }
    public void stop(String reason) {finishJob(reason,true);halt(reason);}
    public void close() {stop("Plugin stopped; physical work paused");if(timer!=null){timer.cancel();timer=null;}}
    public void reload() {
        stop("Settings reloaded; physical movement stopped");if(timer!=null)timer.cancel();
        timer=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);
    }
    public String status() {
        if(body==null || !body.isValid())return knownId==null?"Claude has not been summoned":"Claude's persistent body is unloaded";
        Location p=body.getLocation();return outcome+" in "+body.getWorld().getName()+" at "+p.getBlockX()+","+p.getBlockY()+","+p.getBlockZ()+"; physically placed blocks are checked individually";
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
