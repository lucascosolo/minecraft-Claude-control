package com.lucascosolo.claudecontrol.core;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NearbyConversationTest {
    @Test void observationsRespectWorldCurrentPermissionAndExpiry(){
        var buffer=new NearbyConversation();UUID lucas=UUID.randomUUID(),hannah=UUID.randomUUID();
        buffer.hear(lucas,"Lucas","world","I love libraries",1000);
        buffer.hear(hannah,"Hannah","nether","I love cherry trees",1000);
        assertEquals(1,buffer.snapshot("world",1001,id->true).size());
        assertEquals(0,buffer.snapshot("world",1001,id->!id.equals(lucas)).size());
        assertEquals(1,buffer.snapshot("world",3_601_000,id->true).size());
        assertEquals(0,buffer.snapshot("world",3_601_001,id->true).size());
    }
    @Test void boundedHistoryKeepsNewestSpeechAndSanitizesControlCharacters(){
        var buffer=new NearbyConversation();UUID speaker=UUID.randomUUID();
        for(int i=0;i<30;i++)buffer.hear(speaker,"Lucas","world","message "+i,1000+i);
        var items=buffer.snapshot("world",1030,id->true);assertEquals(24,items.size());
        assertEquals("message 6",items.get(0).getAsJsonObject().get("message").getAsString());
        buffer.hear(speaker,"Lucas","world","x".repeat(300)+"\n",1031);
        assertEquals(240,buffer.snapshot("world",1032,id->true).get(23).getAsJsonObject().get("message").getAsString().length());
    }
    @Test void clockRollbackAndClearDiscardStaleObservations(){
        var buffer=new NearbyConversation();UUID speaker=UUID.randomUUID();
        buffer.hear(speaker,"Lucas","world","yes",1000);
        assertEquals(0,buffer.snapshot("world",999,id->true).size());
        buffer.hear(speaker,"Lucas","world","go",1001);buffer.clear();
        assertEquals(0,buffer.snapshot("world",1002,id->true).size());
    }
}
