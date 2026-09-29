package com.adpulsipher.atla.gates.override;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which one-shot overrides have already been bent in this world (stored in
 * {@code <world>/data/atla_gates_overrides.dat}). The changed blocks themselves are just part of the world.
 */
public final class OverrideState extends SavedData {
    private static final String NAME = "atla_gates_overrides";

    public record Entry(long gameTime, String player) {
    }

    private final Map<String, Entry> done = new LinkedHashMap<>();

    public static OverrideState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(OverrideState::load, OverrideState::new, NAME);
    }

    private static OverrideState load(CompoundTag tag) {
        OverrideState state = new OverrideState();
        CompoundTag doneTag = tag.getCompound("Done");
        for (String id : doneTag.getAllKeys()) {
            CompoundTag e = doneTag.getCompound(id);
            state.done.put(id, new Entry(e.getLong("Time"), e.getString("Player")));
        }
        return state;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag doneTag = new CompoundTag();
        done.forEach((id, e) -> {
            CompoundTag t = new CompoundTag();
            t.putLong("Time", e.gameTime());
            t.putString("Player", e.player());
            doneTag.put(id, t);
        });
        tag.put("Done", doneTag);
        return tag;
    }

    public boolean isDone(String id) {
        return done.containsKey(id);
    }

    @Nullable
    public Entry entry(String id) {
        return done.get(id);
    }

    public void markDone(String id, long gameTime, String player) {
        done.put(id, new Entry(gameTime, player));
        setDirty();
    }

    public boolean clear(String id) {
        boolean removed = done.remove(id) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }
}
