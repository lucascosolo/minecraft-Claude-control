package com.lucascosolo.claudecontrol.core;

import com.google.gson.JsonObject;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.entity.boat.OakBoat;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import java.util.*;

/** Loaded-only native boat travel. Never adopts, ejects or propels somebody else's vehicle. */
final class CompanionWater {
    private final MemoryStore registry;
    private final Object identityLock;
    private final NamespacedKey tag;
    private volatile UUID knownId;
    private OakBoat boat;
    private List<CompanionPlan.Cell> route=List.of();
    private int step,surfaceY,stalled;
    private double lastDistance=Double.MAX_VALUE;
    private Location lastMotion;

    CompanionWater(ClaudeRuntime plugin,MemoryStore registry,Object identityLock) throws Exception {
        this.registry=registry;this.identityLock=identityLock;
        tag=new NamespacedKey(plugin.host(),"claude_companion_boat");
        String saved=registry.read("boat-uuid.txt").trim();
        if(!saved.isEmpty())knownId=UUID.fromString(saved);
        adopt();
    }
    void persistIdentity() throws Exception {
        synchronized(identityLock) {
            if(knownId!=null && !registry.read("boat-uuid.txt").trim().equals(knownId.toString()))
                registry.write("boat-uuid.txt",knownId.toString());
        }
    }
    private void adopt() {
        if(boat!=null && boat.isValid() && !boat.isDead())return;
        boat=null;
        for(World world:Bukkit.getWorlds())for(Entity entity:world.getEntities()) {
            if(entity instanceof OakBoat candidate && !(entity instanceof org.bukkit.entity.ChestBoat)
                && entity.getPersistentDataContainer().has(tag,PersistentDataType.BYTE)
                && (knownId==null || knownId.equals(entity.getUniqueId()))) {
                boat=candidate;knownId=entity.getUniqueId();return;
            }
        }
    }
    private boolean owned() {
        return boat!=null && boat.isValid() && !boat.isDead() && boat.getUniqueId().equals(knownId)
            && boat.getPersistentDataContainer().has(tag,PersistentDataType.BYTE);
    }
    private boolean exclusive(Mannequin body) {
        return owned() && boat.getPassengers().stream().allMatch(e->e.getUniqueId().equals(body.getUniqueId()));
    }
    boolean aboard(Mannequin body) {
        return body!=null && owned() && body.getVehicle()!=null && body.getVehicle().getUniqueId().equals(knownId);
    }
    boolean foreignVehicle(Mannequin body) {
        if(!body.isInsideVehicle())return false;
        adopt();return !aboard(body);
    }

