package com.lucascosolo.claudecontrol.core;
import java.util.*;
public final class CommandPolicy {
    private final Set<String> denied=new HashSet<>(Set.of("op","deop","stop","reload","restart","ban","ban-ip","banlist","pardon","pardon-ip","kick","whitelist","save-off","save-all","save-on","execute","return","function","schedule","sudo","dispatch","eval","script","plugman","permissions","permission","lp","luckperms","minecraft:execute"));
    public CommandPolicy(Set<String> additional) { for(String s:additional) denied.add(base(s.toLowerCase(Locale.ROOT))); }
    public void check(String command, boolean privileged) {
        if(!privileged || !isAllowed(command)) throw new IllegalArgumentException("Console command blocked by local authorization policy");
    }
    private static String base(String s) { int i=s.lastIndexOf(':'); return i<0?s:s.substring(i+1); }
    public boolean isAllowed(String command) {
        if(command==null || command.isBlank() || command.length()>1024 || command.chars().anyMatch(Character::isISOControl)) return false;
        String c=command.strip().toLowerCase(Locale.ROOT); if(c.startsWith("/")) c=c.substring(1);
        String name=c.split(" ",2)[0]; if(!name.matches("[a-z0-9_.-]+(?::[a-z0-9_.-]+)?") || denied.contains(base(name))) return false;
        // Reject data/component payloads: these can carry commands, entities or unsafe items.
        if(c.contains("command_block") || c.contains("command:") || c.contains("{") || c.contains("[") || c.contains(";") || c.contains("\"")) return false;
        return Set.of("give","tp","teleport","time","weather","say","tell","msg","w","title","playsound","particle","effect","setblock","fill","gamemode","experience","xp","spawnpoint","setworldspawn").contains(base(name));
    }
}
