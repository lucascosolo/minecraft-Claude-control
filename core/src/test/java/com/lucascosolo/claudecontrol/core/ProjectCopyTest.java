package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectCopyTest {
    private JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private JsonObject definition() { return object("{\"id\":\"mansion-copy\",\"title\":\"Coherent rooms\",\"goal\":\"Copy reviewed rooms without resending every block\",\"layout\":\"Separate original and copied room footprints\",\"steps\":[]}"); }
    private JsonObject step(int id,int width,int height,int depth) {
        JsonObject step=object("{\"description\":\"Copy reviewed footprint\",\"tool\":\"copy_structure\",\"args\":{\"source\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0},\"destination\":{\"x\":64,\"y\":64,\"z\":0}}}");
        step.addProperty("id","copy-"+id);JsonObject source=step.getAsJsonObject("args").getAsJsonObject("source");
        source.addProperty("width",width);source.addProperty("height",height);source.addProperty("depth",depth);return step;
    }
    @Test void reviewedCopyStepsAreAcceptedAndGeometryValidationStillApplies() {
        JsonObject definition=definition();definition.getAsJsonArray("steps").add(step(0,3,2,4));
        assertEquals(definition,ProjectStore.validateDefinition(definition));
        definition.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonObject("args").getAsJsonObject("destination").addProperty("x",0);
        assertThrows(IllegalArgumentException.class,()->ProjectStore.validateDefinition(definition));
    }
    @Test void copyBudgetCountsCompleteFootprintsAndRejectsOneCellBeyondProjectLimit() {
        JsonObject definition=definition();for(int i=0;i<4;i++)definition.getAsJsonArray("steps").add(step(i,32,16,16));
        assertDoesNotThrow(()->ProjectStore.validateDefinition(definition));
        definition.getAsJsonArray("steps").add(step(4,1,1,1));
        assertThrows(IllegalArgumentException.class,()->ProjectStore.validateDefinition(definition));
    }
}
