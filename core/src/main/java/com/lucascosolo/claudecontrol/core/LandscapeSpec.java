package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;

/** Exact surveyed natural-cell edits. Returns a batch only after every cell passes. */
public record LandscapeSpec(String world,String surveyId,List<Edit> edits) {
    public record Edit(int x,int y,int z,String expectedMaterial,String material){}
    public record Snapshot(String material,boolean protectedState,boolean livingEntity,boolean loaded){}
    private static final Set<String> EARTH=Set.of("DIRT","GRASS_BLOCK","COARSE_DIRT","ROOTED_DIRT","STONE","ANDESITE","DIORITE","GRANITE","DEEPSLATE","TUFF","CALCITE","SAND","RED_SAND");
    private static final Set<String> VEGETATION=Set.of("SHORT_GRASS","TALL_GRASS","FERN","LARGE_FERN","DEAD_BUSH","SNOW");
    private static final Set<String> AIR=Set.of("AIR","CAVE_AIR","VOID_AIR");
    public static boolean natural(String material){return material!=null && (EARTH.contains(material) || VEGETATION.contains(material) || AIR.contains(material));}
    public LandscapeSpec {
        if(world==null || world.isBlank() || world.length()>128 || world.chars().anyMatch(Character::isISOControl) || surveyId==null || !surveyId.matches("[a-f0-9-]{36}") || edits==null || edits.isEmpty() || edits.size()>512)throw new IllegalArgumentException("Landscape requires an exact survey and 1..512 edits");
        Set<SurveySpec.Cell> positions=new HashSet<>();
        for(Edit edit:edits){
            if(edit==null || edit.material()==null || Math.abs((long)edit.x())>29_999_984 || Math.abs((long)edit.z())>29_999_984 || Math.abs((long)edit.y())>2048 || !positions.add(new SurveySpec.Cell(edit.x(),edit.y(),edit.z())) || !natural(edit.expectedMaterial()) || !(EARTH.contains(edit.material()) || edit.material().equals("AIR")))throw new IllegalArgumentException("Landscape edits must be distinct, bounded natural earth or vegetation, with natural fill or AIR removal");
        }
        edits=List.copyOf(edits);
    }
    public static LandscapeSpec parse(JsonObject args){
        try {
            if(args==null)throw new IllegalArgumentException("Landscape arguments required");
            ExperienceSpec.keys(args,Set.of("world","survey_id","operations"));JsonArray operations=ExperienceSpec.array(args,"operations");if(operations.isEmpty() || operations.size()>512)throw new IllegalArgumentException("Landscape requires 1..512 edits");
            List<Edit> edits=new ArrayList<>();for(JsonElement element:operations){JsonObject edit=element.getAsJsonObject();ExperienceSpec.keys(edit,Set.of("x","y","z","expected_material","material"));edits.add(new Edit(BuildSpec.integer(edit,"x"),BuildSpec.integer(edit,"y"),BuildSpec.integer(edit,"z"),ExperienceSpec.text(edit,"expected_material",71,true),ExperienceSpec.text(edit,"material",71,true)));}
            return new LandscapeSpec(ExperienceSpec.text(args,"world",128,true),ExperienceSpec.text(args,"survey_id",36,true),edits);
        }catch(RuntimeException invalid){if(invalid instanceof IllegalArgumentException exception)throw exception;throw new IllegalArgumentException("Invalid landscape batch");}
    }
    public List<Edit> preflight(Function<Edit,Snapshot> surveyed,Function<Edit,Snapshot> current){
        if(surveyed==null || current==null)throw new IllegalArgumentException("Survey and live preflight required");
        for(Edit edit:edits){
            Snapshot receipt=surveyed.apply(edit),live=current.apply(edit);
            if(receipt==null || live==null || !receipt.loaded() || !live.loaded() || receipt.protectedState() || live.protectedState() || receipt.livingEntity() || live.livingEntity()
                || !edit.expectedMaterial().equals(receipt.material()) || !edit.expectedMaterial().equals(live.material()))throw new IllegalArgumentException("Landscape refused at "+edit.x()+","+edit.y()+","+edit.z()+": missing/stale exact survey, protected block or living entity");
        }
        return edits;
    }
}