    private static boolean surface(World world,int x,int y,int z) {
        try {
            // Ordinary boats are wider than a block: validate the whole neighboring footprint.
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                for(int dy=-1;dy<=2;dy++)GameTools.checkLocation(world,x+dx,y+dy,z+dz);
                if(world.getBlockAt(x+dx,y-1,z+dz).getType()!=Material.WATER
                    || !world.getBlockAt(x+dx,y,z+dz).getType().isAir()
                    || !world.getBlockAt(x+dx,y+1,z+dz).getType().isAir()
                    || !world.getBlockAt(x+dx,y+2,z+dz).getType().isAir())return false;
            }
            return true;
        } catch(IllegalArgumentException unavailable) {return false;}
    }
    private static Location waterNear(Location anchor,int radius) {
        World world=anchor.getWorld();
        for(int r=0;r<=radius;r++)for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))!=r)continue;
            for(int dy:new int[]{0,1,-1,2,-2}) {
                int x=anchor.getBlockX()+dx,y=anchor.getBlockY()+dy,z=anchor.getBlockZ()+dz;
                if(surface(world,x,y,z))return new Location(world,x+0.5,y-0.1,z+0.5);
            }
        }
        return null;
    }
    private boolean create(Location spot) throws Exception {
        adopt();
        if(knownId!=null || owned())return owned();
        // A loaded tagged vehicle of an unsupported type is still an existing identity.
        for(World world:Bukkit.getWorlds())for(Entity entity:world.getEntities())
            if(entity.getPersistentDataContainer().has(tag,PersistentDataType.BYTE))return false;
        synchronized(identityLock) {
            boat=spot.getWorld().spawn(spot,OakBoat.class,e->{
                e.getPersistentDataContainer().set(tag,PersistentDataType.BYTE,(byte)1);
                e.setPersistent(true);e.setInvulnerable(true);e.setGravity(true);
            });
            knownId=boat.getUniqueId();persistIdentity();
        }
        return true;
    }
    private boolean board(Mannequin body) {
        if(!exclusive(body) || foreignVehicle(body) || !body.getWorld().equals(boat.getWorld()))return false;
        body.setVelocity(new Vector());body.setGravity(false);body.setImmovable(false);
        if(aboard(body))return true;
        return boat.addPassenger(body) && aboard(body);
    }
    boolean start(Mannequin body) throws Exception {
        if(foreignVehicle(body))return false;
        adopt();
        if(aboard(body))return exclusive(body);
        if(knownId!=null && !owned())return false;
        Location nearby=freeWaterNear(body,body.getLocation(),3);
        if(nearby==null)return false;
        if(!create(nearby) || !exclusive(body) || !body.getWorld().equals(boat.getWorld())
            || body.getLocation().distanceSquared(boat.getLocation())>25)return false;
        return board(body);
    }
    /** Once per second: retarget only the owned boat containing the invited body. */
    boolean follow(Mannequin body,Player friend) throws Exception {
        if(foreignVehicle(body))return false;
        if(!aboard(body)) {
            if(waterNear(friend.getLocation(),2)==null || !start(body))return false;
        }
        if(!exclusive(body) || !boat.getWorld().equals(friend.getWorld())) {park(body);return true;}
        body.setImmovable(false);body.setGravity(false);
        Location here=boat.getLocation();Location friendWater=waterNear(friend.getLocation(),2);
        Location surface=waterNear(here,0);
        if(surface==null){park(body);return true;}
        surfaceY=surface.getBlockY()+1;
        if(friendWater==null && here.distanceSquared(friend.getLocation())<=16*16 && stepAshore(body,friend.getLocation()))return false;
        if(here.distanceSquared(friend.getLocation())<=9){park(body);return true;}
        int tx=friend.getLocation().getBlockX(),tz=friend.getLocation().getBlockZ();
        int x=here.getBlockX(),z=here.getBlockZ();
        tx=x+(int)Math.clamp((long)tx-x,-12L,12L);
        tz=z+(int)Math.clamp((long)tz-z,-12L,12L);
        var start=new CompanionPlan.Cell(x,z);
        for(int[] offset:new int[][]{{-2,0},{2,0},{0,-2},{0,2},{0,0}}) {
            var goal=new CompanionPlan.Cell(tx+offset[0],tz+offset[1]);
            var planned=WaterPlan.route(start,goal,c->surface(boat.getWorld(),c.x(),surfaceY,c.z()));
            if(!planned.isEmpty()) {route=planned;step=0;return true;}
        }
        park(body);return true;
    }
    void tick(Mannequin body) {
        if(!aboard(body) || !exclusive(body)) {route=List.of();step=0;return;}
        if(step>=route.size()){boat.setVelocity(new Vector());return;}
        Location here=boat.getLocation();var target=route.get(step);
        if(!surface(boat.getWorld(),here.getBlockX(),surfaceY,here.getBlockZ())
            || !surface(boat.getWorld(),target.x(),surfaceY,target.z())) {park(body);return;}
        double dx=target.x()+0.5-here.getX(),dz=target.z()+0.5-here.getZ();
        double distance=Math.hypot(dx,dz);
        if(distance<0.12){step++;lastDistance=Double.MAX_VALUE;boat.setVelocity(new Vector());return;}
        if(lastMotion!=null && lastMotion.getWorld().equals(here.getWorld()) && lastMotion.distanceSquared(here)<0.00001)stalled++;else stalled=0;
        lastMotion=here.clone();
        if(stalled>60){park(body);return;}
        lastDistance=distance;
        var motion=WaterPlan.velocity(dx,dz);Location next=here.clone().add(motion.x(),0,motion.z());
        if(!surface(boat.getWorld(),next.getBlockX(),surfaceY,next.getBlockZ())){park(body);return;}
        for(Entity nearby:boat.getWorld().getNearbyEntities(next,0.9,1.5,0.9)) {
            if(!nearby.getUniqueId().equals(knownId) && !nearby.getUniqueId().equals(body.getUniqueId())
                && (nearby instanceof Player || nearby instanceof Vehicle || nearby instanceof Mannequin)) {park(body);return;}
        }
        boat.setGravity(true);boat.setVelocity(new Vector(motion.x(),boat.getVelocity().getY(),motion.z()));
        boat.setRotation((float)Math.toDegrees(Math.atan2(-motion.x(),motion.z())),0);
    }
    void park(Mannequin body) {
        route=List.of();step=0;stalled=0;lastDistance=Double.MAX_VALUE;lastMotion=null;
        if(body!=null && exclusive(body))boat.setVelocity(new Vector());
    }
    private boolean stepAshore(Mannequin body,Location friend) throws Exception {
        Location here=boat.getLocation();List<Location> landings=new ArrayList<>();
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int dy:new int[]{0,1,-1}) {
            int x=here.getBlockX()+dx,y=surfaceY+dy,z=here.getBlockZ()+dz;
            if(safeLanding(here.getWorld(),x,y,z))landings.add(new Location(here.getWorld(),x+0.5,y,z+0.5));
        }
        landings.sort(Comparator.comparingDouble(p->p.distanceSquared(friend)));
        return !landings.isEmpty() && land(body,landings.getFirst());
    }
    private static boolean safeLanding(World world,int x,int y,int z) {
        try {
            for(int dy=-1;dy<=1;dy++)GameTools.checkLocation(world,x,y+dy,z);
            Material floor=world.getBlockAt(x,y-1,z).getType();
            return floor.isSolid() && floor.isOccluding() && GameTools.safeLanding(floor)
                && world.getBlockAt(x,y,z).getType().isAir() && world.getBlockAt(x,y+1,z).getType().isAir();
        } catch(IllegalArgumentException unavailable) {return false;}
    }
    boolean land(Mannequin body,Location spot) throws Exception {
        if(!exclusive(body) || !aboard(body) || !safeLanding(spot.getWorld(),spot.getBlockX(),spot.getBlockY(),spot.getBlockZ()))return false;
        park(body);
        if(!boat.removePassenger(body))return false;
        if(!body.teleport(spot)){board(body);return false;}
        body.setGravity(false);body.setImmovable(true);retireEmpty();return true;
    }
    boolean catchUp(Mannequin body,Player friend) throws Exception {
        if(foreignVehicle(body))return false;
        Location spot=freeWaterNear(body,friend.getLocation(),3);if(spot==null)return false;
        adopt();if(knownId!=null && !owned())return false;
        if(!create(spot) || !exclusive(body))return false;
        park(body);boolean wasAboard=aboard(body);
        if(wasAboard && !boat.removePassenger(body))return false;
        Location old=boat.getLocation();
        if(!boat.teleport(spot)) {if(wasAboard)board(body);return false;}
        if(!body.teleport(spot)) {boat.teleport(old);if(wasAboard)board(body);return false;}
        return board(body);
    }
    private Location freeWaterNear(Mannequin body,Location anchor,int radius) {
        for(int r=0;r<=radius;r++)for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))!=r)continue;
            Location candidate=waterNear(anchor.clone().add(dx,0,dz),0);
            if(candidate==null)continue;
            boolean free=true;
            for(Entity entity:candidate.getWorld().getNearbyEntities(candidate,1.1,1.5,1.1))
                if(!entity.getUniqueId().equals(body.getUniqueId()) && !entity.getUniqueId().equals(knownId)
                    && (entity instanceof Player || entity instanceof Vehicle || entity instanceof Mannequin)) {free=false;break;}
            if(free)return candidate;
        }
        return null;
    }
    private void retireEmpty() throws Exception {
        if(!owned() || !boat.getPassengers().isEmpty())return;
        synchronized(identityLock) {
            registry.write("boat-uuid.txt",""); // Preserve identity replacement before removing only our empty ordinary boat.
            boat.remove();boat=null;knownId=null;
        }
    }
    void dismiss(Mannequin body) throws Exception {
        park(body);
        if(aboard(body) && exclusive(body))boat.removePassenger(body);
        retireEmpty();
    }
    JsonObject snapshot(Mannequin body) {
        JsonObject state=new JsonObject();state.addProperty("owned",knownId!=null);
        state.addProperty("loaded",owned());state.addProperty("aboard",aboard(body));
        state.addProperty("navigating",aboard(body) && step<route.size());
        if(knownId!=null)state.addProperty("uuid",knownId.toString());
        return state;
    }
}
