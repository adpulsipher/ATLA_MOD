package com.adpulsipher.atla.core.compat;

import com.adpulsipher.atla.core.AtlaCore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Optional bridge to Darkhax's Game Stages (checked against GameStages-Forge-1.20.1-15.0.2).
 *
 * <p>Game Stages is what FTB Quests, KubeJS and most "staging" addons already understand, so when
 * it is installed we can (a) read stages in requirements and (b) mirror element unlocks / story
 * flags into stages. It is looked up reflectively so it stays an optional dependency.</p>
 */
public final class GameStagesCompat {
    private static final String MOD_ID = "gamestages";
    private static final String HELPER = "net.darkhax.gamestages.GameStageHelper";

    private static MethodHandle hasStage;
    private static MethodHandle addStage;
    private static MethodHandle removeStage;
    private static boolean loaded;

    private GameStagesCompat() {
    }

    public static void init() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        try {
            Class<?> helper = Class.forName(HELPER);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            hasStage = lookup.findStatic(helper, "hasStage", MethodType.methodType(boolean.class, Player.class, String.class));
            addStage = lookup.findStatic(helper, "addStage", MethodType.methodType(void.class, ServerPlayer.class, String[].class));
            removeStage = lookup.findStatic(helper, "removeStage", MethodType.methodType(void.class, ServerPlayer.class, String[].class));
            loaded = true;
            AtlaCore.LOGGER.info("Game Stages detected: requirements can use \"stages\" and unlocks can be mirrored as stages");
        } catch (ReflectiveOperationException | RuntimeException e) {
            AtlaCore.LOGGER.warn("Game Stages is installed but its API could not be found; stage support is disabled", e);
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static boolean hasStage(Player player, String stage) {
        if (!loaded) {
            return false;
        }
        try {
            return (boolean) hasStage.invokeExact(player, stage);
        } catch (Throwable t) {
            AtlaCore.LOGGER.warn("Game Stages hasStage failed", t);
            return false;
        }
    }

    public static void addStage(ServerPlayer player, String stage) {
        if (!loaded) {
            return;
        }
        try {
            addStage.invokeExact(player, new String[]{stage});
        } catch (Throwable t) {
            AtlaCore.LOGGER.warn("Game Stages addStage failed", t);
        }
    }

    public static void removeStage(ServerPlayer player, String stage) {
        if (!loaded) {
            return;
        }
        try {
            removeStage.invokeExact(player, new String[]{stage});
        } catch (Throwable t) {
            AtlaCore.LOGGER.warn("Game Stages removeStage failed", t);
        }
    }
}
