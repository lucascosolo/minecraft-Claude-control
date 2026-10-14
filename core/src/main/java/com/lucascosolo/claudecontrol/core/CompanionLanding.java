package com.lucascosolo.claudecontrol.core;

import java.util.*;

/** Landing policy is separate from restrictions on creating blocks. Never changes terrain. */
public final class CompanionLanding {
    public record Cell(int x,int y,int z){}
    private static final Set<String> EXISTING_GROUND=Set.of("SAND","RED_SAND","GRAVEL");
    private static final Set<String> UNSAFE=Set.of("WITHER_ROSE","HONEY_BLOCK","SLIME_BLOCK",
        "POINTED_DRIPSTONE","DRIPSTONE_BLOCK","ICE","PACKED_ICE","BLUE_ICE","FROSTED_ICE");
    private CompanionLanding(){}
    public static boolean safeGround(String material){
        return material!=null && (EXISTING_GROUND.contains(material) || BuildSpec.safeMaterial(material))
            && !UNSAFE.contains(material);
    }
    public static List<Cell> candidates(int x,int y,int z){
        List<Cell> cells=new ArrayList<>();
        for(int dx=-8;dx<=8;dx++)for(int dz=-8;dz<=8;dz++){
            if(dx==0 && dz==0)continue;
            for(int dy=-3;dy<=3;dy++){
                long cx=(long)x+dx,cy=(long)y+dy,cz=(long)z+dz;
                if(Math.abs(cx)>29_999_984 || Math.abs(cz)>29_999_984 || Math.abs(cy)>2048)continue;
                cells.add(new Cell((int)cx,(int)cy,(int)cz));
            }
        }
        cells.sort(Comparator.comparingLong(c->{long dx=(long)c.x()-x,dy=(long)c.y()-y,dz=(long)c.z()-z;return dx*dx+dz*dz+4*dy*dy;}));
        return List.copyOf(cells);
    }
}
