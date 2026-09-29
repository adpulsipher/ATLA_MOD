package com.adpulsipher.atla.core.logic;

import com.adpulsipher.atla.core.AtlaCore;
import com.adpulsipher.atla.core.api.Element;
import com.adpulsipher.atla.core.api.event.ActiveElementChangedEvent;
import com.adpulsipher.atla.core.api.event.ElementLevelChangedEvent;
import com.adpulsipher.atla.core.api.event.NarrativeGateClearedEvent;
import com.adpulsipher.atla.core.api.event.StoryFlagChangedEvent;
import com.adpulsipher.atla.core.compat.GameStagesCompat;
import com.adpulsipher.atla.core.config.Announcement;
import com.adpulsipher.atla.core.config.ElementSettings;
import com.adpulsipher.atla.core.config.NarrativeGate;
import com.adpulsipher.atla.core.config.ProgressionConfig;
import com.adpulsipher.atla.core.data.BendingProgression;
import com.adpulsipher.atla.core.data.ProgressionCapability;
import com.adpulsipher.atla.core.network.AtlaNetwork;
import com.adpulsipher.atla.core.network.SyncProgressionPacket;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side rules for story progression. Every change to a player's elements, flags or gates
 * should go through here so that events fire, gates are re-evaluated and the client HUD stays in sync.
 */
public final class ProgressionManager {
    /** Safety valve against gates whose rewards keep satisfying other gates forever. */
    private static final int MAX_EVALUATION_PASSES = 32;

    private static final Set<UUID> EVALUATING = new HashSet<>();
    private static final Set<UUID> DIRTY = new HashSet<>();

    private ProgressionManager() {
    }

    public static BendingProgression get(ServerPlayer player) {
        return ProgressionCapability.get(player);
    }

    // ------------------------------------------------------------------ start / reset

    /** Gives a brand-new player the starting loadout (by default: Airbending only). Runs once per player. */
    public static void ensureInitialized(ServerPlayer player) {
        BendingProgression p = get(player);
        if (p.isInitialized()) {
            return;
        }
        p.setInitialized(true);
        ProgressionConfig cfg = ProgressionConfig.get();
        for (Element e : Element.values()) {
            ElementSettings s = cfg.element(e);
            if (s.startUnlocked() && p.level(e) == 0) {
                applyLevel(player, e, s.startLevel(), false);
            }
        }
        if (p.active() == null) {
            setActive(player, firstUnlocked(p), false);
        }
        sync(player);
    }

    /** Wipes all progression, then re-applies the starting loadout and any gates that already pass. */
    public static void reset(ServerPlayer player) {
        BendingProgression p = get(player);
        List<String> flags = new ArrayList<>(p.flags());
        for (Element e : Element.values()) {
            applyLevel(player, e, 0, false);
        }
        for (String flag : flags) {
            removeFlag(player, flag);
        }
        p.reset();
        ensureInitialized(player);
        evaluateGates(player);
        sync(player);
    }

    // ------------------------------------------------------------------ elements

    /**
     * Sets an element's level (0 locks it), clamped to the configured max level.
     *
     * @return true if anything changed
     */
    public static boolean setLevel(ServerPlayer player, Element element, int level) {
        boolean changed = applyLevel(player, element, level, true);
        if (changed) {
            sync(player);
            evaluateGates(player);
        }
        return changed;
    }

    public static boolean unlock(ServerPlayer player, Element element) {
        if (get(player).isUnlocked(element)) {
            return false;
        }
        return setLevel(player, element, ProgressionConfig.get().element(element).startLevel());
    }

    public static boolean lock(ServerPlayer player, Element element) {
        return setLevel(player, element, 0);
    }

    private static boolean applyLevel(ServerPlayer player, Element element, int level, boolean announce) {
        ProgressionConfig cfg = ProgressionConfig.get();
        BendingProgression p = get(player);
        int clamped = Math.max(0, Math.min(cfg.maxLevel(element), level));
        int old = p.level(element);
        if (old == clamped) {
            return false;
        }
        Element previousActive = p.active();
        p.setLevel(element, clamped);

        if (clamped > 0 && (p.active() == null || (old == 0 && cfg.settings().autoSelectUnlockedElement()))) {
            p.setActive(element);
        } else if (p.active() == null) {
            p.setActive(firstUnlocked(p));
        }

        MinecraftForge.EVENT_BUS.post(new ElementLevelChangedEvent(player, element, old, clamped));
        if (previousActive != p.active()) {
            MinecraftForge.EVENT_BUS.post(new ActiveElementChangedEvent(player, previousActive, p.active()));
        }
        if (announce) {
            announceLevel(player, element, old, clamped);
        }
        mirrorElementStages(player, element);
        AtlaCore.LOGGER.debug("{}: {} level {} -> {}", player.getGameProfile().getName(), element.id(), old, clamped);
        return true;
    }

