package com.lucascosolo.claudecontrol.core;

import java.util.*;
import java.util.function.Predicate;

/** Bounded pure cardinal route; callers supply loaded, safe, same-level cells. */
public final class CompanionPlan {
    private CompanionPlan() {}
    public record Cell(int x,int z) {}
    public record WalkCell(int x,int y,int z) {}
    public static List<WalkCell> terrainRoute(WalkCell start,WalkCell target,
            Predicate<WalkCell> standing,Predicate<WalkCell> clearance) {
        Objects.requireNonNull(start);Objects.requireNonNull(target);
        if(Math.abs((long)target.x-start.x)>16 || Math.abs((long)target.z-start.z)>16
            || Math.abs((long)target.y-start.y)>8)return List.of();
        Map<WalkCell,Boolean> checked=new HashMap<>(),air=new HashMap<>();
        Predicate<WalkCell> safe=c->{
            if(Math.abs((long)c.x-start.x)>16 || Math.abs((long)c.z-start.z)>16 || Math.abs((long)c.y-start.y)>8)return false;
            if(checked.containsKey(c))return checked.get(c);
            if(checked.size()>=256)return false;
            boolean result=standing.test(c);checked.put(c,result);return result;
        };
        Predicate<WalkCell> clear=c->{
            if(air.containsKey(c))return air.get(c);
            if(air.size()>=256)return false;
            boolean result=clearance.test(c);air.put(c,result);return result;
        };
        if(!safe.test(start) || !safe.test(target) || start.equals(target))return List.of();
        record Entry(WalkCell cell,int cost,int score,long order) {}
        PriorityQueue<Entry> frontier=new PriorityQueue<>(Comparator.comparingInt(Entry::score).thenComparingLong(Entry::order));
        Map<WalkCell,Integer> costs=new HashMap<>();Map<WalkCell,WalkCell> parents=new HashMap<>();
        long order=0;costs.put(start,0);frontier.add(new Entry(start,0,0,order++));
        while(!frontier.isEmpty()) {
            Entry current=frontier.remove();if(current.cost!=costs.get(current.cell))continue;
            if(current.cell.equals(target)) {
                List<WalkCell> result=new ArrayList<>();WalkCell c=target;
                while(!c.equals(start)){result.add(c);c=parents.get(c);}
                Collections.reverse(result);return List.copyOf(result);
            }
            if(current.cost>=64)continue;
            for(int[] d:new int[][]{{1,0},{0,1},{-1,0},{0,-1}})for(int dy:new int[]{0,1,-1}) {
                WalkCell c=new WalkCell(current.cell.x+d[0],current.cell.y+dy,current.cell.z+d[1]);
                if(!safe.test(c))continue;
                if(dy==1 && (!clear.test(new WalkCell(current.cell.x,current.cell.y+2,current.cell.z))
                    || !clear.test(new WalkCell(c.x,c.y+2,c.z))))continue;
                if(dy==-1 && !clear.test(new WalkCell(c.x,current.cell.y+1,c.z)))continue;
                int cost=current.cost+1;if(cost>=costs.getOrDefault(c,Integer.MAX_VALUE))continue;
                costs.put(c,cost);parents.put(c,current.cell);
                int remaining=(int)(Math.abs((long)c.x-target.x)+Math.abs((long)c.z-target.z));
                frontier.add(new Entry(c,cost,cost+remaining,order++));
            }
        }
        return List.of();
    }
    private record Node(Cell cell,int cost,int priority,long order) {}
    /** Recomputed from current positions; never routes into the player's occupied cell. */
    public static List<Cell> companyRoute(Cell start,Cell player,Predicate<Cell> walkable) {
        Map<Cell,Boolean> inspected=new HashMap<>();
        Predicate<Cell> unoccupied=cell->{
            if(cell.equals(player))return false;
            if(inspected.containsKey(cell))return inspected.get(cell);
            if(inspected.size()>=256)return false;
            boolean safe=walkable.test(cell);inspected.put(cell,safe);return safe;
        };
        for(int[] offset:new int[][]{{2,0},{-2,0},{0,2},{0,-2}}) {
            List<Cell> path=route(start,new Cell(player.x+offset[0],player.z+offset[1]),unoccupied);
            if(!path.isEmpty())return path;
        }
        return List.of();
    }
    public static List<Cell> route(Cell start,Cell target,Predicate<Cell> walkable) {
        Objects.requireNonNull(start);Objects.requireNonNull(target);Objects.requireNonNull(walkable);
        if(Math.abs((long)target.x-start.x)>16 || Math.abs((long)target.z-start.z)>16)return List.of();
        Map<Cell,Boolean> inspected=new HashMap<>();
        java.util.function.Predicate<Cell> safe=cell->{
            if(Math.abs((long)cell.x-start.x)>16 || Math.abs((long)cell.z-start.z)>16)return false;
            if(inspected.containsKey(cell))return inspected.get(cell);
            if(inspected.size()>=256)return false;
            boolean result=walkable.test(cell);inspected.put(cell,result);return result;
        };
        if(!safe.test(start) || !safe.test(target) || start.equals(target))return List.of();
        PriorityQueue<Node> frontier=new PriorityQueue<>(Comparator.comparingInt(Node::priority).thenComparingLong(Node::order));
        Map<Cell,Integer> distance=new HashMap<>();Map<Cell,Cell> parent=new HashMap<>();
        long order=0;distance.put(start,0);frontier.add(new Node(start,0,manhattan(start,target),order++));
        while(!frontier.isEmpty()) {
            Node current=frontier.remove();if(current.cost!=distance.get(current.cell))continue;
            if(current.cell.equals(target)) {
                List<Cell> path=new ArrayList<>();Cell cell=target;
                while(!cell.equals(start)){path.add(cell);cell=parent.get(cell);}
                Collections.reverse(path);return path.size()<=64?List.copyOf(path):List.of();
            }
            if(current.cost>=64)continue;
            for(int[] delta:new int[][]{{1,0},{0,1},{-1,0},{0,-1}}) {
                Cell next=new Cell(current.cell.x+delta[0],current.cell.z+delta[1]);
                if(!safe.test(next))continue;int cost=current.cost+1;
                if(cost>=distance.getOrDefault(next,Integer.MAX_VALUE))continue;
                distance.put(next,cost);parent.put(next,current.cell);
                frontier.add(new Node(next,cost,cost+manhattan(next,target),order++));
            }
        }
        return List.of();
    }
    private static int manhattan(Cell a,Cell b){return (int)(Math.abs((long)a.x-b.x)+Math.abs((long)a.z-b.z));}
}
