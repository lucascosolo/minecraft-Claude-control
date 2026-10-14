package com.lucascosolo.claudecontrol.core;

/** Local expression admission. World checks and the requested activity always take priority. */
final class CompanionWhimsy {
    private CompanionWhimsy(){}
    static boolean allowed(boolean keepsCompany,boolean paused,boolean busy,boolean moving,
                           boolean playerMoving,double distanceSquared,long now,long lastExpression) {
        return keepsCompany && !paused && !busy && !moving && !playerMoving
            && Double.isFinite(distanceSquared) && distanceSquared>=0 && distanceSquared<=64
            && now>=lastExpression && now-lastExpression>=25_000;
    }
}
