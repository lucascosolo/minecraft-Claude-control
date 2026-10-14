package com.lucascosolo.claudecontrol.core;

import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.block.*;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.*;
import java.util.*;

/** Recomputes local visual joins without physics, drops, chunk loading or material changes. */
public final class ConnectedBlocks {
    public record BlockPos(int x,int y,int z) {
        BlockPos offset(BlockFace face){return new BlockPos(x+face.getModX(),y+face.getModY(),z+face.getModZ());}
    }
    private static final List<BlockFace> HORIZONTAL=List.of(BlockFace.NORTH,BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST);
    private ConnectedBlocks(){}
    public static void refresh(Block block){refresh(block.getWorld(),List.of(new BlockPos(block.getX(),block.getY(),block.getZ())));}
    public static void refresh(World world,Collection<BlockPos> placed){refresh(world,placed,Set.of());}
    public static void refresh(World world,Collection<BlockPos> placed,Set<BlockPos> protectedCells){
        if(placed.size()>8192)throw new IllegalArgumentException("Connection refresh exceeds bounded build size");
        Set<BlockPos> candidates=new LinkedHashSet<>();
        for(BlockPos pos:placed){
            candidates.add(pos);for(BlockFace face:HORIZONTAL)candidates.add(pos.offset(face));
            candidates.add(pos.offset(BlockFace.DOWN));candidates.add(pos.offset(BlockFace.UP));
        }
        // Two bounded passes let a wall see its newly connected wall above. No general physics.
        for(int pass=0;pass<2;pass++)for(BlockPos pos:candidates){
            if(protectedCells.contains(pos))continue;
            Block block=loaded(world,pos);if(block==null || block.getState() instanceof TileState || block.getState() instanceof Container)continue;
            BlockData before=block.getBlockData(),data=before.clone();
            if(data instanceof Fence fence){
                for(BlockFace face:HORIZONTAL)fence.setFace(face,fenceConnects(data,neighbor(world,pos,face),face));
            }else if(data instanceof GlassPane || data.getMaterial()==Material.IRON_BARS){
                if(data instanceof MultipleFacing pane)for(BlockFace face:HORIZONTAL)
                    pane.setFace(face,paneConnects(neighbor(world,pos,face),face));
            }else if(data instanceof Wall wall){
                BlockData above=neighbor(world,pos,BlockFace.UP);
                boolean covered=above!=null && above.isFaceSturdy(BlockFace.DOWN,BlockSupport.FULL);
                int count=0;for(BlockFace face:HORIZONTAL){
                    BlockData next=neighbor(world,pos,face);
                    boolean connected=next instanceof Wall || gateConnects(next,face) || sturdy(next,face);
                    wall.setHeight(face,connected?(covered?Wall.Height.TALL:Wall.Height.LOW):Wall.Height.NONE);
                    if(connected)count++;
                }
                boolean north=wall.getHeight(BlockFace.NORTH)!=Wall.Height.NONE,south=wall.getHeight(BlockFace.SOUTH)!=Wall.Height.NONE;
                boolean east=wall.getHeight(BlockFace.EAST)!=Wall.Height.NONE,west=wall.getHeight(BlockFace.WEST)!=Wall.Height.NONE;
                boolean straight=count==2 && ((north && south) || (east && west));
                wall.setUp(!straight || (above instanceof Wall upper && upper.isUp())
                    || (above!=null && above.isFaceSturdy(BlockFace.DOWN,BlockSupport.CENTER)));
            }else if(data instanceof Stairs stairs)stairs.setShape(stairShape(world,pos,stairs));
            else continue;
            if(!before.getAsString().equals(data.getAsString()))block.setBlockData(data,false);
        }
    }
    private static Block loaded(World world,BlockPos pos){
        if(pos.y()<world.getMinHeight() || pos.y()>=world.getMaxHeight() || !world.isChunkLoaded(pos.x()>>4,pos.z()>>4)
            || !world.getWorldBorder().isInside(new org.bukkit.Location(world,pos.x()+0.5,pos.y(),pos.z()+0.5)))return null;
        return world.getBlockAt(pos.x(),pos.y(),pos.z());
    }
    private static BlockData neighbor(World world,BlockPos pos,BlockFace face){Block block=loaded(world,pos.offset(face));return block==null?null:block.getBlockData();}
    private static boolean sturdy(BlockData data,BlockFace face){return data!=null && data.isFaceSturdy(face.getOppositeFace(),BlockSupport.FULL);}
    private static boolean gateConnects(BlockData data,BlockFace face){return data instanceof Gate gate && axis(gate.getFacing())!=axis(face);}
    private static int axis(BlockFace face){return face==BlockFace.NORTH || face==BlockFace.SOUTH?0:1;}
    private static boolean fenceConnects(BlockData own,BlockData next,BlockFace face){
        return (next instanceof Fence && (own.getMaterial()==Material.NETHER_BRICK_FENCE)==(next.getMaterial()==Material.NETHER_BRICK_FENCE))
            || gateConnects(next,face) || sturdy(next,face);
    }
    private static boolean paneConnects(BlockData next,BlockFace face){
        return next instanceof GlassPane || next instanceof Wall || (next!=null && (next.getMaterial()==Material.IRON_BARS
            || next.getMaterial()==Material.GLASS || next.getMaterial().name().endsWith("_STAINED_GLASS"))) || sturdy(next,face);
    }
    private static Stairs.Shape stairShape(World world,BlockPos pos,Stairs own){
        BlockFace facing=own.getFacing();BlockData front=neighbor(world,pos,facing);
        if(front instanceof Stairs other && other.getHalf()==own.getHalf() && axis(other.getFacing())!=axis(facing)
            && differentStair(world,pos,other.getFacing().getOppositeFace(),own))
            return other.getFacing()==left(facing)?Stairs.Shape.OUTER_LEFT:Stairs.Shape.OUTER_RIGHT;
        BlockData back=neighbor(world,pos,facing.getOppositeFace());
        if(back instanceof Stairs other && other.getHalf()==own.getHalf() && axis(other.getFacing())!=axis(facing)
            && differentStair(world,pos,other.getFacing(),own))
            return other.getFacing()==left(facing)?Stairs.Shape.INNER_LEFT:Stairs.Shape.INNER_RIGHT;
        return Stairs.Shape.STRAIGHT;
    }
    private static boolean differentStair(World world,BlockPos pos,BlockFace face,Stairs own){
        BlockData next=neighbor(world,pos,face);return !(next instanceof Stairs stairs) || stairs.getFacing()!=own.getFacing() || stairs.getHalf()!=own.getHalf();
    }
    private static BlockFace left(BlockFace face){return HORIZONTAL.get((HORIZONTAL.indexOf(face)+3)%4);}
}
