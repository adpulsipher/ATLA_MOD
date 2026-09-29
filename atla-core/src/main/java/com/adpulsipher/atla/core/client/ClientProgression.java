package com.adpulsipher.atla.core.client;

import com.adpulsipher.atla.core.api.Element;
import com.adpulsipher.atla.core.network.SyncProgressionPacket;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/** The local player's element state as last synced by the server. Client-only. */
public final class ClientProgression {
    private static final Element[] ELEMENTS = Element.values();
    private static int[] levels = new int[ELEMENTS.length];
    private static int[] maxLevels = new int[]{5, 5, 5, 5};
    @Nullable
    private static Element active;
    /** Game time when the selection last changed, used to fade the element name in and out. */
    private static long changedAt = Long.MIN_VALUE;

    private ClientProgression() {
    }

    public static void apply(SyncProgressionPacket packet) {
        Element previous = active;
        levels = packet.levels().length == ELEMENTS.length ? packet.levels() : new int[ELEMENTS.length];
        maxLevels = packet.maxLevels().length == ELEMENTS.length ? packet.maxLevels() : maxLevels;
        active = packet.active() >= 0 && packet.active() < ELEMENTS.length ? ELEMENTS[packet.active()] : null;
        Minecraft mc = Minecraft.getInstance();
        if (previous != active && mc.level != null) {
            changedAt = mc.level.getGameTime();
        }
    }

    public static void clear() {
        levels = new int[ELEMENTS.length];
        active = null;
        changedAt = Long.MIN_VALUE;
    }

    public static int level(Element e) {
        return levels[e.ordinal()];
    }

    public static int maxLevel(Element e) {
        return maxLevels[e.ordinal()];
    }

    public static boolean isUnlocked(Element e) {
        return level(e) > 0;
    }

    @Nullable
    public static Element active() {
        return active;
    }

    public static long changedAt() {
        return changedAt;
    }

    public static int unlockedCount() {
        int n = 0;
        for (int l : levels) {
            if (l > 0) {
                n++;
            }
        }
        return n;
    }
}
