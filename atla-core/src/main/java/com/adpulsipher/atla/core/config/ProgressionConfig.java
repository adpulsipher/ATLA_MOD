package com.adpulsipher.atla.core.config;

import com.adpulsipher.atla.core.AtlaCore;
import com.adpulsipher.atla.core.api.Element;
import com.adpulsipher.atla.core.util.ConfigException;
import com.adpulsipher.atla.core.util.Json;
import com.adpulsipher.atla.core.util.Sounds;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code config/story_progression.json}: which elements Aang starts with, level caps and names,
 * and the list of narrative gates that unlock everything else.
 */
public final class ProgressionConfig {
    public static final String FILE_NAME = "story_progression.json";

    private static volatile ProgressionConfig current = defaults();

    private final Map<Element, ElementSettings> elements;
    private final List<String> levelNames;
    private final Map<String, NarrativeGate> gates;
    private final Settings settings;

    private ProgressionConfig(Map<Element, ElementSettings> elements, List<String> levelNames,
                              Map<String, NarrativeGate> gates, Settings settings) {
        this.elements = elements;
        this.levelNames = levelNames;
        this.gates = gates;
        this.settings = settings;
    }

    public static ProgressionConfig get() {
        return current;
    }

    public static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    /**
     * Loads the file (creating it from the bundled template if missing). On error the previous
     * config stays active and the error is returned so /atla reload can show it.
     */
    public static List<String> load() {
        Path file = path();
        try {
            try (InputStream in = AtlaCore.class.getResourceAsStream("/atla_core/default_story_progression.json")) {
                if (in != null && Json.writeDefault(file, new String(in.readAllBytes(), StandardCharsets.UTF_8))) {
                    AtlaCore.LOGGER.info("Created default {}", file);
                }
            }
            current = parse(Json.readObject(file));
            AtlaCore.LOGGER.info("Loaded {} ({} narrative gates)", FILE_NAME, current.gates.size());
            return List.of();
        } catch (ConfigException e) {
            AtlaCore.LOGGER.error("{} - keeping the previous settings", e.getMessage());
            return List.of(e.getMessage());
        } catch (IOException e) {
            AtlaCore.LOGGER.error("Could not create {}", file, e);
            return List.of("Could not create " + file + ": " + e.getMessage());
        }
    }

    /** Replaces the active config (used by tests and by {@link #load()}). */
    public static void install(ProgressionConfig config) {
        current = config;
    }

    static ProgressionConfig defaults() {
        EnumMap<Element, ElementSettings> map = new EnumMap<>(Element.class);
        for (Element e : Element.values()) {
            map.put(e, ElementSettings.defaults(e));
        }
        return new ProgressionConfig(map, List.of(), Map.of(), Settings.DEFAULT);
    }

    public static ProgressionConfig parse(JsonObject root) throws ConfigException {
        EnumMap<Element, ElementSettings> elements = new EnumMap<>(Element.class);
        JsonObject elementsObj = Json.object(root, "elements");
        for (Element e : Element.values()) {
            elements.put(e, ElementSettings.read(e, elementsObj == null ? null : Json.object(elementsObj, e.id())));
        }
        if (elementsObj != null) {
            for (String key : elementsObj.keySet()) {
                if (Element.byId(key) == null) {
                    throw new ConfigException("elements: unknown element \"" + key + "\" (use air, water, earth or fire)");
                }
            }
        }

        Map<String, NarrativeGate> gates = new LinkedHashMap<>();
        List<JsonObject> gateObjs = Json.objects(root, "gates");
        for (int i = 0; i < gateObjs.size(); i++) {
            NarrativeGate gate = NarrativeGate.read(gateObjs.get(i), i);
            if (gates.put(gate.id(), gate) != null) {
                throw new ConfigException("gate id '" + gate.id() + "' is used twice");
            }
        }

        return new ProgressionConfig(Collections.unmodifiableMap(elements), List.copyOf(Json.strings(root, "levelNames")),
                Collections.unmodifiableMap(gates), Settings.read(Json.object(root, "settings")));
    }

    public ElementSettings element(Element element) {
        return elements.get(element);
    }

    public int maxLevel(Element element) {
        return elements.get(element).maxLevel();
    }

    /** "Adept" for level 3 if levelNames has 3+ entries, otherwise "Level 3". */
    public String levelName(int level) {
        if (level >= 1 && level <= levelNames.size()) {
            return levelNames.get(level - 1);
        }
        return "Level " + level;
    }

    /** Gates in the order they are written in the file. */
    public Map<String, NarrativeGate> gates() {
        return gates;
    }

    public Settings settings() {
        return settings;
    }

    /** Every flag referenced anywhere in the config, used for command suggestions. */
    public Set<String> knownFlags() {
        Set<String> out = new HashSet<>();
        for (NarrativeGate g : gates.values()) {
            g.requires().collectFlags(out);
            out.addAll(g.addFlags());
            out.addAll(g.removeFlags());
        }
        return out;
    }

    public record Settings(int evaluateEveryTicks, boolean autoSelectUnlockedElement, boolean allowPlayerElementSwitching,
                           boolean announceUnlocks, boolean announceLevelUps, Sounds unlockSound, Sounds levelUpSound,
                           Sounds switchSound, boolean mirrorElementsToStages, boolean mirrorFlagsToStages,
                           String stagePrefix) {
        static final Settings DEFAULT = new Settings(20, true, true, true, true,
                new Sounds(mc("ui.toast.challenge_complete"), 1f, 1f),
                new Sounds(mc("entity.player.levelup"), 0.8f, 1.2f),
                new Sounds(mc("entity.phantom.flap"), 0.6f, 1.6f),
                true, false, "atla_");

        @SuppressWarnings("removal")
        private static net.minecraft.resources.ResourceLocation mc(String path) {
            return new net.minecraft.resources.ResourceLocation("minecraft", path);
        }

        static Settings read(JsonObject o) throws ConfigException {
            if (o == null) {
                return DEFAULT;
            }
            JsonObject stages = Json.object(o, "gameStages");
            return new Settings(
                    Math.max(1, Json.integer(o, "evaluateEveryTicks", DEFAULT.evaluateEveryTicks)),
                    Json.bool(o, "autoSelectUnlockedElement", DEFAULT.autoSelectUnlockedElement),
                    Json.bool(o, "allowPlayerElementSwitching", DEFAULT.allowPlayerElementSwitching),
                    Json.bool(o, "announceUnlocks", DEFAULT.announceUnlocks),
                    Json.bool(o, "announceLevelUps", DEFAULT.announceLevelUps),
                    Objects.requireNonNullElse(Sounds.read(o.get("unlockSound"), "settings.unlockSound"), DEFAULT.unlockSound),
                    Objects.requireNonNullElse(Sounds.read(o.get("levelUpSound"), "settings.levelUpSound"), DEFAULT.levelUpSound),
                    Objects.requireNonNullElse(Sounds.read(o.get("switchSound"), "settings.switchSound"), DEFAULT.switchSound),
                    stages == null ? DEFAULT.mirrorElementsToStages : Json.bool(stages, "mirrorElements", DEFAULT.mirrorElementsToStages),
                    stages == null ? DEFAULT.mirrorFlagsToStages : Json.bool(stages, "mirrorFlags", DEFAULT.mirrorFlagsToStages),
                    stages == null ? DEFAULT.stagePrefix : Json.string(stages, "prefix", DEFAULT.stagePrefix));
        }
    }
}
