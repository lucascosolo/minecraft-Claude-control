package com.lucascosolo.claudecontrol.core;

import com.google.gson.JsonObject;
import java.util.*;

/** Compact geometry only; authorization and world protection remain runtime checks. */
public record CopySpec(String world,int x,int y,int z,int width,int height,int depth,
                       int destinationX,int destinationY,int destinationZ) {
    public record Cell(int sourceX,int sourceY,int sourceZ,int destinationX,int destinationY,int destinationZ) {}
    public static CopySpec parse(JsonObject input) {
        ExperienceSpec.keys(input,Set.of("source","destination"));
        if(!input.has("source") || !input.get("source").isJsonObject()
            || !input.has("destination") || !input.get("destination").isJsonObject())
            throw new IllegalArgumentException("Source and destination objects required");
        JsonObject source=input.getAsJsonObject("source"),destination=input.getAsJsonObject("destination");
        ExperienceSpec.keys(source,Set.of("world","x","y","z","width","height","depth"));
        ExperienceSpec.keys(destination,Set.of("x","y","z"));
        String world=ExperienceSpec.text(source,"world",128,true);
        if(world.isBlank())throw new IllegalArgumentException("World name required");
        return new CopySpec(world,BuildSpec.integer(source,"x"),BuildSpec.integer(source,"y"),BuildSpec.integer(source,"z"),
            BuildSpec.integer(source,"width"),BuildSpec.integer(source,"height"),BuildSpec.integer(source,"depth"),
            BuildSpec.integer(destination,"x"),BuildSpec.integer(destination,"y"),BuildSpec.integer(destination,"z"));
    }
    public CopySpec {
        if(world==null || world.isBlank() || world.length()>128)throw new IllegalArgumentException("World name required");
        if(width<1 || width>32 || height<1 || height>32 || depth<1 || depth>32 || (long)width*height*depth>8192)
            throw new IllegalArgumentException("Copy must fit 32 blocks per side and 8192 cells total");
        bounds(x,y,z,width,height,depth);bounds(destinationX,destinationY,destinationZ,width,height,depth);
        if(overlap(x,width,destinationX,width) && overlap(y,height,destinationY,height) && overlap(z,depth,destinationZ,depth))
            throw new IllegalArgumentException("Source and destination volumes cannot overlap");
    }
    private static void bounds(int x,int y,int z,int width,int height,int depth) {
        if(x < -29_999_984 || (long)x+width-1>29_999_984 || z < -29_999_984 || (long)z+depth-1>29_999_984
            || y < -2048 || (long)y+height-1>2048)throw new IllegalArgumentException("Copy volume out of bounds");
    }
    private static boolean overlap(int a,int size,int b,int otherSize){return (long)a+size>b && (long)b+otherSize>a;}
    public int volume(){return width*height*depth;}
    public List<Cell> cells(){
        List<Cell> cells=new ArrayList<>(volume());
        for(int dx=0;dx<width;dx++)for(int dy=0;dy<height;dy++)for(int dz=0;dz<depth;dz++)
            cells.add(new Cell(x+dx,y+dy,z+dz,destinationX+dx,destinationY+dy,destinationZ+dz));
        return List.copyOf(cells);
    }
}
