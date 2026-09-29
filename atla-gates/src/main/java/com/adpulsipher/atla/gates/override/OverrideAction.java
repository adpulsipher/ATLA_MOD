package com.adpulsipher.atla.gates.override;

import com.adpulsipher.atla.core.util.ConfigException;
import com.adpulsipher.atla.core.util.Json;
import com.adpulsipher.atla.gates.placement.BlockChange;
import com.adpulsipher.atla.gates.schematic.Schematic;
import com.adpulsipher.atla.gates.schematic.SchematicLoader;
import com.adpulsipher.atla.gates.util.BlockMatcher;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What an override does to the world. Three kinds, picked with {@code "type"}:
 *
 * <pre>{@code
 * // paste a WorldEdit schematic (by default back where it was copied, like //paste -o)
 * {"type": "schematic", "file": "kyoshi_rockwall_open", "origin": [x,y,z] | "position": [x,y,z],
 *  "includeAir": true}
 *
 * // fill a box, optionally only replacing matching blocks ("replace"/"clear" are aliases)
 * {"type": "fill", "from": [x,y,z], "to": [x,y,z], "with": "minecraft:water", "replace": ["#minecraft:ice"]}
 *
 * // set individual blocks
 * {"type": "blocks", "blocks": [{"pos": [x,y,z], "state": "minecraft:air"}]}
 * }</pre>
 */
public interface OverrideAction {
    /** Hard limit so a typo in a fill box can't freeze the server. */
    int MAX_FILL_VOLUME = 1_000_000;

    /** Adds the block changes this action wants to make right now. */
    void plan(ServerLevel level, List<BlockChange> out) throws ConfigException;

    static OverrideAction read(JsonObject o, String where) throws ConfigException {
        String type = Json.string(o, "type", o.has("file") ? "schematic" : "").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "schematic", "schem", "paste" -> SchematicAction.read(o, where);
            case "fill", "replace", "clear" -> FillAction.read(o, type, where);
            case "blocks", "setblock", "set" -> BlocksAction.read(o, where);
            default -> throw new ConfigException(where + ": action \"type\" must be schematic, fill, replace, clear or blocks");
        };
    }

    record SchematicAction(String file, @Nullable BlockPos origin, @Nullable BlockPos position, boolean includeAir,
                           boolean includeBlockEntities) implements OverrideAction {
        static SchematicAction read(JsonObject o, String where) throws ConfigException {
            String file = Json.requireString(o, "file", where);
            BlockPos origin = o.has("origin") ? Json.blockPos(o.get("origin"), where + " origin") : null;
            BlockPos position = o.has("position") ? Json.blockPos(o.get("position"), where + " position") : null;
            SchematicAction action = new SchematicAction(file, origin, position, Json.bool(o, "includeAir", true),
                    Json.bool(o, "includeBlockEntities", true));
            action.minCorner(SchematicLoader.load(file)); // fail at load time, not when a player clicks
            return action;
        }

        BlockPos minCorner(Schematic s) throws ConfigException {
            if (position != null) {
                return position;
            }
            if (origin != null) {
                return origin.offset(s.pasteOffset());
            }
            if (s.originalMin() == null) {
                throw new ConfigException("schematic '" + file + "' does not remember where it was copied from; "
                        + "add \"origin\" (where you stood for //copy) or \"position\" (its minimum corner)");
            }
            return s.originalMin();
        }

        @Override
        public void plan(ServerLevel level, List<BlockChange> out) throws ConfigException {
            Schematic s = SchematicLoader.load(file);
            BlockPos min = minCorner(s);
            for (int y = 0; y < s.height(); y++) {
                for (int z = 0; z < s.length(); z++) {
                    for (int x = 0; x < s.width(); x++) {
                        int index = s.index(x, y, z);
                        int paletteId = s.blocks()[index];
                        if (paletteId < 0) {
                            continue;
                        }
                        BlockState state = s.palette()[paletteId];
                        if (!includeAir && state.isAir()) {
                            continue;
                        }
                        CompoundTag be = includeBlockEntities ? s.blockEntities().get(index) : null;
                        BlockPos pos = min.offset(x, y, z);
                        if (be == null && level.getBlockState(pos) == state) {
                            continue;
                        }
                        out.add(new BlockChange(pos, state, be));
                    }
                }
            }
        }
    }

    record FillAction(BlockPos from, BlockPos to, BlockState with, List<BlockMatcher> replace) implements OverrideAction {
        static FillAction read(JsonObject o, String type, String where) throws ConfigException {
            BlockPos a = Json.blockPos(require(o, "from", where), where + " from");
            BlockPos b = Json.blockPos(require(o, "to", where), where + " to");
            BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
            BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
            long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
            if (volume > MAX_FILL_VOLUME) {
                throw new ConfigException(where + ": fill box is " + volume + " blocks; the limit is " + MAX_FILL_VOLUME);
            }
            String withRaw = type.equals("clear") ? Json.string(o, "with", "minecraft:air") : Json.requireString(o, "with", where);
            List<BlockMatcher> matchers = new ArrayList<>();
            for (String m : Json.strings(o, "replace")) {
                matchers.add(BlockMatcher.parse(m, where + " replace"));
            }
            if (type.equals("replace") && matchers.isEmpty()) {
                throw new ConfigException(where + ": type \"replace\" needs a \"replace\" list of blocks to swap out");
            }
            return new FillAction(min, max, BlockMatcher.parseState(withRaw, where + " with"), List.copyOf(matchers));
        }

        @Override
        public void plan(ServerLevel level, List<BlockChange> out) {
            for (BlockPos p : BlockPos.betweenClosed(from, to)) {
                BlockState current = level.getBlockState(p);
                if (current == with) {
                    continue;
                }
                if (!replace.isEmpty() && replace.stream().noneMatch(m -> m.test(current))) {
                    continue;
                }
                out.add(new BlockChange(p.immutable(), with, null));
            }
        }
    }

    record BlocksAction(List<BlockChange> blocks) implements OverrideAction {
        static BlocksAction read(JsonObject o, String where) throws ConfigException {
            List<BlockChange> list = new ArrayList<>();
            List<JsonObject> entries = Json.objects(o, "blocks");
            for (int i = 0; i < entries.size(); i++) {
                JsonObject e = entries.get(i);
                String w = where + " blocks[" + i + "]";
                BlockPos pos = Json.blockPos(require(e, "pos", w), w + " pos");
                BlockState state = BlockMatcher.parseState(Json.requireString(e, "state", w), w);
                CompoundTag nbt = null;
                String nbtRaw = Json.string(e, "nbt", null);
                if (nbtRaw != null) {
                    try {
                        nbt = TagParser.parseTag(nbtRaw);
                    } catch (Exception ex) {
                        throw new ConfigException(w + ": bad nbt: " + ex.getMessage());
                    }
                }
                list.add(new BlockChange(pos, state, nbt));
            }
            if (list.isEmpty()) {
                throw new ConfigException(where + ": \"blocks\" action has no blocks");
            }
            return new BlocksAction(List.copyOf(list));
        }

        @Override
        public void plan(ServerLevel level, List<BlockChange> out) {
            out.addAll(blocks);
        }
    }

    private static JsonElement require(JsonObject o, String key, String where) throws ConfigException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            throw new ConfigException(where + ": missing \"" + key + "\"");
        }
        return e;
    }
}