    private static void announceLevel(ServerPlayer player, Element element, int old, int level) {
        ProgressionConfig cfg = ProgressionConfig.get();
        ProgressionConfig.Settings s = cfg.settings();
        Map<String, String> placeholders = placeholders(player, element, level);
        if (old == 0 && level > 0 && s.announceUnlocks()) {
            Announcement custom = cfg.element(element).unlocked();
            if (!custom.isEmpty()) {
                custom.show(player, placeholders);
            } else {
                Announcement.showTitle(player, element.displayName(),
                        Component.translatable("atla_core.announce.unlocked"));
                s.unlockSound().playFor(player);
            }
        } else if (level > old && old > 0 && s.announceLevelUps()) {
            player.connection.send(new ClientboundSetActionBarTextPacket(Component.translatable(
                    "atla_core.announce.level_up", element.displayName(), cfg.levelName(level))));
            s.levelUpSound().playFor(player);
        }
    }

    // ------------------------------------------------------------------ active element

    /**
     * Switches the element shown on the HUD (the "current bending element" that block overrides check).
     * Locked elements can never be selected.
     */
    public static boolean select(ServerPlayer player, @Nullable Element element) {
        BendingProgression p = get(player);
        if (element != null && !p.isUnlocked(element)) {
            return false;
        }
        if (p.active() == element) {
            return false;
        }
        setActive(player, element, true);
        sync(player);
        return true;
    }

    /** Selects the next (direction > 0) or previous unlocked element, wrapping around. */
    public static boolean cycle(ServerPlayer player, int direction) {
        BendingProgression p = get(player);
        Element[] all = Element.values();
        int start = p.active() == null ? -1 : p.active().ordinal();
        int step = direction >= 0 ? 1 : -1;
        for (int i = 1; i <= all.length; i++) {
            Element candidate = all[Math.floorMod(start + i * step, all.length)];
            if (p.isUnlocked(candidate)) {
                return select(player, candidate);
            }
        }
        return false;
    }

    private static void setActive(ServerPlayer player, @Nullable Element element, boolean feedback) {
        BendingProgression p = get(player);
        Element previous = p.active();
        p.setActive(element);
        if (previous != p.active()) {
            MinecraftForge.EVENT_BUS.post(new ActiveElementChangedEvent(player, previous, p.active()));
            if (feedback && p.active() != null) {
                ProgressionConfig.get().settings().switchSound().playFor(player);
            }
        }
    }

