package com.adpulsipher.atla.core.data;

import com.adpulsipher.atla.core.api.Element;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Everything the story knows about one player: which elements they can bend and how well,
 * which element is currently "in hand", which story flags are set and which narrative gates
 * have already been cleared.
 *
 * <p>A level of {@code 0} means the element is locked. This is raw storage only, with no rules;
 * all mutations that should fire events or sync to the client go through
 * {@link com.adpulsipher.atla.core.logic.ProgressionManager}.</p>
 */
public final class BendingProgression {
    private final EnumMap<Element, Integer> levels = new EnumMap<>(Element.class);
    private final Set<String> flags = new LinkedHashSet<>();
    private final Set<String> clearedGates = new LinkedHashSet<>();
    @Nullable
    private Element active;
    /** False until the starting loadout from story_progression.json has been applied once. */
    private boolean initialized;

    public int level(Element element) {
        return levels.getOrDefault(element, 0);
    }

    public boolean isUnlocked(Element element) {
        return level(element) > 0;
    }

    public void setLevel(Element element, int level) {
        if (level <= 0) {
            levels.remove(element);
            if (active == element) {
                active = null;
            }
        } else {
            levels.put(element, level);
        }
    }

    @Nullable
    public Element active() {
        return active;
    }

    public void setActive(@Nullable Element element) {
        this.active = element != null && isUnlocked(element) ? element : null;
    }

    public Set<String> flags() {
        return Collections.unmodifiableSet(flags);
    }

    public boolean hasFlag(String flag) {
        return flags.contains(flag);
    }

    public boolean addFlag(String flag) {
        return flags.add(flag);
    }

    public boolean removeFlag(String flag) {
        return flags.remove(flag);
    }

    public Set<String> clearedGates() {
        return Collections.unmodifiableSet(clearedGates);
    }

    public boolean hasClearedGate(String id) {
        return clearedGates.contains(id);
    }

    public boolean markGateCleared(String id) {
        return clearedGates.add(id);
    }

    public boolean unmarkGateCleared(String id) {
        return clearedGates.remove(id);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    /** Wipes everything; the starting loadout is re-applied on the next evaluation. */
    public void reset() {
        levels.clear();
        flags.clear();
        clearedGates.clear();
        active = null;
        initialized = false;
    }

    public void copyFrom(BendingProgression other) {
        levels.clear();
        levels.putAll(other.levels);
        flags.clear();
        flags.addAll(other.flags);
        clearedGates.clear();
        clearedGates.addAll(other.clearedGates);
        active = other.active;
        initialized = other.initialized;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        CompoundTag levelTag = new CompoundTag();
        for (Map.Entry<Element, Integer> e : levels.entrySet()) {
            levelTag.putInt(e.getKey().id(), e.getValue());
        }
        tag.put("Levels", levelTag);
        tag.put("Flags", toList(flags));
        tag.put("ClearedGates", toList(clearedGates));
        if (active != null) {
            tag.putString("Active", active.id());
        }
        tag.putBoolean("Initialized", initialized);
        return tag;
    }

    public void load(CompoundTag tag) {
        levels.clear();
        CompoundTag levelTag = tag.getCompound("Levels");
        for (String key : levelTag.getAllKeys()) {
            Element element = Element.byId(key);
            int level = levelTag.getInt(key);
            if (element != null && level > 0) {
                levels.put(element, level);
            }
        }
        flags.clear();
        readList(tag.getList("Flags", Tag.TAG_STRING), flags);
        clearedGates.clear();
        readList(tag.getList("ClearedGates", Tag.TAG_STRING), clearedGates);
        setActive(Element.byId(tag.getString("Active")));
        initialized = tag.getBoolean("Initialized");
    }

    private static ListTag toList(Set<String> values) {
        ListTag list = new ListTag();
        for (String v : values) {
            list.add(StringTag.valueOf(v));
        }
        return list;
    }

    private static void readList(ListTag list, Set<String> into) {
        for (int i = 0; i < list.size(); i++) {
            into.add(list.getString(i));
        }
    }
}
