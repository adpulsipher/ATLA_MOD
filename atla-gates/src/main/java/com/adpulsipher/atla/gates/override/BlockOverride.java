package com.adpulsipher.atla.gates.override;

import com.adpulsipher.atla.core.api.Element;
import com.adpulsipher.atla.core.api.Requirement;
import com.adpulsipher.atla.core.util.ConfigException;
import com.adpulsipher.atla.core.util.Json;
import com.adpulsipher.atla.gates.placement.Animation;
import com.adpulsipher.atla.gates.placement.Effects;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One entry of {@code config/schematic_overrides.json}: "when a player right-clicks one of these
 * blocks with this element at this level, change the world like this".
 */
public record BlockOverride(String id, ResourceKey<Level> dimension, Set<BlockPos> triggers,
                            List<BoundingBox> triggerAreas, @Nullable Element element, int minLevel,
                            boolean requireActiveElement, Requirement requires, boolean once, List<OverrideAction> actions,
                            List<String> setFlags, List<String> commands, Messages messages, Effects effects,
                            Animation animation, boolean updateNeighbors, boolean recordUndo) {

    /** Player-facing text; null = use the built-in (translatable) default. */
    public record Messages(@Nullable String success, @Nullable String locked, @Nullable String wrongElement,
                           @Nullable String levelTooLow, @Nullable String notReady, @Nullable String alreadyDone) {
        static Messages read(@Nullable JsonObject o) {
            if (o == null) {
                return new Messages(null, null, null, null, null, null);
            }
            return new Messages(Json.string(o, "success", null), Json.string(o, "locked", null),
                    Json.string(o, "wrongElement", null), Json.string(o, "levelTooLow", null),
                    Json.string(o, "notReady", null), Json.string(o, "alreadyDone", null));
        }
    }

    public boolean isTrigger(BlockPos pos) {
        if (triggers.contains(pos)) {
            return true;
        }
        for (BoundingBox box : triggerAreas) {
            if (box.isInside(pos)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("removal")
    public static BlockOverride read(JsonObject o, int index, OverrideConfig.Settings settings) throws ConfigException {
        String where = "schematic_overrides.json overrides[" + index + "]";
        String id = Json.requireString(o, "id", where);
        where = "override '" + id + "'";

        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                Json.id(Json.string(o, "dimension", "minecraft:overworld"), where + " dimension"));

        Set<BlockPos> triggers = new LinkedHashSet<>();
        JsonElement trig = o.has("trigger") ? o.get("trigger") : o.get("triggers");
        if (trig != null && trig.isJsonArray() && !trig.getAsJsonArray().isEmpty() && trig.getAsJsonArray().get(0).isJsonArray()) {
            for (JsonElement e : trig.getAsJsonArray()) {
                triggers.add(Json.blockPos(e, where + " trigger"));
            }
        } else if (trig != null) {
            triggers.add(Json.blockPos(trig, where + " trigger"));
        }
        List<BoundingBox> areas = new ArrayList<>();
        for (JsonObject area : Json.objects(o, "triggerArea")) {
            BlockPos a = Json.blockPos(area.get("from"), where + " triggerArea.from");
            BlockPos b = Json.blockPos(area.get("to"), where + " triggerArea.to");
            areas.add(BoundingBox.fromCorners(a, b));
        }
        if (triggers.isEmpty() && areas.isEmpty()) {
            throw new ConfigException(where + ": needs a \"trigger\" ([x,y,z] or a list of them) or a \"triggerArea\"");
        }

        Element element = null;
        String elementRaw = Json.string(o, "element", null);
        if (elementRaw != null && !elementRaw.equalsIgnoreCase("any") && !elementRaw.isBlank()) {
            element = Element.byId(elementRaw);
            if (element == null) {
                throw new ConfigException(where + ": unknown element \"" + elementRaw + "\" (use air, water, earth, fire or any)");
            }
        }

        List<OverrideAction> actions = new ArrayList<>();
        List<JsonObject> actionObjs = new ArrayList<>(Json.objects(o, "actions"));
        JsonObject single = Json.object(o, "action");
        if (single != null) {
            actionObjs.add(0, single);
        }
        for (int i = 0; i < actionObjs.size(); i++) {
            actions.add(OverrideAction.read(actionObjs.get(i), where + " action " + (i + 1)));
        }
        if (actions.isEmpty() && Json.strings(o, "setFlags").isEmpty() && Json.strings(o, "commands").isEmpty()) {
            throw new ConfigException(where + ": does nothing - add an \"action\" (or \"actions\"), \"setFlags\" or \"commands\"");
        }

        return new BlockOverride(id,
                dim,
                Set.copyOf(triggers),
                List.copyOf(areas),
                element,
                Math.max(1, Json.integer(o, "minLevel", 1)),
                Json.bool(o, "requireActiveElement", true),
                Requirement.fromJson(o.get("requires"), where),
                Json.bool(o, "once", true),
                List.copyOf(actions),
                List.copyOf(Json.strings(o, "setFlags")),
                List.copyOf(Json.strings(o, "commands")),
                Messages.read(Json.object(o, "messages")),
                Effects.read(Json.object(o, "effects"), element, where),
                Animation.read(Json.object(o, "animation"), settings.animation(), where),
                Json.bool(o, "updateNeighbors", false),
                Json.bool(o, "recordUndo", settings.recordUndo()));
    }
}
