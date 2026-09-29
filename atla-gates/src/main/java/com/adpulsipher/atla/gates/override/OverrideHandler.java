package com.adpulsipher.atla.gates.override;

import com.adpulsipher.atla.core.api.AtlaApi;
import com.adpulsipher.atla.core.api.Element;
import com.adpulsipher.atla.core.util.ConfigException;
import com.adpulsipher.atla.core.util.Text;
import com.adpulsipher.atla.gates.AtlaGates;
import com.adpulsipher.atla.gates.placement.BlockChange;
import com.adpulsipher.atla.gates.placement.PlacementQueue;
import com.adpulsipher.atla.gates.placement.UndoStore;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Right-click a designated block -> check the player's bending -> swap the blocks.
 *
 * <p>Checks, in order: is the element unlocked at all, is its level high enough, is it the element
 * currently selected on the HUD, and do the extra {@code requires} pass. The first failure is shown
 * on the action bar (rate-limited).</p>
 */
public final class OverrideHandler {
    public enum Outcome {
        /** No active override at that block. */
        NONE,
        TRIGGERED,
        LOCKED,
        LEVEL_TOO_LOW,
        WRONG_ELEMENT,
        NOT_READY,
        ALREADY_DONE,
        /** The override is misconfigured (e.g. a schematic file vanished); see the log. */
        ERROR
    }

    private static final Map<UUID, Long> LAST_MESSAGE = new HashMap<>();

