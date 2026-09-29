package com.adpulsipher.atla.core.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns config strings into chat components.
 *
 * <ul>
 *   <li>A string starting with <code>{</code> or <code>[</code> is read as a vanilla JSON text
 *       component, e.g. <code>{"text":"Katara","color":"aqua"}</code>.</li>
 *   <li>Anything else is plain text with {@code &} colour codes, e.g. {@code "&bKatara&r says hi"}.</li>
 * </ul>
 *
 * Placeholders like {@code {player}} are substituted before parsing.
 */
public final class Text {
    private static final Pattern AMPERSAND_CODES = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    private Text() {
    }

    @Nullable
    public static Component parse(@Nullable String raw, Map<String, String> placeholders) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String s = raw;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        String trimmed = s.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                MutableComponent c = Component.Serializer.fromJson(trimmed);
                if (c != null) {
                    return c;
                }
            } catch (RuntimeException ignored) {
                // fall through and show it as plain text so the map maker can see the mistake
            }
        }
        return Component.literal(AMPERSAND_CODES.matcher(s).replaceAll("§$1"));
    }

    @Nullable
    public static Component parse(@Nullable String raw) {
        return parse(raw, Map.of());
    }
}
