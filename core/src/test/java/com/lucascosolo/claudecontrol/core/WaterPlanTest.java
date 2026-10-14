package com.lucascosolo.claudecontrol.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class WaterPlanTest {
    private CompanionPlan.Cell cell(int x,int z) { return new CompanionPlan.Cell(x,z); }
    private void cardinalRoute(CompanionPlan.Cell start,List<CompanionPlan.Cell> route,Predicate<CompanionPlan.Cell> water) {
        assertTrue(route.size()<=64);assertFalse(route.contains(start));
        CompanionPlan.Cell previous=start;
        for(var next:route) {
            assertEquals(1,Math.abs(next.x()-previous.x())+Math.abs(next.z()-previous.z()));
            assertTrue(water.test(next));previous=next;
        }
    }
    @Test void crossesLoadedSurfaceWaterUsingCardinalStepsOnly() {
        var start=cell(0,0);var target=cell(4,0);
        Predicate<CompanionPlan.Cell> water=c->c.z()==0 && c.x()>=0 && c.x()<=4;
        var route=WaterPlan.route(start,target,water);
        assertEquals(List.of(cell(1,0),cell(2,0),cell(3,0),cell(4,0)),route);
        cardinalRoute(start,route,water);
    }
    @Test void routesAroundObstructionThroughLoadedWaterWithoutEnteringBlockedCell() {
        var start=cell(0,0);var target=cell(4,0);var obstacle=cell(2,0);
        Predicate<CompanionPlan.Cell> water=c->c.x()>=0 && c.x()<=4 && c.z()>=0 && c.z()<=1 && !c.equals(obstacle);
        var route=WaterPlan.route(start,target,water);
        assertFalse(route.isEmpty());assertEquals(target,route.getLast());assertFalse(route.contains(obstacle));
        cardinalRoute(start,route,water);
    }
    @Test void unloadedWaterHeadroomFailureAndDryShorelineCannotBeForced() {
        var start=cell(0,0);var target=cell(2,0);
        Predicate<CompanionPlan.Cell> loadedWater=c->c.equals(start) || c.equals(target);
        assertTrue(WaterPlan.route(start,target,loadedWater).isEmpty());
        Predicate<CompanionPlan.Cell> dryTarget=c->c.z()==0 && c.x()>=0 && c.x()<2;
        assertTrue(WaterPlan.route(start,target,dryTarget).isEmpty());
        assertTrue(WaterPlan.route(start,target,c->false).isEmpty());
        assertTrue(WaterPlan.route(start,start,c->true).isEmpty());
    }
    @Test void diagonalOnlyWaterDoesNotCreateDiagonalMovement() {
        var start=cell(0,0);var target=cell(1,1);
        assertTrue(WaterPlan.route(start,target,c->c.equals(start)||c.equals(target)).isEmpty());
        Set<CompanionPlan.Cell> water=Set.of(start,cell(1,0),target);
        var route=WaterPlan.route(start,target,water::contains);
        assertEquals(List.of(cell(1,0),target),route);cardinalRoute(start,route,water::contains);
    }
    @Test void queryCountIsBoundedCachedAndConfinedToLocalRadius() {
        var start=cell(0,0);Map<CompanionPlan.Cell,Integer> queries=new HashMap<>();
        var route=WaterPlan.route(start,cell(16,16),c->{
            assertTrue(Math.abs((long)c.x())<=16);assertTrue(Math.abs((long)c.z())<=16);
            queries.merge(c,1,Integer::sum);return true;
        });
        assertTrue(queries.size()<=256);assertTrue(queries.values().stream().allMatch(count->count==1));
        cardinalRoute(start,route,c->true);
        int[] outsideQueries={0};
        for(var target:List.of(cell(17,0),cell(0,-17),cell(Integer.MAX_VALUE,0))) {
            assertTrue(WaterPlan.route(start,target,c->{outsideQueries[0]++;return true;}).isEmpty());
        }
        assertEquals(0,outsideQueries[0]);
    }
    @Test void longWindingWaterRouteCannotExceedMovementBudget() {
        Set<CompanionPlan.Cell> water=new HashSet<>();
        for(int z=0;z<=8;z+=2) {
            for(int x=0;x<=14;x++)water.add(cell(x,z));
            if(z<8)water.add(cell((z/2)%2==0?14:0,z+1));
        }
        assertTrue(WaterPlan.route(cell(0,0),cell(14,8),water::contains).isEmpty());
    }
    @Test void propulsionUsesOneAxisAndCapsSpeedWithoutOvershooting() {
        for(double[] delta:List.of(new double[]{8,4},new double[]{-8,4},new double[]{0,8},new double[]{0,-8},new double[]{0.1,0},new double[]{0,0.1},new double[]{0,0})) {
            var motion=WaterPlan.velocity(delta[0],delta[1]);
            assertTrue(Double.isFinite(motion.x()));assertTrue(Double.isFinite(motion.z()));
            assertTrue(Math.abs(motion.x())<=0.4);assertTrue(Math.abs(motion.z())<=0.4);
            assertTrue(motion.x()==0 || motion.z()==0);
            assertTrue(Math.abs(motion.x())<=Math.abs(delta[0]));assertTrue(Math.abs(motion.z())<=Math.abs(delta[1]));
            if(motion.x()!=0)assertEquals(Math.signum(delta[0]),Math.signum(motion.x()));
            if(motion.z()!=0)assertEquals(Math.signum(delta[1]),Math.signum(motion.z()));
        }
        assertEquals(0.4,WaterPlan.velocity(8,4).x());assertEquals(0,WaterPlan.velocity(8,4).z());
    }
    @Test void nonfinitePropulsionInputsFailClosed() {
        for(double value:List.of(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class,()->WaterPlan.velocity(value,0));
            assertThrows(IllegalArgumentException.class,()->WaterPlan.velocity(0,value));
        }
    }
}
