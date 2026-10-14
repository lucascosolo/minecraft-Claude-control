package com.lucascosolo.claudecontrol.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildSiteSearchTest {
    @Test void candidateBudgetIncludesEveryVolumeAndSupportingFloorCell() {
        for(int[] size:List.of(new int[]{1,1,1},new int[]{16,16,16},new int[]{32,8,32},new int[]{32,1,32},new int[]{32,16,16})) {
            int width=size[0],height=size[1],depth=size[2];
            var candidates=BuildSiteSearch.candidates(0,64,0,32,8,width,height,depth);
            long visitsPerCandidate=(long)width*depth*(height+1);
            assertFalse(candidates.isEmpty());assertTrue(candidates.size()<=128);
            assertTrue(candidates.size()*visitsPerCandidate<=65536,
                       "Include floor checks rather than budgeting only empty build volume");
            assertEquals(candidates.size(),new HashSet<>(candidates).size());
        }
    }
    @Test void nearbyHorizontalAlternativesAndVerticalLevelsAreBothRepresented() {
        var candidates=BuildSiteSearch.candidates(10,64,-10,8,2,2,3,2);
        assertEquals(new BuildSiteSearch.Origin(10,64,-10),candidates.getFirst());
        assertTrue(candidates.stream().anyMatch(c->c.x()!=10||c.z()!=-10));
        assertTrue(candidates.stream().anyMatch(c->c.y()<64));
        assertTrue(candidates.stream().anyMatch(c->c.y()>64));
        for(var candidate:candidates) {
            assertTrue(Math.abs((long)candidate.x()-10)<=8);
            assertTrue(Math.abs((long)candidate.z()+10)<=8);
            assertTrue(Math.abs((long)candidate.y()-64)<=2);
        }
    }
    @Test void smallRadiusCandidatesStayLocalUniqueDeterministicAndImmutable() {
        for(int radius:List.of(1,2,3)) {
            var candidates=BuildSiteSearch.candidates(0,64,0,radius,0,1,1,1);
            Set<BuildSiteSearch.Origin> expected=new HashSet<>();
            for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++)expected.add(new BuildSiteSearch.Origin(x,64,z));
            assertEquals(expected,new HashSet<>(candidates));assertEquals(expected.size(),candidates.size());
            assertEquals(candidates,BuildSiteSearch.candidates(0,64,0,radius,0,1,1,1));
            assertThrows(UnsupportedOperationException.class,()->candidates.clear());
        }
    }
    @Test void arithmeticCannotWrapFarOriginsIntoApparentlyValidLocations() {
        for(int coordinate:List.of(Integer.MIN_VALUE,Integer.MAX_VALUE)) {
            assertTrue(BuildSiteSearch.candidates(coordinate,64,0,32,8,1,1,1).isEmpty());
            assertTrue(BuildSiteSearch.candidates(0,coordinate,0,32,8,1,1,1).isEmpty());
            assertTrue(BuildSiteSearch.candidates(0,64,coordinate,32,8,1,1,1).isEmpty());
        }
        var edge=BuildSiteSearch.candidates(29_999_984,2048,-29_999_984,8,2,1,1,1);
        assertFalse(edge.isEmpty());
        for(var candidate:edge) {
            assertTrue(Math.abs((long)candidate.x())<=29_999_984);
            assertTrue(Math.abs((long)candidate.z())<=29_999_984);
            assertTrue(Math.abs((long)candidate.y())<=2048);
        }
    }
    @Test void invalidSearchRadiusHeightAndVolumeFailClosed() {
        for(int radius:List.of(-1,0,33))assertThrows(IllegalArgumentException.class,()->BuildSiteSearch.candidates(0,64,0,radius,0,1,1,1));
        for(int vertical:List.of(-1,9))assertThrows(IllegalArgumentException.class,()->BuildSiteSearch.candidates(0,64,0,8,vertical,1,1,1));
        for(int[] size:List.of(new int[]{0,1,1},new int[]{1,0,1},new int[]{1,1,0},new int[]{33,1,1},new int[]{1,33,1},new int[]{1,1,33},new int[]{32,32,32})) {
            assertThrows(IllegalArgumentException.class,()->BuildSiteSearch.candidates(0,64,0,8,0,size[0],size[1],size[2]));
        }
    }
}