    @Nullable
    private static Element firstUnlocked(BendingProgression p) {
        for (Element e : Element.values()) {
            if (p.isUnlocked(e)) {
                return e;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ flags

    public static boolean addFlag(ServerPlayer player, String flag) {
        if (!get(player).addFlag(flag)) {
            return false;
        }
        MinecraftForge.EVENT_BUS.post(new StoryFlagChangedEvent(player, flag, true));
        if (ProgressionConfig.get().settings().mirrorFlagsToStages()) {
            GameStagesCompat.addStage(player, flag);
        }
        evaluateGates(player);
        return true;
    }

    public static boolean removeFlag(ServerPlayer player, String flag) {
        if (!get(player).removeFlag(flag)) {
            return false;
        }
        MinecraftForge.EVENT_BUS.post(new StoryFlagChangedEvent(player, flag, false));
        if (ProgressionConfig.get().settings().mirrorFlagsToStages()) {
            GameStagesCompat.removeStage(player, flag);
        }
        return true;
    }

    // ------------------------------------------------------------------ narrative gates

    /**
     * Clears every narrative gate whose requirements this player now meets. Gate rewards can satisfy
     * other gates, so this loops until nothing changes. Safe to call re-entrantly (e.g. from a gate's
     * reward command that runs {@code /atla flag add}).
     */
    public static void evaluateGates(ServerPlayer player) {
        UUID id = player.getUUID();
        if (!EVALUATING.add(id)) {
            DIRTY.add(id);
            return;
        }
        try {
            ensureInitialized(player);
            BendingProgression p = get(player);
            for (int pass = 0; pass < MAX_EVALUATION_PASSES; pass++) {
                DIRTY.remove(id);
                boolean cleared = false;
                for (NarrativeGate gate : ProgressionConfig.get().gates().values()) {
                    if (!p.hasClearedGate(gate.id()) && gate.requires().test(player)) {
                        clearGate(player, gate);
                        cleared = true;
                    }
                }
                if (!cleared && !DIRTY.contains(id)) {
                    return;
                }
            }
            AtlaCore.LOGGER.warn("Narrative gates for {} were still changing after {} passes - check for gates that "
                    + "keep re-triggering each other", player.getGameProfile().getName(), MAX_EVALUATION_PASSES);
        } finally {
            EVALUATING.remove(id);
            DIRTY.remove(id);
        }
    }

    /** Clears a gate and applies its rewards, ignoring its requirements. Returns false if it was already cleared. */
    public static boolean clearGate(ServerPlayer player, NarrativeGate gate) {
        BendingProgression p = get(player);
        if (!p.markGateCleared(gate.id())) {
            return false;
        }
        AtlaCore.LOGGER.info("{} cleared narrative gate '{}'", player.getGameProfile().getName(), gate.id());
        ProgressionConfig cfg = ProgressionConfig.get();
        boolean levelsChanged = false;
        for (Element e : gate.unlock()) {
            if (p.level(e) == 0) {
                levelsChanged |= applyLevel(player, e, cfg.element(e).startLevel(), true);
            }
        }
        for (Map.Entry<Element, Integer> e : gate.levels().entrySet()) {
            if (p.level(e.getKey()) < e.getValue()) {
                levelsChanged |= applyLevel(player, e.getKey(), e.getValue(), true);
            }
        }
        if (levelsChanged) {
            sync(player);
        }
        for (String flag : gate.removeFlags()) {
            removeFlag(player, flag);
        }
        for (String flag : gate.addFlags()) {
            addFlag(player, flag);
        }
        gate.announce().show(player, placeholders(player, null, 0));
        runCommands(player, gate.commands());
        MinecraftForge.EVENT_BUS.post(new NarrativeGateClearedEvent(player, gate.id()));
        return true;
    }

    /** Forgets that a gate was cleared (its rewards are NOT taken back). It may clear again on the next evaluation. */
    public static boolean unclearGate(ServerPlayer player, String gateId) {
        return get(player).unmarkGateCleared(gateId);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs commands as the player with operator permission and no chat output. {@code {player}} is
     * replaced with the player's name; {@code @s} also works.
     */
    public static void runCommands(ServerPlayer player, List<String> commands) {
        if (commands.isEmpty()) {
            return;
        }
        CommandSourceStack source = player.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        for (String command : commands) {
            String resolved = command.replace("{player}", player.getGameProfile().getName());
            try {
                player.server.getCommands().performPrefixedCommand(source, resolved);
            } catch (RuntimeException e) {
                AtlaCore.LOGGER.error("Story command failed: {}", resolved, e);
            }
        }
    }

    public static Map<String, String> placeholders(ServerPlayer player, @Nullable Element element, int level) {
        Map<String, String> map = new HashMap<>();
        map.put("player", player.getGameProfile().getName());
        if (element != null) {
            map.put("element", element.displayName().getString());
            map.put("level", String.valueOf(level));
            map.put("levelName", ProgressionConfig.get().levelName(level));
        }
        return map;
    }

    /** Keeps Game Stages in step with element levels: "atla_water" and "atla_water_1".."atla_water_N". */
    private static void mirrorElementStages(ServerPlayer player, Element element) {
        ProgressionConfig cfg = ProgressionConfig.get();
        if (!GameStagesCompat.isLoaded() || !cfg.settings().mirrorElementsToStages()) {
            return;
        }
        String base = cfg.settings().stagePrefix() + element.id();
        int level = get(player).level(element);
        setStage(player, base, level > 0);
        for (int i = 1; i <= cfg.maxLevel(element); i++) {
            setStage(player, base + "_" + i, level >= i);
        }
    }

    private static void setStage(ServerPlayer player, String stage, boolean wanted) {
        boolean has = GameStagesCompat.hasStage(player, stage);
        if (wanted && !has) {
            GameStagesCompat.addStage(player, stage);
        } else if (!wanted && has) {
            GameStagesCompat.removeStage(player, stage);
        }
    }

    public static void resyncStages(ServerPlayer player) {
        for (Element e : Element.values()) {
            mirrorElementStages(player, e);
        }
    }

    /** Sends the player's element levels and selection to their client for the HUD. */
    public static void sync(ServerPlayer player) {
        if (player.connection == null) {
            return;
        }
        AtlaNetwork.sendTo(player, SyncProgressionPacket.of(get(player), ProgressionConfig.get()));
    }
}
