package com.adpulsipher.atla.core.config;

import com.adpulsipher.atla.core.api.Element;
import com.adpulsipher.atla.core.api.Requirement;
import com.adpulsipher.atla.core.util.ConfigException;
import com.adpulsipher.atla.core.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A story milestone. Once its {@code requires} pass for a player, it is cleared (once per player)
 * and its rewards are applied: elements unlocked, levels raised, flags changed, commands run.
 */
public record NarrativeGate(String id, String description, Requirement requires, List<Element> unlock,
                            Map<Element, Integer> levels, List<String> addFlags, List<String> removeFlags,
                            List<String> commands, Announcement announce) {

    public static NarrativeGate read(JsonObject o, int index) throws ConfigException {
        String where = "story_progression.json gates[" + index + "]";
        String id = Json.requireString(o, "id", where);
        where = "gate '" + id + "'";

        List<Element> unlock = new ArrayList<>();
        for (String s : Json.strings(o, "unlock")) {
            unlock.add(element(s, where));
        }
        EnumMap<Element, Integer> levels = new EnumMap<>(Element.class);
        JsonObject levelObj = Json.object(o, "levels");
        if (levelObj != null) {
            for (Map.Entry<String, JsonElement> e : levelObj.entrySet()) {
                int level = e.getValue().getAsInt();
                if (level < 1) {
                    throw new ConfigException(where + ": levels must be 1 or higher (use /atla element lock to take an element away)");
                }
                levels.put(element(e.getKey(), where), level);
            }
        }
        return new NarrativeGate(id,
                Json.string(o, "description", ""),
                Requirement.fromJson(o.get("requires"), where),
                List.copyOf(unlock),
                Collections.unmodifiableMap(levels),
                List.copyOf(Json.strings(o, "addFlags")),
                List.copyOf(Json.strings(o, "removeFlags")),
                List.copyOf(Json.strings(o, "commands")),
                Announcement.read(Json.object(o, "announce"), where));
    }

    private static Element element(String raw, String where) throws ConfigException {
        Element e = Element.byId(raw);
        if (e == null) {
            throw new ConfigException(where + ": unknown element \"" + raw + "\" (use air, water, earth or fire)");
        }
        return e;
    }
}
