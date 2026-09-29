package com.adpulsipher.atla.core.api;

import com.adpulsipher.atla.core.compat.GameStagesCompat;
import com.adpulsipher.atla.core.data.BendingProgression;
import com.adpulsipher.atla.core.data.ProgressionCapability;
import com.adpulsipher.atla.core.util.ConfigException;
import com.adpulsipher.atla.core.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A condition on a player's story state, shared by narrative gates, bendable block overrides and
 * boundary zones. Every listed check must pass. JSON form (all keys optional):
 *
 * <pre>{@code
 * {
 *   "flags":         ["met_katara"],            // all of these story flags
 *   "anyFlags":      ["a", "b"],                // at least one of these
 *   "notFlags":      ["zuko_captured_aang"],    // none of these
 *   "elements":      {"earth": 2},              // element unlocked at >= level
 *   "activeElement": "earth",                   // element currently selected on the HUD
 *   "gates":         ["waterbending_unlocked"], // narrative gates already cleared
 *   "advancements":  ["kyoshicraft:story/kyoshi_island"],
 *   "tags":          ["reached_omashu"],        // vanilla /tag entity tags
 *   "notTags":       ["in_cutscene"],
 *   "stages":        ["book_two"],              // Game Stages (only if that mod is installed)
 *   "scores":        {"chapter": {"min": 3}},   // scoreboard objective ranges (or just a number = min)
 *   "any":           [ {...}, {...} ]           // at least one nested requirement must pass
 * }
 * }</pre>
 */
public final class Requirement {
    public static final Requirement NONE = new Requirement();

    private List<String> flags = List.of();
    private List<String> anyFlags = List.of();
    private List<String> notFlags = List.of();
    private Map<Element, Integer> elements = Map.of();
    @Nullable
    private Element activeElement;
    private List<String> gates = List.of();
    private List<ResourceLocation> advancements = List.of();
    private List<String> tags = List.of();
    private List<String> notTags = List.of();
    private List<String> stages = List.of();
    private Map<String, int[]> scores = Map.of();
    private List<Requirement> any = List.of();

    private Requirement() {
    }

    public static Requirement fromJson(@Nullable JsonElement json, String where) throws ConfigException {
        if (json == null || json.isJsonNull()) {
            return NONE;
        }
        if (!json.isJsonObject()) {
            throw new ConfigException(where + ": \"requires\" must be an object { ... }");
        }
        JsonObject o = json.getAsJsonObject();
        Requirement r = new Requirement();
        r.flags = List.copyOf(Json.strings(o, "flags"));
        r.anyFlags = List.copyOf(Json.strings(o, "anyFlags"));
        r.notFlags = List.copyOf(Json.strings(o, "notFlags"));
        r.gates = List.copyOf(Json.strings(o, "gates"));
        r.tags = List.copyOf(Json.strings(o, "tags"));
        r.notTags = List.copyOf(Json.strings(o, "notTags"));
        r.stages = List.copyOf(Json.strings(o, "stages"));

        List<ResourceLocation> adv = new ArrayList<>();
        for (String s : Json.strings(o, "advancements")) {
            adv.add(Json.id(s, where + " advancements"));
        }
        r.advancements = List.copyOf(adv);

        JsonObject elementObj = Json.object(o, "elements");
        if (elementObj != null) {
            EnumMap<Element, Integer> map = new EnumMap<>(Element.class);
            for (Map.Entry<String, JsonElement> e : elementObj.entrySet()) {
                Element element = parseElement(e.getKey(), where);
                map.put(element, Math.max(1, e.getValue().getAsInt()));
            }
            r.elements = Collections.unmodifiableMap(map);
        } else if (o.has("elements")) {
            // also accept a plain list: "elements": ["water", "earth"] means level >= 1
            EnumMap<Element, Integer> map = new EnumMap<>(Element.class);
            for (String s : Json.strings(o, "elements")) {
                map.put(parseElement(s, where), 1);
            }
            r.elements = Collections.unmodifiableMap(map);
        }

        String active = Json.string(o, "activeElement", null);
        if (active != null) {
            r.activeElement = parseElement(active, where);
        }

        JsonObject scoreObj = Json.object(o, "scores");
        if (scoreObj != null) {
            Map<String, int[]> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : scoreObj.entrySet()) {
                JsonElement v = e.getValue();
                int min = Integer.MIN_VALUE;
                int max = Integer.MAX_VALUE;
                if (v.isJsonObject()) {
                    min = Json.integer(v.getAsJsonObject(), "min", Integer.MIN_VALUE);
                    max = Json.integer(v.getAsJsonObject(), "max", Integer.MAX_VALUE);
                } else {
                    min = v.getAsInt();
                }
                map.put(e.getKey(), new int[]{min, max});
            }
            r.scores = Collections.unmodifiableMap(map);
        }

