package com.adpulsipher.atla.core.api;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The four bending arts. The ordinal order is the order the HUD cycles through them.
 */
public enum Element {
    AIR("air", 0xE8D9A8, ChatFormatting.YELLOW),
    WATER("water", 0x3F8FD8, ChatFormatting.AQUA),
    EARTH("earth", 0x5E9C3A, ChatFormatting.GREEN),
    FIRE("fire", 0xE0482A, ChatFormatting.RED);

    private static final Element[] VALUES = values();

    private final String id;
    private final int color;
    private final ChatFormatting chatColor;

    Element(String id, int color, ChatFormatting chatColor) {
        this.id = id;
        this.color = color;
        this.chatColor = chatColor;
    }

    /** Lower-case id used in configs, commands and NBT ("air", "water", "earth", "fire"). */
    public String id() {
        return id;
    }

    /** RGB colour used by the HUD. */
    public int color() {
        return color;
    }

    public ChatFormatting chatColor() {
        return chatColor;
    }

    /** "Airbending", "Waterbending", ... (translatable). */
    public MutableComponent displayName() {
        return Component.translatable("atla_core.element." + id).withStyle(chatColor);
    }

    /**
     * Accepts "earth", "EARTH", "earthbending" or "Earthbending".
     */
    @Nullable
    public static Element byId(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith("bending")) {
            s = s.substring(0, s.length() - "bending".length());
        }
        for (Element e : VALUES) {
            if (e.id.equals(s)) {
                return e;
            }
        }
        return null;
    }

    public static Element[] all() {
        return VALUES.clone();
    }
}
