package com.adpulsipher.atla.gates.override;

import com.adpulsipher.atla.core.util.ConfigException;
import com.adpulsipher.atla.core.util.Json;
import com.adpulsipher.atla.core.util.Sounds;
import com.adpulsipher.atla.gates.AtlaGates;
import com.adpulsipher.atla.gates.placement.Animation;
import com.adpulsipher.atla.gates.schematic.SchematicLoader;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code config/schematic_overrides.json}. A broken entry is skipped (and reported) without
 * taking the rest of the file down; a file that isn't valid JSON keeps the previous config.
 */
public final class OverrideConfig {
    public static final String FILE_NAME = "schematic_overrides.json";

    private static volatile OverrideConfig current = new OverrideConfig(Settings.DEFAULT, Map.of());

    private final Settings settings;
    private final Map<String, BlockOverride> byId;
    private final Map<ResourceKey<Level>, Map<BlockPos, List<BlockOverride>>> byPos = new HashMap<>();
    private final List<BlockOverride> areaOverrides = new ArrayList<>();

    private OverrideConfig(Settings settings, Map<String, BlockOverride> byId) {
        this.settings = settings;
        this.byId = byId;
        for (BlockOverride o : byId.values()) {
            for (BlockPos p : o.triggers()) {
                byPos.computeIfAbsent(o.dimension(), k -> new HashMap<>()).computeIfAbsent(p, k -> new ArrayList<>()).add(o);
            }
            if (!o.triggerAreas().isEmpty()) {
                areaOverrides.add(o);
            }
        }
    }

    public static OverrideConfig get() {
        return current;
    }

    public static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    public static List<String> load() {
        Path file = path();
        List<String> errors = new ArrayList<>();
        try {
            try (InputStream in = AtlaGates.class.getResourceAsStream("/atla_gates/default_schematic_overrides.json")) {
                if (in != null && Json.writeDefault(file, new String(in.readAllBytes(), StandardCharsets.UTF_8))) {
                    AtlaGates.LOGGER.info("Created default {}", file);
                }
            }
            SchematicLoader.clearCache();
            current = parse(Json.readObject(file), errors);
            AtlaGates.LOGGER.info("Loaded {} ({} overrides)", FILE_NAME, current.byId.size());
        } catch (ConfigException e) {
            errors.add(e.getMessage() + " - keeping the previous overrides");
        } catch (IOException e) {
            errors.add("Could not create " + file + ": " + e.getMessage());
        }
        errors.forEach(e -> AtlaGates.LOGGER.error("{}: {}", FILE_NAME, e));
        return errors;
    }

    /** Parses a whole file; broken entries are skipped and described in {@code errors}. */
    public static OverrideConfig parse(JsonObject root, List<String> errors) throws ConfigException {
        Settings settings = Settings.read(Json.object(root, "settings"));
        Map<String, BlockOverride> map = new LinkedHashMap<>();
        List<JsonObject> entries = Json.objects(root, "overrides");
        for (int i = 0; i < entries.size(); i++) {
            if (!Json.bool(entries.get(i), "enabled", true)) {
                continue; // disabled entries are not even validated, so examples can point at files that don't exist yet
            }
            try {
                BlockOverride o = BlockOverride.read(entries.get(i), i, settings);
                if (map.put(o.id(), o) != null) {
                    errors.add("override id '" + o.id() + "' is used twice; the later one wins");
                }
            } catch (ConfigException e) {
                errors.add(e.getMessage());
            }
        }
        return new OverrideConfig(settings, Collections.unmodifiableMap(map));
    }

    /** Replaces the active config (used by tests and by {@link #load()}). */
    public static void install(OverrideConfig config) {
        current = config;
    }

    public Settings settings() {
        return settings;
    }

    public Map<String, BlockOverride> all() {
        return byId;
    }

    @Nullable
    public BlockOverride byId(String id) {
        return byId.get(id);
    }

    /** Every override whose trigger includes this block, in file order. */
    public List<BlockOverride> at(ResourceKey<Level> dimension, BlockPos pos) {
        List<BlockOverride> exact = byPos.getOrDefault(dimension, Map.of()).getOrDefault(pos, List.of());
        if (areaOverrides.isEmpty()) {
            return exact;
        }
        List<BlockOverride> out = new ArrayList<>(exact);
        for (BlockOverride o : areaOverrides) {
            if (o.dimension().equals(dimension) && !out.contains(o) && o.isTrigger(pos)) {
                out.add(o);
            }
        }
        return out;
    }

    /**
     * @param gameModes             modes in which right-clicking triggers overrides (default adventure + survival)
     * @param messageCooldownTicks  minimum gap between "you can't bend this" messages
     * @param failSound             optional sound when a player can't bend a block yet
     * @param animation             default animation for every override
     * @param recordUndo            save undo snapshots so /atla override reset works
     */
    public record Settings(Set<GameType> gameModes, int messageCooldownTicks, @Nullable Sounds failSound,
                           Animation animation, boolean recordUndo) {
        static final Settings DEFAULT = new Settings(EnumSet.of(GameType.ADVENTURE, GameType.SURVIVAL), 20, null,
                Animation.DEFAULT, true);

        static Settings read(@Nullable JsonObject o) throws ConfigException {
            if (o == null) {
                return DEFAULT;
            }
            Set<GameType> modes = EnumSet.noneOf(GameType.class);
            for (String raw : Json.strings(o, "gameModes")) {
                GameType type = GameType.byName(raw.toLowerCase(Locale.ROOT), null);
                if (type == null) {
                    throw new ConfigException("settings.gameModes: unknown game mode '" + raw + "'");
                }
                modes.add(type);
            }
            return new Settings(modes.isEmpty() ? DEFAULT.gameModes : modes,
                    Math.max(0, Json.integer(o, "messageCooldownTicks", DEFAULT.messageCooldownTicks)),
                    Sounds.read(o.get("failSound"), "settings.failSound"),
                    Animation.read(Json.object(o, "animation"), DEFAULT.animation, "settings"),
                    Json.bool(o, "recordUndo", DEFAULT.recordUndo));
        }
    }
}
