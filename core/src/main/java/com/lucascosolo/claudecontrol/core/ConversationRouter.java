package com.lucascosolo.claudecontrol.core;

import java.util.*;
import java.util.regex.Pattern;

/** Short local conversation continuations. Routing never supplies identity, authority or consent. */
public final class ConversationRouter {
    private static final long ANSWER_WINDOW=120_000,FOLLOWUP_WINDOW=60_000;
    private static final int REPLY_CHARS=160,REPLY_WORDS=16;
    private static final Pattern QUESTION_CUE=Pattern.compile("\\b(?:tell me|let me know|do you prefer|would you prefer)\\b|(?:^|[.!:\\n]\\s*)(?:which|choose|select)\\b");
    private static final Pattern DIRECTION=Pattern.compile("(?:the )?(?:north|south|east|west|northeast|northwest|southeast|southwest)(?: (?:wall|side|corner|entrance|end|edge))?");
    private static final Pattern SPATIAL=Pattern.compile("\\b(?:where|direction|side|wall|corner|entrance|position|location|place|door)\\b");
    private static final Pattern DIRECT=Pattern.compile("(?:make it|paint it|build it|change it to|switch it to|move it to|put it on|put it at|use instead) [a-z0-9]+(?:[ -][a-z0-9]+){0,5}");
    private static final Set<String> ANSWERS=Set.of("yes","yes please","yep","yup","yeah","sure","sure go ahead","go","go ahead",
        "please do","do it","let's do it","lets do it","proceed","continue","carry on","ok","okay","sounds good","i agree",
        "yes go ahead","yes do it","yes proceed","yes continue","go for it","please continue","please proceed",
        "no","no thanks","no thank you","nope","nah","not now","stop","wait","hold on","cancel","never mind","nevermind","don't do it","no don't do that",
        "that one","first","second","third","the first","the second","the third","first one","second one","third one");
    private static final Set<String> FILLER=Set.of("a","an","the","please","one","option","i","we","me","us","you","your","our","my",
        "it","its","this","that","these","those","do","does","did","would","could","should","can","will","shall","want","like",
        "prefer","choose","select","tell","let","know","to","for","of","with","in","on","at","by","from","or","and","be",
        "is","are","was","were","have","has","which","what","where","when","how","whether","instead","use","make");
    private record Question(UUID actor,boolean privateReply,long at,Set<String> options,boolean spatial,String text) {}
    private Question question;
    private UUID lastActor;
    private long lastSpoken=-1;
    private String lastDelivered="",matchedContext="";
    private UUID matchedActor;

    public ConversationRouter() {}

    public synchronized void spoken(UUID actor,String deliveredMessage,boolean privateReply,long nowMillis) {
        if(actor==null || deliveredMessage==null || deliveredMessage.isBlank() || deliveredMessage.length()>4096
            || nowMillis<0 || nowMillis<lastSpoken) {clear();return;}
        lastActor=actor;lastSpoken=nowMillis;
        lastDelivered=deliveredMessage.strip().substring(0,Math.min(1200,deliveredMessage.strip().length()));
        matchedActor=null;matchedContext="";
        String message=deliveredMessage.strip().toLowerCase(Locale.ROOT).replace('\u2019','\'');
        int end=message.lastIndexOf('?');
        boolean asked=end>=0 || QUESTION_CUE.matcher(message).find();
        if(!asked)return;
        if(end>=0) {
            int start=Math.max(Math.max(message.lastIndexOf('.',end),message.lastIndexOf('!',end)),message.lastIndexOf('\n',end));
            message=message.substring(start+1,end);
        }
        Set<String> options=meaningfulWords(message);
        question=new Question(actor,privateReply,nowMillis,Set.copyOf(options),SPATIAL.matcher(message).find(),lastDelivered);
    }

    public synchronized boolean reply(UUID actor,String message,long nowMillis) {
        if(!preview(actor,message,nowMillis))return false;
        consume(actor);return true;
    }

    /** Evaluate before queue admission; a rejected submission leaves the answer available. */
    public synchronized boolean preview(UUID actor,String message,long nowMillis) {
        matchedActor=null;matchedContext="";
        if(actor==null || message==null || nowMillis<0 || lastSpoken<0 || nowMillis<lastSpoken
            || message.isBlank() || message.length()>REPLY_CHARS)return false;
        String text=message.strip().toLowerCase(Locale.ROOT).replace('\u2019','\'').replaceAll("[.!?,;:]+$","").strip();
        if(text.isEmpty() || text.split("\\s+").length>REPLY_WORDS || text.indexOf('\n')>=0 || text.indexOf('\r')>=0)return false;
        Question pending=question;
        if(pending!=null && nowMillis>=pending.at && nowMillis-pending.at<=ANSWER_WINDOW
            && (!pending.privateReply || pending.actor.equals(actor)) && answer(text,pending)) {
            matchedActor=actor;matchedContext=pending.text;
            return true;
        }
        if(actor.equals(lastActor) && nowMillis-lastSpoken<=FOLLOWUP_WINDOW && DIRECT.matcher(text).matches()) {
            matchedActor=actor;matchedContext=lastDelivered;
            return true;
        }
        return false;
    }

    /** Commit only the latest successful preview after its actual actor's request was admitted. */
    public synchronized void consume(UUID actor) {
        if(actor==null || !actor.equals(matchedActor) || matchedContext.isEmpty())return;
        question=null;lastActor=null;lastSpoken=-1;lastDelivered="";
    }

    private static boolean answer(String text,Question pending) {
        if(ANSWERS.contains(text) || text.matches("(?:option )?[1-3]"))return true;
        if(pending.spatial && DIRECTION.matcher(text).matches())return true;
        if(!text.matches("[a-z0-9 '-]+"))return false;
        Set<String> words=meaningfulWords(text);
        return !words.isEmpty() && words.size()<=5 && pending.options.containsAll(words);
    }

    private static Set<String> meaningfulWords(String text) {
        Set<String> words=new HashSet<>();
        for(String word:text.split("[^a-z0-9]+"))if(!word.isEmpty() && !FILLER.contains(word))words.add(word);
        return words;
    }

    /** Actual delivered speech as context data only; the runtime retains all identity checks. */
    public synchronized String replyContext(UUID actor) {return actor!=null && actor.equals(matchedActor)?matchedContext:"";}

    public synchronized void clear() {question=null;lastActor=null;lastSpoken=-1;lastDelivered="";matchedActor=null;matchedContext="";}
}
