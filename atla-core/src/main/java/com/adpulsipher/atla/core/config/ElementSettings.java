package com.adpulsipher.atla.core.config;

import com.adpulsipher.atla.core.api.Element;
import com.adpulsipher.atla.core.util.ConfigException;
import com.adpulsipher.atla.core.util.Json;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * Per-element rules from the "elements" section of story_progression.json.
 *
 * @param startUnlocked whether a brand-new player can already bend this element
 * @param startLevel    level given when unlocked at the start
 * @param maxLevel      highest reachable level
 * @param unlocked      what to show when this element is unlocked through a gate (overrides the default title)
 */
public record ElementSettings(Element element, boolean startUnlocked, int startLevel, int maxLevel,
                              Announcement unlocked) {

    public static ElementSettings defaults(Element element) {
        return new ElementSettings(element, element == Element.AIR, 1, 5, Announcement.NONE);
    }

    public static ElementSettings read(Element element, @Nullable JsonObject o) throws ConfigException {
        if (o == null) {
            return defaults(element);
        }
        String where = "elements." + element.id();
        int max = Json.integer(o, "maxLevel", 5);
        if (max < 1) {
            throw new ConfigException(where + ": maxLevel must be at least 1");
        }
        int start = Math.max(1, Math.min(max, Json.integer(o, "startLevel", 1)));
        return new ElementSettings(element, Json.bool(o, "startUnlocked", element == Element.AIR), start, max,
                Announcement.read(Json.object(o, "unlockAnnouncement"), where));
    }
}
