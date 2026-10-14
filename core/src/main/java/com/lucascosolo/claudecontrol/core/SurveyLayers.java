package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import java.util.List;

/** Exact horizontal maps of a small survey, using the same indices as its state palette. */
final class SurveyLayers {
    private static final String LEGEND="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz+-";
    static JsonObject encode(int width,int height,int depth,List<Integer> indices){
        if(indices==null || width<1 || width>32 || depth<1 || depth>32 || height<1 || height>8
            || (long)width*height*depth>2048 || indices.size()!=width*height*depth)
            throw new IllegalArgumentException("Bounded complete survey layers required");
        for(Integer index:indices)if(index==null || index<0 || index>=LEGEND.length())throw new IllegalArgumentException("Layer palette index out of bounds");
        JsonArray layers=new JsonArray();
        for(int y=0;y<height;y++){
            JsonArray rows=new JsonArray();
            for(int z=0;z<depth;z++){
                StringBuilder row=new StringBuilder();for(int x=0;x<width;x++)row.append(LEGEND.charAt(indices.get((x*height+y)*depth+z)));
                rows.add(row.toString());
            }layers.add(rows);
        }
        JsonObject out=new JsonObject();out.addProperty("legend",LEGEND);out.add("rows",layers);
        out.addProperty("order","Layers ascend y from origin; rows ascend z (north to south); characters ascend x (west to east). Character position in legend is palette/block_states index. Exact cells, not a sketch.");
        return out;
    }
}
