package com.lucascosolo.claudecontrol;

import static com.lucascosolo.claudecontrol.TestJson.j;
import static com.lucascosolo.claudecontrol.TestJson.rejects;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

// Assumed: static ContainerSpec ContainerSpec.parse(JsonNode) (IllegalArgumentException; message contains the limit number / key / action)
//   String action() read|fill|add|clear;  PlaceExpr at() (must be absolute {world,x,y,z}: a {near} place is rejected);
//   List<ItemSpec> items() (empty for read/clear);  String create() null | "chest" | "barrel";  String name() through ChatText.plain, or null
//   String refusal(Authority a)  null when allowed; otherwise a SuiteGates-style refusal (SuiteGates.item / enchant) for the first
//   restricted item or over-limit enchantment, naming the item id; privileged authorities are allowed
// fill and add need 1..27 stacks (an entry counts ceil(count/64) stacks); read and clear take no items.
class ContainerSpecTest {
    private static final String AT = "'at':{'world':'world','x':10,'y':64,'z':-3}";
    private static final Authority PLAYER = new Authority(List.of("Sam"), false, false, false);
    private static final Authority OWNER = new Authority(List.of("Sam"), true, false, false);

    private static String items(int n) {
        return IntStream.range(0, n).mapToObj(i -> "{'id':'cobblestone','count':1}").reduce((a, b) -> a + "," + b).orElse("");
    }

    @Test void readAndClearNeedNoItems() {
        assertEquals("read", ContainerSpec.parse(j("{'action':'read'," + AT + "}")).action());
        ContainerSpec c = ContainerSpec.parse(j("{'action':'clear'," + AT + "}"));
        assertEquals("clear", c.action());
        assertEquals(List.of(), c.items());
        assertEquals("world", c.at().world());
        assertNull(c.create());
    }

    @Test void fillAndAddParseItems() {
        ContainerSpec f = ContainerSpec.parse(j("{'action':'fill'," + AT + ",'items':[{'id':'diamond','count':3},{'id':'bread'}],'create':'chest','name':'Loot'}"));
        assertEquals(List.of("diamond", "bread"), f.items().stream().map(ItemSpec::id).toList());
        assertEquals("chest", f.create());
        assertEquals("Loot", f.name());
        assertEquals("add", ContainerSpec.parse(j("{'action':'add'," + AT + ",'items':[{'id':'bread'}],'create':'barrel'}")).action());
    }

    @Test void unknownActionAndCreateRejected() {
        rejects(() -> ContainerSpec.parse(j("{'action':'explode'," + AT + "}")), "explode");
        rejects(() -> ContainerSpec.parse(j("{'action':'fill'," + AT + ",'items':[{'id':'bread'}],'create':'furnace'}")), "furnace");
        rejects(() -> ContainerSpec.parse(j("{'action':'fill'," + AT + ",'items':[{'id':'bread'}],'create':'shulker_box'}")));
    }

    @Test void atMustBeAbsolute() {
        rejects(() -> ContainerSpec.parse(j("{'action':'read','at':{'near':'Sam'}}")));
        rejects(() -> ContainerSpec.parse(j("{'action':'read'}")));
    }

    @Test void stackLimit() {
        assertEquals(27, ContainerSpec.parse(j("{'action':'fill'," + AT + ",'items':[" + items(27) + "]}")).items().size());
        rejects(() -> ContainerSpec.parse(j("{'action':'fill'," + AT + ",'items':[" + items(28) + "]}")), "27");
        rejects(() -> ContainerSpec.parse(j("{'action':'add'," + AT + ",'items':[{'id':'cobblestone','count':1792}]}")), "27");
    }

    @Test void itemsRequiredForFillAddAndForbiddenForReadClear() {
        rejects(() -> ContainerSpec.parse(j("{'action':'fill'," + AT + "}")));
        rejects(() -> ContainerSpec.parse(j("{'action':'add'," + AT + ",'items':[]}")));
        rejects(() -> ContainerSpec.parse(j("{'action':'read'," + AT + ",'items':[{'id':'bread'}]}")));
        rejects(() -> ContainerSpec.parse(j("{'action':'fill'," + AT + ",'items':[{'id':'sword{Damage:1}'}]}")));
    }

    @Test void unknownKeysRejectedAndNameIsPlain() {
        rejects(() -> ContainerSpec.parse(j("{'action':'read'," + AT + ",'nbt':'{}'}")), "nbt");
        assertEquals("Loot", ContainerSpec.parse(j("{'action':'fill'," + AT + ",'items':[{'id':'bread'}],'name':'Loot 🎁'}")).name());
    }

    @Test void restrictedItemsRefusedWithoutAnOwner() {
        ContainerSpec c = ContainerSpec.parse(j("{'action':'fill'," + AT + ",'items':[{'id':'bread'},{'id':'command_block'}]}"));
        String r = c.refusal(PLAYER);
        assertNotNull(r);
        assertTrue(r.contains("command_block"), r);
        assertNull(c.refusal(OWNER));
        assertNotNull(ContainerSpec.parse(j("{'action':'add'," + AT + ",'items':[{'id':'zombie_spawn_egg'}]}")).refusal(PLAYER));
        assertNull(ContainerSpec.parse(j("{'action':'add'," + AT + ",'items':[{'id':'bread'}]}")).refusal(PLAYER));
    }

    @Test void overLevelEnchantmentsRefusedWithoutAnOwner() {
        ContainerSpec c = ContainerSpec.parse(j("{'action':'fill'," + AT + ",'items':[{'id':'diamond_sword','enchantments':{'sharpness':9}}]}"));
        assertNotNull(c.refusal(PLAYER));
        assertNull(c.refusal(OWNER));
    }
}
