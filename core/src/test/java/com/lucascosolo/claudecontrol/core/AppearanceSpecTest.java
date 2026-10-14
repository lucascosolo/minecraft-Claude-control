package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AppearanceSpecTest {
    private JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    @Test void defaultsAreHatterAndEmptyAccountAndPartialUpdatesRetainOtherPreference() {
        AppearanceSpec defaults=AppearanceSpec.defaults();assertEquals("",defaults.account());assertEquals("hatter",defaults.preset());
        AppearanceSpec current=new AppearanceSpec("TownOwner","explorer");
        assertEquals(new AppearanceSpec("New_Player","explorer"),AppearanceSpec.parse(object("{\"action\":\"appearance\",\"account\":\"New_Player\"}"),current));
        assertEquals(new AppearanceSpec("TownOwner","plain"),AppearanceSpec.parse(object("{\"preset\":\"plain\"}"),current));
        assertEquals(new AppearanceSpec("","explorer"),AppearanceSpec.parse(object("{\"account\":\"\"}"),current));
    }
    @Test void nativeAccountNamesAndAllThreePresetsAreAllowedWithinBoundaries() {
        for(String account:List.of("A","TownOwner_123","x".repeat(16),""))for(String preset:List.of("plain","hatter","explorer")) {
            JsonObject input=new JsonObject();input.addProperty("account",account);input.addProperty("preset",preset);
            assertEquals(new AppearanceSpec(account,preset),AppearanceSpec.parse(input,AppearanceSpec.defaults()));
        }
    }
    @Test void malformedAccountsPresetsActionsAndExtraFieldsCannotSupplyRemoteOrIdentityData() {
        for(String account:List.of("x".repeat(17),"two names","name-with-dash","§aOwner","https://example.invalid/skin","owner\n")) {
            JsonObject input=new JsonObject();input.addProperty("account",account);assertThrows(IllegalArgumentException.class,()->AppearanceSpec.parse(input,AppearanceSpec.defaults()));
            assertThrows(IllegalArgumentException.class,()->new AppearanceSpec(account,"plain"));
        }
        for(String preset:List.of("HATTER","unknown","","plain\n"))assertThrows(IllegalArgumentException.class,()->new AppearanceSpec("Owner",preset));
        assertThrows(IllegalArgumentException.class,()->AppearanceSpec.parse(object("{\"action\":\"summon\",\"preset\":\"plain\"}"),AppearanceSpec.defaults()));
        for(String field:List.of("uuid","texture","url","command","entity_id")) {
            JsonObject input=object("{\"preset\":\"plain\"}");input.addProperty(field,"untrusted");assertThrows(IllegalArgumentException.class,()->AppearanceSpec.parse(input,AppearanceSpec.defaults()));
        }
    }
    @Test void presentValuesMustBeStringsAndAChangeIsRequired() {
        for(String field:List.of("action","account","preset"))for(JsonElement value:List.of(new JsonPrimitive(1),new JsonPrimitive(true),JsonNull.INSTANCE,new JsonArray(),new JsonObject())) {
            JsonObject input=object("{\"preset\":\"plain\"}");input.add(field,value);assertThrows(IllegalArgumentException.class,()->AppearanceSpec.parse(input,AppearanceSpec.defaults()));
        }
        assertThrows(IllegalArgumentException.class,()->AppearanceSpec.parse(new JsonObject(),AppearanceSpec.defaults()));
        assertThrows(IllegalArgumentException.class,()->AppearanceSpec.parse(object("{\"action\":\"appearance\"}"),AppearanceSpec.defaults()));
        assertThrows(IllegalArgumentException.class,()->new AppearanceSpec(null,"plain"));assertThrows(IllegalArgumentException.class,()->new AppearanceSpec("Owner",null));
    }
    @Test void jsonSnapshotsAndCallerInputsCannotMutateImmutablePreferences() {
        JsonObject input=object("{\"account\":\"TownOwner\",\"preset\":\"hatter\"}"),before=input.deepCopy();
        AppearanceSpec spec=AppearanceSpec.parse(input,AppearanceSpec.defaults());assertEquals(before,input);
        JsonObject snapshot=spec.json();snapshot.addProperty("account","attacker");input.addProperty("preset","plain");
        assertEquals("TownOwner",spec.account());assertEquals("hatter",spec.preset());assertEquals(before,spec.json());
    }
}
