package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The single list of game tool definitions; the API tool params and the MCP tools/list are both rendered from it. */
public final class ToolCatalog {
    public record Tool(String name, String description, JsonNode inputSchema) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<Tool> tools;

    private ToolCatalog(List<Tool> tools) { this.tools = List.copyOf(tools); }

    public static ToolCatalog of(List<Tool> tools) { return new ToolCatalog(tools); }

    public List<Tool> tools() { return tools; }

    public List<String> names() { return tools.stream().map(Tool::name).toList(); }

    private static final String PLACE = "A place: {near: player, offset?: {x,y,z}, scatter?: {radius, min_radius?, height?: [min,max], on_ground?, in_view?}} "
            + "or {world, x, y, z, scatter?}. Resolved when the work runs, so near follows the player.";
    private static final String ITEMS = "Items are {id, count, name?, lore?: [text], enchantments?: {id: level}, unbreakable?}; no NBT strings.";
    private static final String CALLS = "Calls are {tool, args} with tool one of spawn, show, player, build, entities";

    public static ToolCatalog game() {
        return of(List.of(
                tool("run_commands",
                        "Run one or more server console commands (op-level authority) in order, without a leading slash. "
                                + "Use this for commands that change the world or players: give, tp, effect, summon, setblock, fill, kill, time, weather. "
                                + "Each command's output is returned; read it and adapt if a command failed or was denied. "
                                + "Never place chests or barrels with setblock or fill: use container with create, or build. "
                                + "Commands that read the world (execute if block, data get) do not work on this server, and neither do @e/@n "
                                + "selectors with distance or x/y/z, or execute at/positioned with @e: use the entities tool for mobs near a place.",
                        obj("commands", obj("type", "array", "items", obj("type", "string"),
                                "description", "Commands to run, in order. Later commands still run if an earlier one fails."))),
                tool("build",
                        "Place a whole structure in one call, straight through the block API. The box is width (east, +x) by depth "
                                + "(south, +z) by height (up), each 1 to 16. ops (1 to 64) use local coordinates from 0 inside the box and "
                                + "apply in order; hollow places only the cuboid's outer shell. origin null picks the flattest free ground near "
                                + "near_player, away from water and existing builds, and its floor replaces the top ground block; otherwise "
                                + "origin is the box's absolute minimum corner. clear sets the box to air first. teleport_to (local) moves "
                                + "near_player there afterwards; message is broadcast once every op succeeded. The result gives the absolute "
                                + "origin and, per op, the blocks placed or the error. Example, a sealed, lit 7x7x5 shelter: "
                                + "{\"near_player\":\"Alex\",\"width\":7,\"depth\":7,\"height\":5,\"origin\":null,\"clear\":true,\"ops\":["
                                + "{\"block\":\"cobblestone\",\"from\":[0,0,0],\"to\":[6,4,6],\"hollow\":true},"
                                + "{\"block\":\"oak_door[half=lower,facing=south]\",\"from\":[3,1,0],\"to\":[3,1,0],\"hollow\":false},"
                                + "{\"block\":\"oak_door[half=upper,facing=south]\",\"from\":[3,2,0],\"to\":[3,2,0],\"hollow\":false},"
                                + "{\"block\":\"torch\",\"from\":[1,1,1],\"to\":[1,1,1],\"hollow\":false},"
                                + "{\"block\":\"torch\",\"from\":[5,1,5],\"to\":[5,1,5],\"hollow\":false},"
                                + "{\"block\":\"red_bed[part=foot,facing=east]\",\"from\":[4,1,3],\"to\":[4,1,3],\"hollow\":false},"
                                + "{\"block\":\"red_bed[part=head,facing=east]\",\"from\":[5,1,3],\"to\":[5,1,3],\"hollow\":false}],"
                                + "\"teleport_to\":[2,1,3],\"message\":\"You're safe inside, Alex.\"}",
                        obj("near_player", obj("type", "string", "description", "Online player whose world and surroundings are used, and who teleport_to moves."),
                                "width", obj("type", "integer", "description", "Box size east (+x), 1 to 16."),
                                "depth", obj("type", "integer", "description", "Box size south (+z), 1 to 16."),
                                "height", obj("type", "integer", "description", "Box size up (+y), 1 to 16."),
                                "origin", obj("anyOf", List.of(obj("type", "object",
                                                "properties", obj("x", obj("type", "integer"), "y", obj("type", "integer"), "z", obj("type", "integer")),
                                                "required", List.of("x", "y", "z"), "additionalProperties", false), obj("type", "null")),
                                        "description", "Absolute minimum corner of the box, or null to pick a site automatically."),
                                "clear", obj("type", "boolean", "description", "Set the whole box to air first."),
                                "ops", obj("type", "array", "items", obj("type", "object",
                                                "properties", obj("block", obj("type", "string", "description", "Block state, e.g. oak_stairs[facing=east]."),
                                                        "from", obj("type", "array", "items", obj("type", "integer"), "description", "Local [x,y,z]."),
                                                        "to", obj("type", "array", "items", obj("type", "integer"), "description", "Local [x,y,z]."),
                                                        "hollow", obj("type", "boolean", "description", "Only the outer shell of the cuboid.")),
                                                "required", List.of("block", "from", "to", "hollow"), "additionalProperties", false),
                                        "description", "Block operations, applied in order."),
                                "teleport_to", obj("anyOf", List.of(obj("type", "array", "items", obj("type", "integer")), obj("type", "null")),
                                        "description", "Local [x,y,z] to move near_player to after placing, or null."),
                                "message", obj("anyOf", List.of(obj("type", "string"), obj("type", "null")),
                                        "description", "Chat message broadcast after success, or null."))),
                tool("survey_area",
                        "Look at the terrain before building or placing anything. Returns the ground height and surface material of every column "
                                + "in a square around (x, z), mobs inside it, and up to 3 flat sites of the requested footprint, nearest the centre first.",
                        obj("world", obj("type", "string", "description", "World name."),
                                "x", obj("type", "integer", "description", "Centre block x."),
                                "z", obj("type", "integer", "description", "Centre block z."),
                                "radius", obj("type", "integer", "description", "Half-width of the square, 1 to 12."),
                                "footprint", obj("type", "integer", "description", "Side of the flat site wanted, 3 to 15."))),
                tool("teleport_player",
                        "Teleport an online player to the centre of an exact block.",
                        obj("player", obj("type", "string", "description", "Exact player name."),
                                "world", obj("type", "string", "description", "World name."),
                                "x", obj("type", "integer", "description", "Block x."),
                                "y", obj("type", "integer", "description", "Block y (feet)."),
                                "z", obj("type", "integer", "description", "Block z."))),
                loose("entities",
                        "List, remove, give an effect to, or set traits on entities near a player or a block, on the server thread that owns that place. "
                                + "set: traits {attributes: {scale, max_health, movement_speed, attack_damage, jump_strength, gravity, step_height, ...}, "
                                + "glowing, silent, no_gravity, no_ai, invisible, on_fire, persistent, can_pick_up_items, collidable, invulnerable, name, name_visible, "
                                + "baby, health, color, variant, profession, level, size, sheared, saddled, sitting, angry, charged, explosion_radius, tamed_by, "
                                + "leashed_to, riding, passenger, target, equipment, velocity {x,y,z}, facing {yaw,pitch}} on at most 64 chosen by types or ids; "
                                + "seconds (1 to 3600) puts the old values back after that. Traits that do not fit a type are skipped and listed; undo restores. "
                                + "list with detail: true returns each entity's current trait values. "
                                + "Players are never targeted. remove skips named and tamed entities unless include_named is true. "
                                + "Returns counts per type, and how many were skipped.",
                        obj("near", obj("anyOf", List.of(obj("type", "string"), obj("type", "object",
                                                "properties", obj("world", obj("type", "string"), "x", obj("type", "integer"), "y", obj("type", "integer"),
                                                        "z", obj("type", "integer")),
                                                "required", List.of("world", "x", "y", "z"), "additionalProperties", false)),
                                        "description", "Exact online player name, or a block {world, x, y, z}."),
                                "radius", obj("type", "integer", "description", "1 to 64 blocks."),
                                "types", obj("type", "array", "items", obj("type", "string"),
                                        "description", "Entity ids (zombie, minecraft:spider) or groups: hostile, animals, items, all_mobs."),
                                "action", obj("type", "string", "enum", List.of("list", "remove", "effect", "set")),
                                "ids", obj("type", "array", "items", obj("type", "string"), "description", "Entity ids (UUIDs) to choose, instead of or with types."),
                                "traits", obj("type", "object", "description", "For set: the traits to give."),
                                "seconds", obj("type", "integer", "description", "For set: 1 to 3600; the old values come back after this."),
                                "detail", obj("type", "boolean", "description", "For list: include each entity's current trait values."),
                                "effect", obj("anyOf", List.of(obj("type", "object",
                                                "properties", obj("type", obj("type", "string", "description", "Effect id, e.g. slowness."),
                                                        "seconds", obj("type", "integer", "description", "1 to 600."),
                                                        "amplifier", obj("type", "integer", "description", "0 to 9 (0 is level I).")),
                                                "required", List.of("type", "seconds", "amplifier"), "additionalProperties", false), obj("type", "null")),
                                        "description", "For action effect; null otherwise."),
                                "limit", obj("type", "integer", "description", "At most this many entities, 1 to 200."),
                                "include_named", obj("type", "boolean", "description", "Let remove take named and tamed entities too.")),
                        "near", "radius", "action"),
                tool("send_chat",
                        "Say something in the game chat. This is the only way players see your words; your plain reply text is never shown. "
                                + "Use it when a player addressed you or should be told something.",
                        obj("message", obj("type", "string", "description", "The chat message."),
                                "player", obj("anyOf", List.of(obj("type", "string"), obj("type", "null")),
                                        "description", "Online player to whisper to, or null to broadcast to everyone."))),
                tool("get_player_details",
                        "Get full details for one online player: position, world, gamemode, health, food, xp level, held item, "
                                + "inventory summary and active effects. Call it before acting on a player when the snapshot is not enough.",
                        obj("player", obj("type", "string", "description", "Exact player name."))),
                loose("spawn",
                        "One burst of mobs or items, once. For anything that keeps happening (rain, showers, waves, something for N minutes or that follows players) use routine instead of repeating spawn. Spawn mobs or dropped items at a place, on the thread that owns it. Returns a group id. Use lifetime_seconds for anything "
                                + "temporary: it is removed on time, even across a restart, unless a player tamed or named it. " + ITEMS,
                        obj("type", obj("type", "string", "description", "Entity id, e.g. cat, or item for dropped items."),
                                "at", obj("type", "object", "description", PLACE),
                                "count", obj("type", "integer", "description", "1 to 30, default 1."),
                                "spread", obj("type", "integer", "description", "Blocks of random spread around the place, 0 to 16."),
                                "height_offset", obj("type", "integer", "description", "Blocks above the place, 0 to 40."),
                                "lifetime_seconds", obj("type", "integer", "description", "0 to 1800; 0 means permanent."),
                                "name", obj("type", "string"), "baby", obj("type", "boolean"), "tamed_by", obj("type", "string", "description", "Player name."),
                                "equipment", obj("type", "object", "description", "Slot (head, chest, legs, feet, mainhand, offhand) to item."),
                                "effects", obj("type", "array", "description", "[{type, seconds, amplifier?}]"),
                                "invulnerable_seconds", obj("type", "integer", "description", "0 to 600."),
                                "items", obj("type", "array", "description", "For type item: the items to drop."),
                                "traits", obj("type", "object", "description", "Same traits as entities set (attributes, glowing, color, variant, size, ...)."),
                                "landing", obj("type", "string", "enum", List.of("soft", "bounce", "vanish"),
                                        "description", "Falling mobs: soft (no fall damage), bounce, or vanish in a puff after landing.")),
                        "type", "at"),
                loose("cover",
                        "Plant or scatter blocks on the ground around a place (flowers, mushrooms, snow, pumpkins, tall plants): \"put X everywhere\" means this, not build. "
                                + "Loaded chunks only, at most 1500 blocks, never on containers, graves, water or leaves unless named in on. undo takes it back.",
                        obj("at", obj("type", "object", "description", PLACE),
                                "radius", obj("type", "integer", "description", "1 to 32."),
                                "blocks", obj("type", "array", "description", "1 to 12 entries {id, weight?} (weight 1 to 100, default 1), e.g. poppy, dandelion, sunflower."),
                                "density", obj("type", "number", "description", "Chance per ground column, 0.01 to 1."),
                                "on", obj("type", "array", "description", "Optional ground block ids to restrict to, e.g. [\"grass_block\"]."),
                                "lifetime_seconds", obj("type", "integer", "description", "0 to 1800; 0 means permanent. Removes what is still unchanged.")),
                        "at", "radius", "blocks", "density"),
                loose("routine",
                        "Repeat suite calls every few ticks with no model involved, following players or at a place, for up to 600 seconds. "
                                + "Use the rain shorthand for 'make it rain X': {label, follow or at, seconds, rain: {what: [{type | item, weight?}], "
                                + "per_second <= 10, radius <= 24, height 8..40, cloud?, landing: soft|bounce|vanish, lifetime_seconds <= 120}}. "
                                + "General form: {label, follow | at, seconds, every_ticks >= 2, do: [{tool, args, chance?, weight?}], max_alive <= 150, "
                                + "on_end?}; in args near \"$player\" means each followed player. At most 3 run at once. {list: true} shows them, "
                                + "{stop: label} ends one early; everything they spawned is removed at the end.",
                        obj("label", obj("type", "string"), "follow", obj("description", "Player names, or \"all\"."),
                                "at", obj("type", "object", "description", PLACE + " Use instead of follow."),
                                "seconds", obj("type", "integer"), "every_ticks", obj("type", "integer"), "do", obj("type", "array"),
                                "max_alive", obj("type", "integer"), "on_end", obj("type", "array"), "rain", obj("type", "object"),
                                "list", obj("type", "boolean"), "stop", obj("type", "string", "description", "Label of the routine to end."))),
                loose("player",
                        "Act on one online player in one call, on their own thread: actions is a list of {action: heal | feed | clear_effects | extinguish} "
                                + "or {action: effect, type, seconds, amplifier?}, {action: give, items}, {action: take, item, count}, {action: xp, levels}, "
                                + "{action: gamemode, mode}, {action: set_spawn, place}, {action: protect, seconds <= 60}, "
                                + "{action: attributes, set: {scale | movement_speed | jump_strength | gravity | step_height | max_health | reach: value}, seconds <= 1800 (default 300)} "
                                + "(put back on death, quit and when the time ends; only your own unless an owner asks). " + ITEMS,
                        obj("name", obj("type", "string"), "actions", obj("type", "array")), "name", "actions"),
                loose("show",
                        "Things players see and hear; never damages or changes blocks. to is a list of names or \"all\". sound {id, volume, pitch}, "
                                + "bossbar {text, color, seconds <= 600, progress?}, fireworks {at, count <= 20, seconds <= 30, colors?}, "
                                + "particles {at, id, count, seconds <= 30}. at is a place: " + PLACE,
                        obj("to", obj("description", "Player names, or \"all\"."), "title", obj("type", "string"), "subtitle", obj("type", "string"),
                                "actionbar", obj("type", "string"), "sound", obj("type", "object"), "bossbar", obj("type", "object"),
                                "fireworks", obj("type", "object"), "particles", obj("type", "object")),
                        "to"),
                loose("later",
                        "Run suite calls after a delay with no new turn; survives restarts. {label, after_seconds <= 3600, repeat?: {every_seconds >= 5, "
                                + "times <= 60}, do: [{tool, args}]}. " + CALLS + ", send_chat, run_commands, teleport_player, routine. "
                                + "{list: true} shows pending ones, {cancel: label} removes one. At most 20 pending.",
                        obj("label", obj("type", "string"), "after_seconds", obj("type", "integer"), "repeat", obj("type", "object"),
                                "do", obj("type", "array"), "list", obj("type", "boolean"), "cancel", obj("type", "string"))),
                loose("wake",
                        "Get a turn of your own later instead of promising to check back. {after_seconds <= 86400, note} or {when: {type, ...}, note, "
                                + "expires_seconds <= 86400, repeat? <= 10 total fires}. Types: player_enters/player_leaves {place, radius}, player_picks_up {item}, "
                                + "player_breaks/player_places {block, at?}, mob_killed {mob, by?}, player_dies {name?}, player_joins {name?}, night_falls, "
                                + "day_breaks, container_opened {at}. The turn arrives with source wake, your note and what happened. {list: true} shows "
                                + "them, {cancel: id} removes one. At most 20.",
                        obj("after_seconds", obj("type", "integer"), "note", obj("type", "string"), "when", obj("type", "object"),
                                "expires_seconds", obj("type", "integer"), "once", obj("type", "boolean"), "repeat", obj("type", "integer"), "list", obj("type", "boolean"),
                                "cancel", obj("type", "integer"))),
                loose("quest",
                        "Shared progress players see on a sidebar or boss bar; survives restarts. {action: start, id, title <= 40, steps: [<= 10], "
                                + "show: sidebar | bossbar | none, reward?: {to: player, items}}, {action: update, id, done: [step numbers]} (adds to those done), "
                                + "{action: finish, id} (gives the reward), {action: cancel, id}, {action: list}. At most 5 active. " + ITEMS,
                        obj("action", obj("type", "string"), "id", obj("type", "string"), "title", obj("type", "string"), "steps", obj("type", "array"),
                                "done", obj("type", "array"), "show", obj("type", "string"), "reward", obj("type", "object")),
                        "action"),
                loose("places",
                        "Named locations. {action: save, name, at: place} (replaces a place of that name), {action: list}, {action: delete, name}, "
                                + "{action: go, name, player} teleports with teleport_player's safety checks. at is a place: " + PLACE,
                        obj("action", obj("type", "string"), "name", obj("type", "string"), "at", obj("type", "object"), "player", obj("type", "string")),
                        "action"),
                loose("undo",
                        "Take back what you did: {action: list} shows the last 20 changes (build, spawn, container, book, sign, entities remove); "
                                + "{action: revert, id} restores blocks, containers and signs and removes spawned mobs that still exist. Blocks a player "
                                + "changed since, containers holding items you did not leave, unloaded chunks and graves are left alone and reported per chunk; "
                                + "a partly undone change can be reverted again for the rest. Given items and removed mobs are not restored. Revert needs "
                                + "an owner, or every player who asked for the change asking now.",
                        obj("action", obj("type", "string"), "id", obj("type", "integer")), "action"),
                loose("find",
                        "Where things are: {what: block | entity | biome | structure | player_bed | saved_place, id?, near?: place, radius?}. "
                                + "Everything is searched only in loaded chunks, within 2 s; the result says what was skipped. radius: blocks, entities "
                                + "and biomes <= 256, structures <= 128. biome and structure are for owners only. player_bed takes a player name as id "
                                + "(the asking player's own, or an owner's request); saved_place lists saved places (id filters).",
                        obj("what", obj("type", "string"), "id", obj("type", "string"), "near", obj("description", "Player name or {world, x, y, z}."),
                                "radius", obj("type", "integer")), "what"),
                loose("server_status",
                        "One call for 'is something wrong': TPS, players with ping and world, memory, uptime, Claude-written plugins with watch "
                                + "state, pending later and wake entries, restarts left today, quests and the plugin library.",
                        obj()),
                loose("container",
                        "Chests, barrels and the like. {at: {world, x, y, z}, action: read | fill | add | clear, items?, create?: chest | barrel, name?}. "
                                + "create places one only into air or a replaceable block; always make chests this way (not with run_commands), since a "
                                + "container Claude did not create here can only be read or added to by a player standing next to it, and filled or cleared "
                                + "only when an owner asks. Refuses a container a player has open, graves and other "
                                + "plugins' containers. A readable book goes in with the book tool, not as an item. " + ITEMS,
                        obj("at", obj("type", "object"), "action", obj("type", "string"), "items", obj("type", "array"), "create", obj("type", "string"),
                                "name", obj("type", "string")),
                        "at", "action"),
                loose("book",
                        "Written things. {to: player | {container: {world, x, y, z}}, title, author?, pages: [<= 20 pages of <= 256 chars]} gives a written "
                                + "book; {sign: {at: {world, x, y, z}, lines: [<= 4]}} writes the front of an existing sign. For riddles, letters and quests.",
                        obj("to", obj("description", "Player name, or {container: {world, x, y, z}}."), "title", obj("type", "string"),
                                "author", obj("type", "string"), "pages", obj("type", "array"), "sign", obj("type", "object"))),
                loose("install_plugin",
                        "Workshop mode only. Install the plugin built in workshop/<name>/: checks its plugin.yml and scans its classes, "
                                + "copies the jar to plugins/ and loads and enables it at once; a running version is disabled and hot-replaced. Then the plugin "
                                + "is on watch: its warnings and errors come back as plugin-watch lines. A plugin that fails to enable is moved "
                                + "to plugins/claude-quarantine/. The first install of a name needs purpose and usage for the plugin library. "
                                + "until gives it a lifetime: seconds (at most 86400) after which it is shelved, or \"request_done\", which lasts until you call "
                                + "shelve_plugin when the request is over.",
                        obj("name", obj("type", "string", "description", "Directory under workshop/, and the plugin's name, e.g. ClaudeWelcome."),
                                "until", obj("description", "Seconds (at most 86400) or \"request_done\"; omitted means no lifetime."),
                                "purpose", obj("type", "string", "description", "One line, at most 200 characters. Required on the first install."),
                                "usage", obj("type", "string", "description", "Commands, triggers and options, at most 500 characters. Required on the first install."),
                                "changes_world", obj("type", "boolean"), "cleans_up", obj("type", "boolean", "description", "True if onDisable undoes its effects."),
                                "safe_for_events", obj("type", "boolean", "description", "Only when a player agreed it may be activated on random-event turns.")),
                        "name"),
                loose("activate_plugin",
                        "Workshop mode only. Bring a shelved plugin back from the plugin library: same jar checks as install_plugin, loaded at once, no rebuild or restart. "
                                + "Look at the catalogue or list_plugins first.",
                        obj("name", obj("type", "string"), "until", obj("description", "Seconds (at most 86400) or \"request_done\"; omitted means no lifetime.")),
                        "name"),
                tool("shelve_plugin",
                        "Workshop mode only. Disable a plugin now and keep it in the plugin library for later (not a deletion). Call it when the request a plugin "
                                + "was installed for with until \"request_done\" is over; that is the only thing that ends such a lifetime.",
                        obj("name", obj("type", "string", "description", "Plugin name, starting with Claude."))),
                tool("list_plugins",
                        "Workshop mode only. List the Claude-written plugins: name, version, state (enabled, disabled, pending restart, quarantined), "
                                + "watch state and problems collected.",
                        obj()),
                tool("disable_plugin",
                        "Workshop mode only. Disable a Claude-written plugin now and move its jar to plugins/claude-quarantine/ so it does not load again.",
                        obj("name", obj("type", "string", "description", "Plugin name, starting with Claude."))),
                tool("restart_server",
                        "Workshop mode only. Announce the reason to players with a 30-second countdown, then save and restart the server. Restarts are budgeted; "
                                + "a refusal says when the next one is allowed. Batch plugin changes into one restart.",
                        obj("reason", obj("type", "string", "description", "Required. Shown to players in chat.")))));
    }

    /** Every property is required and nothing else is allowed (strict tool use). */
    private static Tool tool(String name, String description, Map<String, Object> properties) {
        return new Tool(name, description, MAPPER.valueToTree(obj("type", "object", "properties", properties,
                "required", List.copyOf(properties.keySet()), "additionalProperties", false)));
    }

    /** Only the named properties are required; nested shapes are validated by the tool itself (not strict tool use). */
    private static Tool loose(String name, String description, Map<String, Object> properties, String... required) {
        return new Tool(name, description, MAPPER.valueToTree(obj("type", "object", "properties", properties,
                "required", List.of(required), "additionalProperties", false)));
    }

    /** Insertion-ordered, so the schemas serialise byte-identically on every run (prompt cache, thinking binding). */
    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
