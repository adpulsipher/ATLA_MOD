package com.adpulsipher.atla.gates.zone;

import com.adpulsipher.atla.core.api.Requirement;
import com.adpulsipher.atla.core.util.ConfigException;
import com.adpulsipher.atla.core.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * A box in the world with a story rule attached. Corners are inclusive block coordinates; give
 * {@code [x, z]} pairs instead of {@code [x, y, z]} to cover the full world height.
 *
 * <ul>
 *   <li><b>barrier</b> - players cannot enter unless {@code requires} passes. With no requirements it is
 *       permanently closed: perfect for parts of the map that are not built yet.</li>
 *   <li><b>bounds</b> - the playable area. If a dimension has any bounds zones whose requirements pass,
 *       players must stay inside at least one of them. Add bigger bounds zones gated on later story
 *       flags and the world opens up chapter by chapter.</li>
 *   <li><b>trigger</b> - entering it (with {@code requires} passing) sets story flags / runs commands.</li>
 * </ul>
 */
public record Zone(String id, Type type, ResourceKey<Level> dimension, AABB box, boolean fullHeight, Requirement requires,
                   @Nullable String message, @Nullable String enterMessage, List<String> setFlags, List<String> commands,
                   boolean once, @Nullable BlockPos fallback, boolean showWall) {

    public enum Type { BARRIER, BOUNDS, TRIGGER }

    public boolean contains(Vec3 pos) {
        return pos.x >= box.minX && pos.x < box.maxX && pos.z >= box.minZ && pos.z < box.maxZ
                && (fullHeight || (pos.y >= box.minY && pos.y < box.maxY));
    }

    @SuppressWarnings("removal")
    public static Zone read(JsonObject o, int index) throws ConfigException {
        String where = "story_boundaries.json zones[" + index + "]";
        String id = Json.requireString(o, "id", where);
        where = "zone '" + id + "'";
        String typeRaw = Json.string(o, "type", "barrier");
        Type type;
        try {
            type = Type.valueOf(typeRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(where + ": type must be barrier, bounds or trigger (got '" + typeRaw + "')");
        }
        JsonElement fromE = o.get("from");
        JsonElement toE = o.get("to");
        if (fromE == null || toE == null) {
            throw new ConfigException(where + ": needs \"from\" and \"to\" corners");
        }
        boolean fullHeight = fromE.isJsonArray() && fromE.getAsJsonArray().size() == 2;
        AABB box;
        if (fullHeight) {
            int[] a = Json.ints(fromE, 2, where + " from");
            int[] b = Json.ints(toE, 2, where + " to");
            box = new AABB(Math.min(a[0], b[0]), -2048, Math.min(a[1], b[1]),
                    Math.max(a[0], b[0]) + 1, 4096, Math.max(a[1], b[1]) + 1);
        } else {
            BlockPos a = Json.blockPos(fromE, where + " from");
            BlockPos b = Json.blockPos(toE, where + " to");
            box = new AABB(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                    Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
        }
        return new Zone(id, type,
                ResourceKey.create(Registries.DIMENSION, Json.id(Json.string(o, "dimension", "minecraft:overworld"), where + " dimension")),
                box, fullHeight,
                Requirement.fromJson(o.get("requires"), where),
                Json.string(o, "message", null),
                Json.string(o, "enterMessage", null),
                List.copyOf(Json.strings(o, "setFlags")),
                List.copyOf(Json.strings(o, "commands")),
                Json.bool(o, "once", true),
                o.has("fallback") ? Json.blockPos(o.get("fallback"), where + " fallback") : null,
                Json.bool(o, "showWall", type != Type.TRIGGER));
    }
}