    private OverrideHandler() {
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || event.getHand() != InteractionHand.MAIN_HAND
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        OverrideConfig cfg = OverrideConfig.get();
        if (!cfg.settings().gameModes().contains(player.gameMode.getGameModeForPlayer())) {
            return;
        }
        Outcome outcome = tryBend(player, event.getPos());
        if (outcome != Outcome.NONE && outcome != Outcome.ALREADY_DONE) {
            // the click was "used" by bending (or the attempt), so doors/levers/etc. at that block don't also react
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** Public so tests and other mods can simulate a bending right-click. */
    public static Outcome tryBend(ServerPlayer player, BlockPos pos) {
        List<BlockOverride> candidates = OverrideConfig.get().at(player.level().dimension(), pos);
        if (candidates.isEmpty()) {
            return Outcome.NONE;
        }
        OverrideState state = OverrideState.get(player.server);
        Outcome failure = null;
        BlockOverride failed = null;
        BlockOverride done = null;
        for (BlockOverride ov : candidates) {
            if (ov.once() && state.isDone(ov.id())) {
                done = ov;
                continue;
            }
            Outcome check = check(player, ov);
            if (check == Outcome.TRIGGERED) {
                return trigger(ov, player.server, player, pos) ? Outcome.TRIGGERED : Outcome.ERROR;
            }
            if (failure == null) {
                failure = check;
                failed = ov;
            }
        }
        if (failed != null) {
            feedback(player, failed, failure);
            return failure;
        }
        if (done != null && done.messages().alreadyDone() != null) {
            feedback(player, done, Outcome.ALREADY_DONE);
        }
        return Outcome.ALREADY_DONE;
    }

    /** Returns TRIGGERED if this player may bend the override right now, otherwise the reason they can't. */
    public static Outcome check(ServerPlayer player, BlockOverride ov) {
        Element element = ov.element();
        if (element != null) {
            int level = AtlaApi.getLevel(player, element);
            if (level <= 0) {
                return Outcome.LOCKED;
            }
            if (level < ov.minLevel()) {
                return Outcome.LEVEL_TOO_LOW;
            }
            if (ov.requireActiveElement() && AtlaApi.getActiveElement(player) != element) {
                return Outcome.WRONG_ELEMENT;
            }
        }
        return ov.requires().test(player) ? Outcome.TRIGGERED : Outcome.NOT_READY;
    }

    /**
     * Applies an override: snapshot for undo, mark done, animate the block changes, then give the
     * player their flags / commands / message. {@code player} may be null (triggered by command).
     */
    public static boolean trigger(BlockOverride ov, MinecraftServer server, @Nullable ServerPlayer player, BlockPos clicked) {
        ServerLevel level = server.getLevel(ov.dimension());
        if (level == null) {
            AtlaGates.LOGGER.error("Override '{}' points at dimension {} which does not exist", ov.id(), ov.dimension().location());
            return false;
        }
        // Later actions win when two actions touch the same block.
        Map<BlockPos, BlockChange> merged = new LinkedHashMap<>();
        try {
            List<BlockChange> planned = new ArrayList<>();
            for (OverrideAction action : ov.actions()) {
                action.plan(level, planned);
            }
            for (BlockChange change : planned) {
                merged.remove(change.pos());
                merged.put(change.pos(), change);
            }
        } catch (ConfigException e) {
            AtlaGates.LOGGER.error("Override '{}' could not run: {}", ov.id(), e.getMessage());
            if (player != null && player.hasPermissions(2)) {
                player.sendSystemMessage(Component.literal("[ATLA] override '" + ov.id() + "': " + e.getMessage()).withStyle(ChatFormatting.RED));
            }
            return false;
        }
        List<BlockChange> changes = new ArrayList<>(merged.values());

        if (ov.recordUndo() && !changes.isEmpty() && !UndoStore.exists(server, ov.id())) {
            UndoStore.capture(level, ov.id(), changes);
        }
        String who = player == null ? "<command>" : player.getGameProfile().getName();
        if (ov.once()) {
            OverrideState.get(server).markDone(ov.id(), level.getGameTime(), who);
        }
        List<BlockChange> ordered = ov.animation().sort(changes, clicked, level.random);
        PlacementQueue.submit(level, ordered, ov.animation().perTick(ordered.size()), ov.effects(), ov.updateNeighbors());
        if (ov.effects().sound() != null) {
            ov.effects().sound().playAt(level, Vec3.atCenterOf(clicked), SoundSource.PLAYERS);
        }
        AtlaGates.LOGGER.info("{} triggered override '{}' ({} block changes)", who, ov.id(), changes.size());

        if (player != null) {
            player.swing(InteractionHand.MAIN_HAND, true);
            for (String flag : ov.setFlags()) {
                AtlaApi.addFlag(player, flag);
            }
            AtlaApi.runCommands(player, ov.commands());
            Component success = Text.parse(ov.messages().success(), placeholders(player, ov));
            if (success != null) {
                player.displayClientMessage(success, true);
            }
        }
        return true;
    }

    private static void feedback(ServerPlayer player, BlockOverride ov, Outcome outcome) {
        long now = player.level().getGameTime();
        Long last = LAST_MESSAGE.get(player.getUUID());
        if (last != null && now - last < OverrideConfig.get().settings().messageCooldownTicks() && now >= last) {
            return;
        }
        LAST_MESSAGE.put(player.getUUID(), now);

        Map<String, String> ph = placeholders(player, ov);
        Component element = ov.element() == null ? Component.literal("?") : ov.element().displayName();
        Component msg = switch (outcome) {
            case LOCKED -> custom(ov.messages().locked(), ph, Component.translatableWithFallback("atla_gates.override.locked",
                    "Only %s could move this... and you cannot bend it yet.", element));
            case LEVEL_TOO_LOW -> custom(ov.messages().levelTooLow(), ph, Component.translatableWithFallback("atla_gates.override.level_too_low",
                    "Your %s is not strong enough yet (needs %s).", element, AtlaApi.getLevelName(ov.minLevel())));
            case WRONG_ELEMENT -> custom(ov.messages().wrongElement(), ph, Component.translatableWithFallback("atla_gates.override.wrong_element",
                    "Switch to %s to bend this.", element));
            case NOT_READY -> custom(ov.messages().notReady(), ph, Component.translatableWithFallback("atla_gates.override.not_ready",
                    "The time isn't right for this yet."));
            case ALREADY_DONE -> Text.parse(ov.messages().alreadyDone(), ph);
            default -> null;
        };
        if (msg != null) {
            player.displayClientMessage(msg, true);
        }
        if (OverrideConfig.get().settings().failSound() != null && outcome != Outcome.ALREADY_DONE) {
            OverrideConfig.get().settings().failSound().playFor(player);
        }
    }

    private static Component custom(@Nullable String raw, Map<String, String> ph, Component fallback) {
        Component parsed = Text.parse(raw, ph);
        return parsed != null ? parsed : fallback;
    }

    private static Map<String, String> placeholders(ServerPlayer player, BlockOverride ov) {
        Map<String, String> map = new HashMap<>();
        map.put("player", player.getGameProfile().getName());
        map.put("element", ov.element() == null ? "" : ov.element().displayName().getString());
        map.put("level", String.valueOf(ov.minLevel()));
        map.put("levelName", AtlaApi.getLevelName(ov.minLevel()));
        return map;
    }

    public static void forget(UUID player) {
        LAST_MESSAGE.remove(player);
    }
}
