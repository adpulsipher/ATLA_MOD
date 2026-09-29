package com.adpulsipher.atla.gates.zone;

import com.adpulsipher.atla.core.util.ConfigException;
import com.adpulsipher.atla.core.util.Json;
import com.adpulsipher.atla.core.util.Sounds;
import com.adpulsipher.atla.gates.AtlaGates;
import com.adpulsipher.atla.gates.placement.Effects;
import com.google.gson.JsonObject;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** {@code config/story_boundaries.json}: the zones that keep players out of unfinished parts of the map. */
public final class ZoneConfig {
    public static final String FILE_NAME = "story_boundaries.json";

    private static volatile ZoneConfig current = new ZoneConfig(Settings.DEFAULT, Map.of());

    private final Settings settings;
    private final Map<String, Zone> byId;
    private final Map<ResourceKey<Level>, List<Zone>> byDimension = new HashMap<>();

    private ZoneConfig(Settings settings, Map<String, Zone> byId) {
        this.settings = settings;
        this.byId = byId;
        for (Zone z : byId.values()) {
            byDimension.computeIfAbsent(z.dimension(), k -> new ArrayList<>()).add(z);
        }
    }

    public static ZoneConfig get() {
        return current;
    }

    public static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    public static List<String> load() {
        Path file = path();
        List<String> errors = new ArrayList<>();
        try {
            try (InputStream in = AtlaGates.class.getResourceAsStream("/atla_gates/default_story_boundaries.json")) {
                if (in != null && Json.writeDefault(file, new String(in.readAllBytes(), StandardCharsets.UTF_8))) {
                    AtlaGates.LOGGER.info("Created default {}", file);
                }
            }
            current = parse(Json.readObject(file), errors);
            AtlaGates.LOGGER.info("Loaded {} ({} zones)", FILE_NAME, current.byId.size());
        } catch (ConfigException e) {
            errors.add(e.getMessage() + " - keeping the previous zones");
        } catch (IOException e) {
            errors.add("Could not create " + file + ": " + e.getMessage());
        }
        errors.forEach(e -> AtlaGates.LOGGER.error("{}: {}", FILE_NAME, e));
        return errors;
    }

    /** Parses a whole file; broken zones are skipped and described in {@code errors}. */
    public static ZoneConfig parse(JsonObject root, List<String> errors) throws ConfigException {
        Settings settings = Settings.read(Json.object(root, "settings"));
        Map<String, Zone> map = new LinkedHashMap<>();
        List<JsonObject> entries = Json.objects(root, "zones");
        for (int i = 0; i < entries.size(); i++) {
            if (!Json.bool(entries.get(i), "enabled", true)) {
                continue;
            }
            try {
                Zone z = Zone.read(entries.get(i), i);
                if (map.put(z.id(), z) != null) {
                    errors.add("zone id '" + z.id() + "' is used twice; the later one wins");
                }
            } catch (ConfigException e) {
                errors.add(e.getMessage());
            }
        }
        return new ZoneConfig(settings, map);
    }

    /** Replaces the active config (used by tests and by {@link #load()}). */
    public static void install(ZoneConfig config) {
        current = config;
    }

    public Settings settings() {
        return settings;
    }

    public Map<String, Zone> all() {
        return byId;
    }

    public List<Zone> in(ResourceKey<Level> dimension) {
        return byDimension.getOrDefault(dimension, List.of());
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    public record Settings(Set<GameType> exemptGameModes, int messageCooldownTicks, boolean showWalls, double wallDistance,
                           @Nullable ParticleOptions wallParticle, @Nullable Sounds pushSound, @Nullable String defaultMessage) {
        @SuppressWarnings("removal")
        static final Settings DEFAULT = new Settings(EnumSet.of(GameType.CREATIVE, GameType.SPECTATOR), 40, true, 4.0,
                ParticleTypes.CLOUD, new Sounds(new ResourceLocation("minecraft", "entity.phantom.flap"), 0.5f, 0.7f), null);

        static Settings read(@Nullable JsonObject o) throws ConfigException {
            if (o == null) {
                return DEFAULT;
            }
            Set<GameType> exempt = EnumSet.noneOf(GameType.class);
            if (o.has("exemptGameModes")) {
                for (String raw : Json.strings(o, "exemptGameModes")) {
                    GameType type = GameType.byName(raw.toLowerCase(Locale.ROOT), null);
                    if (type == null) {
                        throw new ConfigException("settings.exemptGameModes: unknown game mode '" + raw + "'");
                    }
                    exempt.add(type);
                }
            } else {
                exempt.addAll(DEFAULT.exemptGameModes);
            }
            ParticleOptions particle = DEFAULT.wallParticle;
            if (o.has("wallParticle")) {
                String raw = Json.string(o, "wallParticle", "");
                particle = raw.isBlank() ? null : Effects.parseParticle(raw, "settings.wallParticle");
            }
            Sounds push = o.has("pushSound") ? Sounds.read(o.get("pushSound"), "settings.pushSound") : DEFAULT.pushSound;
            return new Settings(exempt,
                    Math.max(0, Json.integer(o, "messageCooldownTicks", DEFAULT.messageCooldownTicks)),
                    Json.bool(o, "showWalls", DEFAULT.showWalls),
                    Math.max(0, Json.number(o, "wallDistance", DEFAULT.wallDistance)),
                    particle, push, Json.string(o, "defaultMessage", null));
        }
    }
}
