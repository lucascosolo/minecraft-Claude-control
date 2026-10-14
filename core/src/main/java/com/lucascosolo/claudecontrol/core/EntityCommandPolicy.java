package com.lucascosolo.claudecontrol.core;

import java.util.*;
import java.util.regex.Pattern;

/** Owner-only vanilla entity commands. Never accepts plugin, host or administrative dispatch. */
public final class EntityCommandPolicy {
    private static final Set<String> ROOTS=Set.of("summon","data","attribute","ride","tag","tp","teleport","effect","item","particle","playsound","stopsound","title","tellraw");
    private static final Pattern HAZARD_TYPE=Pattern.compile("(?i)(?<![a-z0-9_.-])(?:minecraft:)?(?:tnt(?:_minecart)?|fireball|small_fireball|dragon_fireball|wither_skull|wither|ender_?dragon|end_crystal|lightning(?:_bolt)?|falling_?block|command_?block(?:_minecart)?|minecart_command_block)(?![a-z0-9_.-])");
    private static final Pattern HAZARD_KEY=Pattern.compile("(?i)(?<![a-z0-9_.-])(?:command|run_command|click_event|clickevent|items|inventory|uuid(?:most|least)?|bukkitvalues|fuse|explosionpower|explosionradius|ignited|deathtime|deathlootTable)(?![a-z0-9_.-])");
    private static final Pattern HEALTH_KEY=Pattern.compile("(?i)(?<![a-z0-9_.-])[\\\"']?health[\\\"']?\\s*:\\s*");
    private static final Pattern POSITIVE_NUMBER=Pattern.compile("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?[fFdDbBsSlL]?(?=\\s*[,}])");
    private static final Pattern POWERED=Pattern.compile("(?i)(?<![a-z0-9_.-])[\\\"']?powered[\\\"']?\\s*:\\s*(?:0[bBsSlLfFdD]?|false)(?=\\s*[,}])");
    private static final Pattern POWERED_KEY=Pattern.compile("(?i)(?<![a-z0-9_.-])[\\\"']?powered[\\\"']?\\s*:\\s*");
    private EntityCommandPolicy(){}
    public static String require(String command,Set<String> additionalDenied){
        if(command==null || command.isBlank() || command.length()>4096 || command.indexOf('\\')>=0
            || command.chars().anyMatch(c->Character.isISOControl(c) || (Character.isWhitespace(c) && c!=' ')))throw refused("ENTITY_COMMAND_FORMAT_REFUSED");
        String text=command.strip();if(text.startsWith("/"))text=text.substring(1);
        if(HAZARD_TYPE.matcher(text).find() || HAZARD_KEY.matcher(text).find() || text.toLowerCase(Locale.ROOT).contains("claudecontrol:") || text.toLowerCase(Locale.ROOT).contains("instant_damage"))throw refused("ENTITY_COMMAND_DESTRUCTIVE_PAYLOAD_REFUSED");
        var charged=POWERED_KEY.matcher(text);while(charged.find()){var benign=POWERED.matcher(text).region(charged.start(),text.length());if(!benign.lookingAt())throw refused("ENTITY_COMMAND_DESTRUCTIVE_PAYLOAD_REFUSED");}
        var health=HEALTH_KEY.matcher(text);while(health.find()){
            var number=POSITIVE_NUMBER.matcher(text).region(health.end(),text.length());if(!number.lookingAt())throw refused("ENTITY_COMMAND_DEATH_PAYLOAD_REFUSED");
            try{double value=Double.parseDouble(number.group().replaceFirst("[fFdDbBsSlL]$",""));if(!Double.isFinite(value) || value<=0)throw refused("ENTITY_COMMAND_DEATH_PAYLOAD_REFUSED");}catch(NumberFormatException invalid){throw refused("ENTITY_COMMAND_DEATH_PAYLOAD_REFUSED");}
        }
        Set<String> denied=new HashSet<>();if(additionalDenied!=null)for(String name:additionalDenied){if(name!=null){String value=name.strip().toLowerCase(Locale.ROOT);int colon=value.lastIndexOf(':');denied.add(colon<0?value:value.substring(colon+1));}}
        return normalize(text,denied,0,1);
    }
    private record Word(String text,int start,int end){}
    private static List<Word> words(String command){
        List<Word> words=new ArrayList<>();Deque<Character> nesting=new ArrayDeque<>();char quote=0;int start=-1;
        for(int i=0;i<command.length();i++){
            char c=command.charAt(i);if(start<0){if(c==' ')continue;start=i;}
            if(quote!=0){if(c==quote)quote=0;continue;}
            if(c=='\'' || c=='"'){quote=c;continue;}
            if(c=='{' || c=='[')nesting.push(c);
            else if(c=='}' || c==']'){if(nesting.isEmpty() || nesting.pop()!=(c=='}'?'{':'['))throw refused("ENTITY_COMMAND_FORMAT_REFUSED");}
            else if(c==' ' && nesting.isEmpty()){words.add(new Word(command.substring(start,i),start,i));start=-1;}
        }
        if(quote!=0 || !nesting.isEmpty())throw refused("ENTITY_COMMAND_FORMAT_REFUSED");
        if(start>=0)words.add(new Word(command.substring(start),start,command.length()));return words;
    }
    private static String root(String token){
        String value=token.toLowerCase(Locale.ROOT);if(value.startsWith("minecraft:"))value=value.substring(10);
        if(!value.matches("[a-z_]+"))throw refused("ENTITY_COMMAND_NAMESPACE_REFUSED");return value;
    }
    private static String normalize(String text,Set<String> denied,int depth,int sources){
        List<Word> tokens=words(text);if(tokens.isEmpty())throw refused("ENTITY_COMMAND_FORMAT_REFUSED");String root=root(tokens.getFirst().text());
        if(denied.contains(root))throw refused("ENTITY_COMMAND_CONFIG_DENIED");
        if(root.equals("execute")){
            if(depth>=4)throw refused("ENTITY_COMMAND_WRAPPER_LIMIT");int run=-1;
            for(int i=1;i<tokens.size();i++){
                String clause=tokens.get(i).text().toLowerCase(Locale.ROOT);if(clause.startsWith("minecraft:"))clause=clause.substring(10);
                if(clause.equals("run")){run=i;break;}
                if(Set.of("store","summon","function","return").contains(clause))throw refused("ENTITY_COMMAND_EXECUTE_CLAUSE_REFUSED");
            }
            if(run<0 || run==tokens.size()-1)throw refused("ENTITY_COMMAND_TERMINAL_REQUIRED");
            for(int i=1;i<run;i++){
                String clause=tokens.get(i).text().toLowerCase(Locale.ROOT);
                if((clause.equals("as") || clause.equals("at")) && i+1<run)sources=Math.min(17,sources*sourceCount(tokens.get(i+1).text()));
                if(clause.equals("on"))sources=17; // Entity relations have no explicit bounded selector.
            }
            String prefix=text.substring(tokens.getFirst().end(),tokens.get(run).start());
            return "minecraft:execute"+prefix+"run "+normalize(text.substring(tokens.get(run+1).start()),denied,depth+1,sources);
        }
        if(!ROOTS.contains(root))throw refused("ENTITY_COMMAND_ROOT_REFUSED");
        if(tokens.size()<2)throw refused("ENTITY_COMMAND_ARGUMENTS_REQUIRED");
        if(root.equals("summon") && sources>16)throw refused("ENTITY_COMMAND_SUMMON_FANOUT_REFUSED");
        if(root.equals("data")){
            if(tokens.size()<4 || !Set.of("get","merge","modify","remove").contains(tokens.get(1).text().toLowerCase(Locale.ROOT)) || !tokens.get(2).text().equalsIgnoreCase("entity"))throw refused("ENTITY_COMMAND_ENTITY_TARGET_REQUIRED");
            for(Word word:tokens)if(Set.of("block","storage").contains(word.text().toLowerCase(Locale.ROOT)))throw refused("ENTITY_COMMAND_ENTITY_TARGET_REQUIRED");
            if(tokens.size()>4 && tokens.get(4).text().replace("\"", "").replace("'", "").equalsIgnoreCase("Health") && !tokens.get(1).text().equalsIgnoreCase("get")){
                if(tokens.size()!=8 || !tokens.get(1).text().equalsIgnoreCase("modify") || !tokens.get(5).text().equalsIgnoreCase("set") || !tokens.get(6).text().equalsIgnoreCase("value"))throw refused("ENTITY_COMMAND_DEATH_PAYLOAD_REFUSED");
                String value=tokens.get(7).text().replaceFirst("[fFdDbBsSlL]$","");try{if(!Double.isFinite(Double.parseDouble(value)) || Double.parseDouble(value)<=0)throw refused("ENTITY_COMMAND_DEATH_PAYLOAD_REFUSED");}catch(NumberFormatException invalid){throw refused("ENTITY_COMMAND_DEATH_PAYLOAD_REFUSED");}
            }
        }
        if(root.equals("item") && (tokens.size()<3 || !tokens.get(2).text().equalsIgnoreCase("entity")))throw refused("ENTITY_COMMAND_ENTITY_TARGET_REQUIRED");
        return "minecraft:"+root+text.substring(tokens.getFirst().end());
    }
    private static int sourceCount(String selector){
        String text=selector.toLowerCase(Locale.ROOT);
        if(!text.startsWith("@e") && !text.startsWith("@a"))return 1;
        if(text.length()<4 || text.charAt(2)!='[' || !text.endsWith("]"))return 17;
        String body=text.substring(3,text.length()-1);int depth=0,start=0;char quote=0;Integer limit=null;
        for(int i=0;i<=body.length();i++){
            char c=i==body.length()?',':body.charAt(i);
            if(quote!=0){if(c==quote)quote=0;continue;}
            if(c=='\'' || c=='"'){quote=c;continue;}
            if(c=='{' || c=='[')depth++;else if(c=='}' || c==']')depth--;
            if(c==',' && depth==0){String field=body.substring(start,i).strip();start=i+1;
                if(field.startsWith("limit")){
                    if(limit!=null || !field.matches("limit *=[ ]*[0-9]+"))return 17;
                    try{limit=Integer.parseInt(field.substring(field.indexOf('=')+1).strip());}catch(NumberFormatException invalid){return 17;}
                }
            }
        }
        return limit!=null && limit>=1 && limit<=16?limit:17;
    }
    private static IllegalArgumentException refused(String reason){return new IllegalArgumentException(reason);}
}
