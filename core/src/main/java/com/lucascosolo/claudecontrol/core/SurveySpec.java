package com.lucascosolo.claudecontrol.core;

import com.google.gson.JsonObject;
import java.util.*;

/** Every cell in a bounded footprint, in x/y/z order. No terrain sampling. */
public record SurveySpec(String world,int x,int y,int z,int width,int height,int depth) {
    public record Cell(int x,int y,int z){}
    public SurveySpec {
        if(world==null || world.isBlank() || world.length()>128 || world.chars().anyMatch(Character::isISOControl)
            || width<1 || width>32 || height<1 || height>32 || depth<1 || depth>32 || (long)width*height*depth>8192
            || Math.abs((long)x)>29_999_984 || Math.abs((long)z)>29_999_984 || Math.abs((long)y)>2048
            || (long)x+width-1>29_999_984 || (long)z+depth-1>29_999_984 || (long)y+height-1>2048)throw new IllegalArgumentException("Survey requires a bounded exact volume of at most 8192 cells");
    }
    public static SurveySpec parse(JsonObject args){
        if(args==null)throw new IllegalArgumentException("Survey arguments required");
        ExperienceSpec.keys(args,Set.of("world","x","y","z","width","height","depth"));
        return new SurveySpec(ExperienceSpec.text(args,"world",128,true),BuildSpec.integer(args,"x"),BuildSpec.integer(args,"y"),BuildSpec.integer(args,"z"),BuildSpec.integer(args,"width"),BuildSpec.integer(args,"height"),BuildSpec.integer(args,"depth"));
    }
    public List<Cell> cells(){List<Cell> cells=new ArrayList<>(width*height*depth);for(int dx=0;dx<width;dx++)for(int dy=0;dy<height;dy++)for(int dz=0;dz<depth;dz++)cells.add(new Cell(x+dx,y+dy,z+dz));return List.copyOf(cells);}
}
