package com.lucascosolo.claudecontrol.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationRouterTest {
    private final UUID owner=UUID.randomUUID(),other=UUID.randomUUID();
    private final long now=1_000;
    @Test void shortAnswersToPublicQuestionsRouteOnceForTheActualSpeaker() {
        for(String answer:List.of("yes","go","yes please","no thanks","continue")) {
            ConversationRouter router=new ConversationRouter();router.spoken(owner,"Should I continue with the hall?",false,now);
            assertTrue(router.reply(other,answer,now+1),answer);
            assertFalse(router.reply(owner,answer,now+2),"One public answer must consume the pending question");
        }
    }
    @Test void privateQuestionsRemainBoundToOriginalActorWithoutConsumingOnOtherChat() {
        ConversationRouter router=new ConversationRouter();router.spoken(owner,"Should I use cherry for your room?",true,now);
        assertFalse(router.reply(other,"yes",now+1));assertFalse(router.reply(other,"cherry",now+2));
        assertTrue(router.reply(owner,"yes",now+3));assertFalse(router.reply(owner,"yes",now+4));
    }
    @Test void directionalAndRelevantOptionAnswersNeedTheMatchingQuestionContext() {
        ConversationRouter router=new ConversationRouter();router.spoken(owner,"Which wall should have the entrance?",false,now);
        assertTrue(router.reply(other,"north wall",now+1));
        router.spoken(owner,"Would you prefer oak or cherry?",false,now+2);assertTrue(router.reply(owner,"cherry",now+3));
        router.spoken(owner,"Choose oak or cherry for the trim.",false,now+4);assertTrue(router.reply(owner,"oak",now+5));
        router.spoken(owner,"Which wood should I use?",false,now+6);assertFalse(router.reply(owner,"north wall",now+7));
    }
    @Test void ordinaryUnrelatedChatDoesNotRouteOrConsumeAQuestion() {
        ConversationRouter router=new ConversationRouter();
        assertFalse(router.reply(owner,"yes",now));router.spoken(owner,"Should I make the entrance on the north wall?",false,now);
        for(String chat:List.of("hello everyone","I found diamonds","meet me by the river","nice weather today")) {
            assertFalse(router.reply(other,chat,now+1),chat);
        }
        assertTrue(router.reply(owner,"yes",now+2));
        router.spoken(owner,"The foundation inspection is complete.",false,now+3);
        assertFalse(router.reply(owner,"yes",now+4));assertFalse(router.reply(other,"go",now+4));
    }
    @Test void questionWindowIncludesTwoMinutesButRejectsExpiredOrBackwardTime() {
        ConversationRouter router=new ConversationRouter();router.spoken(owner,"Should I continue?",false,now);
        assertFalse(router.reply(owner,"yes",now-1));assertTrue(router.reply(owner,"yes",now+120_000));
        router.spoken(owner,"Should I continue?",false,now+200_000);
        assertFalse(router.reply(owner,"yes",now+320_001));
        router.spoken(owner,"Should I continue?",false,now+400_000);
        router.spoken(owner,"Should I continue?",false,now+399_999);
        assertFalse(router.reply(owner,"yes",now+400_001),"A rolled-back spoken clock invalidates pending routing");
    }
    @Test void directFollowupsHaveOneMinuteWindowAndOnlyOriginalSpeakerCanContinue() {
        ConversationRouter router=new ConversationRouter();router.spoken(owner,"The room plan is ready.",false,now);
        assertFalse(router.reply(other,"make it cherry",now+1));assertTrue(router.reply(owner,"make it cherry",now+60_000));
        assertFalse(router.reply(owner,"make it cherry",now+60_001));
        router.spoken(owner,"The room plan is ready.",false,now+100_000);
        assertFalse(router.reply(owner,"make it cherry",now+160_001));
    }
    @Test void longMultilineOrMalformedRepliesDoNotBypassShortLocalRouting() {
        ConversationRouter router=new ConversationRouter();router.spoken(owner,"Should I continue?",false,now);
        for(String invalid:List.of("x".repeat(161),"yes "+"please ".repeat(17),"yes\nplease","yes\rplease",""," ")) {
            assertFalse(router.reply(owner,invalid,now+1));
        }
        assertFalse(router.reply(null,"yes",now+1));assertFalse(router.reply(owner,null,now+1));assertFalse(router.reply(owner,"yes",-1));
        assertTrue(router.reply(owner,"yes",now+2));
    }
    @Test void routedContextKeepsActualDeliveredQuestionForActualReplyingActorOnly() {
        ConversationRouter router=new ConversationRouter();String question="Which wall should have the entrance?";
        router.spoken(owner,question,false,now);assertTrue(router.reply(other,"north wall",now+1));
        assertEquals(question,router.replyContext(other));assertEquals("",router.replyContext(owner));assertEquals("",router.replyContext(null));
        assertFalse(router.reply(other,"north wall",now+2));assertEquals("",router.replyContext(other));
        String statement="The room plan is ready.";router.spoken(owner,statement,false,now+3);
        assertTrue(router.reply(owner,"make it cherry",now+4));assertEquals(statement,router.replyContext(owner));
        router.clear();assertEquals("",router.replyContext(owner));
    }
    @Test void narrativeWhichDoesNotOpenAnAnswerWindowAndContextIsBounded() {
        ConversationRouter router=new ConversationRouter();router.spoken(owner,"I checked which blocks are loaded and recorded the terrain.",false,now);
        assertFalse(router.reply(owner,"yes",now+1));
        String question="Should I continue with the hall? "+"x".repeat(1800);
        router.spoken(owner,question,false,now+2);assertTrue(router.reply(owner,"yes",now+3));
        assertFalse(router.replyContext(owner).isEmpty());assertTrue(router.replyContext(owner).length()<=1200);
    }
    @Test void previewRetainsPendingAnswerWhenSubmissionFailsAndConsumeCommitsItOnce() {
        ConversationRouter router=new ConversationRouter();String question="Should I continue with the hall?";
        router.spoken(owner,question,false,now);
        assertTrue(router.preview(other,"yes",now+1));assertEquals(question,router.replyContext(other));
        // No consume represents a rejected queue admission; the same answer remains available.
        assertTrue(router.preview(other,"yes",now+2));assertEquals(question,router.replyContext(other));
        router.consume(other);
        assertFalse(router.preview(other,"yes",now+3));assertFalse(router.reply(owner,"yes",now+4));
    }
    @Test void onlyMatchedActorCanConsumePrivatePreviewAndUnmatchedConsumeCannotDropQuestion() {
        ConversationRouter router=new ConversationRouter();String question="Should I use cherry in your room?";
        router.spoken(owner,question,true,now);router.consume(owner);
        assertTrue(router.preview(owner,"yes",now+1));
        router.consume(other);router.consume(null);
        assertEquals(question,router.replyContext(owner));assertTrue(router.preview(owner,"yes",now+2));
        router.consume(owner);assertFalse(router.preview(owner,"yes",now+3));
        assertFalse(router.preview(other,"yes",now+3));
    }
    @Test void directFollowupPreviewAlsoWaitsForSuccessfulAdmissionBeforeConsumption() {
        ConversationRouter router=new ConversationRouter();String statement="The room plan is ready.";
        router.spoken(owner,statement,false,now);
        assertTrue(router.preview(owner,"make it cherry",now+1));assertEquals(statement,router.replyContext(owner));
        assertTrue(router.preview(owner,"make it cherry",now+2));
        router.consume(owner);assertFalse(router.preview(owner,"make it cherry",now+3));
    }
    @Test void newDeliveredQuestionInvalidatesOldAdmissionMatchWithoutLosingNewQuestion() {
        ConversationRouter router=new ConversationRouter();router.spoken(owner,"Should I use oak?",true,now);
        assertTrue(router.preview(owner,"yes",now+1));
        router.spoken(owner,"Should I use cherry instead?",true,now+2);router.consume(owner);
        assertTrue(router.preview(owner,"yes",now+3));assertEquals("Should I use cherry instead?",router.replyContext(owner));
    }
    @Test void clearRemovesBothPendingQuestionsAndStatementFollowups() {
        ConversationRouter router=new ConversationRouter();router.spoken(owner,"Should I continue?",false,now);router.clear();
        assertFalse(router.reply(owner,"yes",now+1));assertFalse(router.reply(owner,"make it cherry",now+1));
        router.spoken(owner,"The plan is ready.",false,now+2);router.clear();assertFalse(router.reply(owner,"make it cherry",now+3));
    }
}
