package com.lucascosolo.claudecontrol.core;

import java.util.*;
import java.util.function.Predicate;

/** Bounded pure cardinal route; callers supply loaded, safe, same-level cells. */
public final class CompanionPlan {
    private CompanionPlan() {}
    public record Cell(int x,int z) {}
    private record Node(Cell cell,int cost,int priority,long order) {}
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
