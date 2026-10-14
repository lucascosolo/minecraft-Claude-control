package com.lucascosolo.claudecontrol.core;

/** Pure local catch-up policy; callers still verify identity and loaded safe landing. */
public final class CompanionPresence {
    private CompanionPresence() {}
    public static boolean catchUp(boolean sameWorld,double distanceSquared,boolean routeFound,long now,long previous) {
        if(!Double.isFinite(distanceSquared) || distanceSquared<0 || now<previous || now-previous<15_000)return false;
        return !sameWorld || distanceSquared>24*24 || (!routeFound && distanceSquared>9);
    }
}
