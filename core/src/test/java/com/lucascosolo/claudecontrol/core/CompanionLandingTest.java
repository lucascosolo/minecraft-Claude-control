package com.lucascosolo.claudecontrol.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompanionLandingTest {
    @Test void existingBeachAndGravelGroundPermitLandingWithoutAllowingPlacement() {
        for(String ground:List.of("SAND","RED_SAND","GRAVEL")) {
            assertTrue(CompanionLanding.safeGround(ground),"Existing solid "+ground+" is safe ground");
            assertFalse(BuildSpec.safeMaterial(ground),"Landing must not weaken falling-block placement restrictions");
        }
        for(String ground:List.of("STONE","DIRT","GRASS_BLOCK","OAK_PLANKS"))assertTrue(CompanionLanding.safeGround(ground),ground);
    }
    @Test void contactHazardsControlBlocksAndMalformedMaterialsRemainRefused() {
        for(String ground:List.of("MAGMA_BLOCK","CACTUS","CAMPFIRE","SOUL_CAMPFIRE","POWDER_SNOW",
            "SWEET_BERRY_BUSH","WITHER_ROSE","POINTED_DRIPSTONE","DRIPSTONE_BLOCK","ICE","PACKED_ICE",
            "BLUE_ICE","FROSTED_ICE","HONEY_BLOCK","SLIME_BLOCK","TNT","COMMAND_BLOCK","CHAIN_COMMAND_BLOCK",
            "REPEATING_COMMAND_BLOCK","STRUCTURE_BLOCK","JIGSAW","LAVA","FIRE","OAK_PRESSURE_PLATE","", "sand")) {
            assertFalse(CompanionLanding.safeGround(ground),ground);
        }
        assertFalse(CompanionLanding.safeGround(null));
    }
    @Test void searchRetainsLegacySpotsAndAddsDiagonalAndVerticalAlternatives() {
        int x=10,y=64,z=-10;var candidates=CompanionLanding.candidates(x,y,z);
        for(int[] offset:List.of(new int[]{2,0},new int[]{-2,0},new int[]{0,2},new int[]{0,-2},new int[]{3,0},new int[]{0,3})) {
            assertTrue(candidates.contains(new CompanionLanding.Cell(x+offset[0],y,z+offset[1])));
        }
        assertTrue(candidates.contains(new CompanionLanding.Cell(x+1,y,z+1)));
        assertTrue(candidates.contains(new CompanionLanding.Cell(x+1,y+1,z)));
        assertTrue(candidates.contains(new CompanionLanding.Cell(x+1,y-1,z)));
        assertTrue(candidates.stream().anyMatch(c->Math.abs(c.x()-x)==8||Math.abs(c.z()-z)==8));
    }
    @Test void candidatesAreBoundedUniqueImmutableAndExcludeActorsEntireColumn() {
        int x=10,y=64,z=-10;var candidates=CompanionLanding.candidates(x,y,z);
        assertFalse(candidates.isEmpty());assertTrue(candidates.size()<=(17*17-1)*7);
        assertEquals(candidates.size(),new HashSet<>(candidates).size());
        for(var cell:candidates) {
            assertTrue(Math.abs((long)cell.x()-x)<=8);assertTrue(Math.abs((long)cell.z()-z)<=8);
            assertTrue(Math.abs((long)cell.y()-y)<=3);assertFalse(cell.x()==x&&cell.z()==z);
        }
        assertEquals(candidates,CompanionLanding.candidates(x,y,z));
        assertThrows(UnsupportedOperationException.class,()->candidates.clear());
    }
    @Test void farCoordinatesNeverOverflowIntoValidWorldAndEdgeCandidatesStayBounded() {
        for(int value:List.of(Integer.MIN_VALUE,Integer.MAX_VALUE)) {
            assertTrue(CompanionLanding.candidates(value,64,0).isEmpty());
            assertTrue(CompanionLanding.candidates(0,value,0).isEmpty());
            assertTrue(CompanionLanding.candidates(0,64,value).isEmpty());
        }
        var edge=CompanionLanding.candidates(29_999_984,2048,-29_999_984);assertFalse(edge.isEmpty());
        for(var cell:edge) {
            assertTrue(Math.abs((long)cell.x())<=29_999_984);assertTrue(Math.abs((long)cell.z())<=29_999_984);
            assertTrue(Math.abs((long)cell.y())<=2048);
        }
    }
}
