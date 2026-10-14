package com.lucascosolo.claudecontrol.core;

import java.util.*;

/** Deterministic nearby origins; total full-volume plus floor visits never exceeds budget. */
public final class BuildSiteSearch {
    public record Origin(int x,int y,int z){}
    private BuildSiteSearch(){}
    public static List<Origin> candidates(int x,int y,int z,int radius,int verticalRange,int width,int height,int depth){
        if(radius<1 || radius>32 || verticalRange<0 || verticalRange>8 || width<1 || width>32 || height<1 || height>32 || depth<1 || depth>32 || (long)width*height*depth>8192)throw new IllegalArgumentException("Invalid bounded site search");
        int limit=Math.min(128,65536/(width*depth*(height+1)));List<Origin> choices=new ArrayList<>();
        // Interleave horizontal alternatives and vertical levels instead of exhausting one column.
        List<int[]> offsets=new ArrayList<>();offsets.add(new int[]{0,0});
        for(int r=4;r<=radius;r+=4)for(int dx=-r;dx<=r;dx+=4)for(int dz=-r;dz<=r;dz+=4)if(Math.abs(dx)==r || Math.abs(dz)==r)offsets.add(new int[]{dx,dz});
        if(radius<4)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)if(dx!=0 || dz!=0)offsets.add(new int[]{dx,dz});
        for(int distance=0;distance<=radius+verticalRange*2 && choices.size()<limit;distance++)for(int[] offset:offsets)for(int dy=-verticalRange;dy<=verticalRange;dy++){
            if(choices.size()>=limit)return List.copyOf(choices);
            if(Math.max(Math.abs(offset[0]),Math.abs(offset[1]))+Math.abs(dy)*2!=distance)continue;
            long cx=(long)x+offset[0],cy=(long)y+dy,cz=(long)z+offset[1];
            if(Math.abs(cx)>29_999_984 || Math.abs(cz)>29_999_984 || Math.abs(cy)>2048)continue;
            choices.add(new Origin((int)cx,(int)cy,(int)cz));if(choices.size()==limit)break;
        }
        return List.copyOf(choices);
    }
}
