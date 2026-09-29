package com.adpulsipher.atla.core.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A sound reference from a config file. Unknown ids are still playable: sounds that only exist in
 * the map's resource pack (sounds.json) have no registry entry on the server but play fine on the client.
 */
public record Sounds(ResourceLocation id, float volume, float pitch) {

    /** Reads {@code "minecraft:block.stone.break"} or {@code {"id": "...", "volume": 1, "pitch": 1}}. */
    @Nullable
    public static Sounds read(@Nullable JsonElement e, String where) throws ConfigException {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive()) {
            String raw = e.getAsString();
            return raw.isBlank() ? null : new Sounds(Json.id(raw, where), 1.0f, 1.0f);
        }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            return new Sounds(Json.id(Json.requireString(o, "id", where), where),
                    (float) Json.number(o, "volume", 1.0), (float) Json.number(o, "pitch", 1.0));
        }
        throw new ConfigException(where + ": a sound must be an id string or {\"id\": ...}");
    }

    public SoundEvent event() {
        return BuiltInRegistries.SOUND_EVENT.getOptional(id).orElseGet(() -> SoundEvent.createVariableRangeEvent(id));
    }

    /** Plays at a position for everyone nearby. */
    public void playAt(ServerLevel level, Vec3 pos, SoundSource source) {
        level.playSound(null, pos.x, pos.y, pos.z, event(), source, volume, pitch);
    }

    /** Plays only for this player (UI-style feedback). */
    public void playFor(ServerPlayer player) {
        player.playNotifySound(event(), SoundSource.PLAYERS, volume, pitch);
    }
}