        List<Requirement> nested = new ArrayList<>();
        for (JsonObject child : Json.objects(o, "any")) {
            nested.add(fromJson(child, where + " any[]"));
        }
        r.any = List.copyOf(nested);
        return r;
    }

    private static Element parseElement(String raw, String where) throws ConfigException {
        Element e = Element.byId(raw);
        if (e == null) {
            throw new ConfigException(where + ": unknown element \"" + raw + "\" (use air, water, earth or fire)");
        }
        return e;
    }

    public boolean isEmpty() {
        return this == NONE || (flags.isEmpty() && anyFlags.isEmpty() && notFlags.isEmpty() && elements.isEmpty()
                && activeElement == null && gates.isEmpty() && advancements.isEmpty() && tags.isEmpty()
                && notTags.isEmpty() && stages.isEmpty() && scores.isEmpty() && any.isEmpty());
    }

    public boolean test(ServerPlayer player) {
        return failures(player, true).isEmpty();
    }

    /** Human-readable list of the checks this player currently fails (empty = passes). For debug commands. */
    public List<String> describeFailures(ServerPlayer player) {
        return failures(player, false);
    }

    private List<String> failures(ServerPlayer player, boolean stopAtFirst) {
        if (this == NONE) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        BendingProgression p = ProgressionCapability.get(player);
        Set<String> playerTags = player.getTags();

        for (String f : flags) {
            if (!p.hasFlag(f) && add(out, "missing flag '" + f + "'", stopAtFirst)) return out;
        }
        if (!anyFlags.isEmpty() && anyFlags.stream().noneMatch(p::hasFlag)
                && add(out, "needs one of flags " + anyFlags, stopAtFirst)) return out;
        for (String f : notFlags) {
            if (p.hasFlag(f) && add(out, "has forbidden flag '" + f + "'", stopAtFirst)) return out;
        }
        for (Map.Entry<Element, Integer> e : elements.entrySet()) {
            if (p.level(e.getKey()) < e.getValue()
                    && add(out, e.getKey().id() + " level " + p.level(e.getKey()) + " < " + e.getValue(), stopAtFirst)) return out;
        }
        if (activeElement != null && p.active() != activeElement
                && add(out, "active element is " + (p.active() == null ? "none" : p.active().id()) + ", needs " + activeElement.id(), stopAtFirst)) return out;
        for (String g : gates) {
            if (!p.hasClearedGate(g) && add(out, "gate '" + g + "' not cleared", stopAtFirst)) return out;
        }
        for (ResourceLocation id : advancements) {
            Advancement adv = player.server.getAdvancements().getAdvancement(id);
            boolean done = adv != null && player.getAdvancements().getOrStartProgress(adv).isDone();
            if (!done && add(out, "advancement " + id + (adv == null ? " (does not exist!)" : " not done"), stopAtFirst)) return out;
        }
        for (String t : tags) {
            if (!playerTags.contains(t) && add(out, "missing tag '" + t + "'", stopAtFirst)) return out;
        }
        for (String t : notTags) {
            if (playerTags.contains(t) && add(out, "has forbidden tag '" + t + "'", stopAtFirst)) return out;
        }
        for (String s : stages) {
            if (!GameStagesCompat.hasStage(player, s)
                    && add(out, "missing game stage '" + s + "'" + (GameStagesCompat.isLoaded() ? "" : " (Game Stages is not installed)"), stopAtFirst)) return out;
        }
        if (!scores.isEmpty()) {
            Scoreboard board = player.getScoreboard();
            String owner = player.getScoreboardName();
            for (Map.Entry<String, int[]> e : scores.entrySet()) {
                Objective obj = board.getObjective(e.getKey());
                int[] range = e.getValue();
                boolean ok = false;
                String value = "unset";
                if (obj != null && board.hasPlayerScore(owner, obj)) {
                    int score = board.getOrCreatePlayerScore(owner, obj).getScore();
                    value = String.valueOf(score);
                    ok = score >= range[0] && score <= range[1];
                }
                if (!ok && add(out, "score " + e.getKey() + " is " + value + ", needs " + rangeText(range), stopAtFirst)) return out;
            }
        }
        if (!any.isEmpty() && any.stream().noneMatch(r -> r.test(player))
                && add(out, "none of the 'any' alternatives pass", stopAtFirst)) return out;
        return out;
    }

    private static boolean add(List<String> out, String failure, boolean stop) {
        out.add(failure);
        return stop;
    }

    private static String rangeText(int[] range) {
        if (range[1] == Integer.MAX_VALUE) {
            return ">= " + range[0];
        }
        if (range[0] == Integer.MIN_VALUE) {
            return "<= " + range[1];
        }
        return range[0] + ".." + range[1];
    }

    /** Element levels this requirement asks for; used to build friendly "you need..." messages. */
    public Map<Element, Integer> elementLevels() {
        return elements;
    }

    @Nullable
    public Element activeElement() {
        return activeElement;
    }

    /** Every flag this requirement mentions, for command auto-completion. */
    public void collectFlags(Set<String> into) {
        into.addAll(flags);
        into.addAll(anyFlags);
        into.addAll(notFlags);
        for (Requirement r : any) {
            r.collectFlags(into);
        }
    }
}
