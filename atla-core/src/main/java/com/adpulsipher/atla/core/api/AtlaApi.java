package com.adpulsipher.atla.core.api;

import com.adpulsipher.atla.core.config.ProgressionConfig;
import com.adpulsipher.atla.core.data.BendingProgression;
import com.adpulsipher.atla.core.data.ProgressionCapability;
import com.adpulsipher.atla.core.logic.ProgressionManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Stable entry point for other mods (atla_gates, a future bending-abilities mod, KubeJS scripts...).
 * Reads work on either side for the local player's server copy; writes are server-only.
 *
 * <p>Events worth listening to on {@code MinecraftForge.EVENT_BUS}: ElementLevelChangedEvent,
 * ActiveElementChangedEvent, StoryFlagChangedEvent, NarrativeGateClearedEvent, AtlaReloadEvent.</p>
 */
public final class AtlaApi {
    private AtlaApi() {
    }

    // ---- reads

    public static int getLevel(Player player, Element element) {
        return ProgressionCapability.get(player).level(element);
    }

    public static boolean isUnlocked(Player player, Element element) {
        return ProgressionCapability.get(player).isUnlocked(element);
    }

    /** The element currently selected on the player's HUD, or null if they cannot bend anything. */
    @Nullable
    public static Element getActiveElement(Player player) {
        return ProgressionCapability.get(player).active();
    }

    /**
     * The check block overrides use: is {@code element} unlocked at {@code minLevel} or higher, and
     * (if {@code mustBeActive}) is it the element the player currently has selected?
     */
    public static boolean canBend(Player player, Element element, int minLevel, boolean mustBeActive) {
        BendingProgression p = ProgressionCapability.get(player);
        return p.level(element) >= Math.max(1, minLevel) && (!mustBeActive || p.active() == element);
    }

    public static boolean hasFlag(Player player, String flag) {
        return ProgressionCapability.get(player).hasFlag(flag);
    }

    public static Set<String> getFlags(Player player) {
        return ProgressionCapability.get(player).flags();
    }

    public static boolean hasClearedGate(Player player, String gateId) {
        return ProgressionCapability.get(player).hasClearedGate(gateId);
    }

    public static int getMaxLevel(Element element) {
        return ProgressionConfig.get().maxLevel(element);
    }

    public static String getLevelName(int level) {
        return ProgressionConfig.get().levelName(level);
    }

    // ---- writes (server only)

    public static boolean addFlag(ServerPlayer player, String flag) {
        return ProgressionManager.addFlag(player, flag);
    }

    public static boolean removeFlag(ServerPlayer player, String flag) {
        return ProgressionManager.removeFlag(player, flag);
    }

    public static boolean unlock(ServerPlayer player, Element element) {
        return ProgressionManager.unlock(player, element);
    }

    public static boolean setLevel(ServerPlayer player, Element element, int level) {
        return ProgressionManager.setLevel(player, element, level);
    }

    public static boolean selectElement(ServerPlayer player, Element element) {
        return ProgressionManager.select(player, element);
    }

    /** Runs commands as the player with op permission ({@code {player}} is replaced by their name). */
    public static void runCommands(ServerPlayer player, List<String> commands) {
        ProgressionManager.runCommands(player, commands);
    }
}
