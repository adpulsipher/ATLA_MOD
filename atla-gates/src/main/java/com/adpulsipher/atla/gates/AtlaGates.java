package com.adpulsipher.atla.gates;

import com.adpulsipher.atla.core.api.event.AtlaReloadEvent;
import com.adpulsipher.atla.gates.command.GatesCommands;
import com.adpulsipher.atla.gates.override.OverrideConfig;
import com.adpulsipher.atla.gates.override.OverrideHandler;
import com.adpulsipher.atla.gates.placement.PlacementQueue;
import com.adpulsipher.atla.gates.zone.ZoneConfig;
import com.adpulsipher.atla.gates.zone.ZoneEnforcer;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * ATLA Gates: the world-side half of the story mod.
 *
 * <ul>
 *   <li><b>Bendable blocks</b> ({@code config/schematic_overrides.json}): right-clicking a designated
 *       block with the right element at the right level swaps blocks in the world, from a WorldEdit
 *       {@code .schem} or with direct fill/replace/setblock operations.</li>
 *   <li><b>Boundaries</b> ({@code config/story_boundaries.json}): keeps players out of unfinished
 *       parts of the map until the story opens them.</li>
 * </ul>
 *
 * Configs are (re)loaded through atla_core's reload cycle: server start, {@code /atla reload} and {@code /reload}.
 */
@Mod(AtlaGates.MOD_ID)
public final class AtlaGates {
    public static final String MOD_ID = "atla_gates";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AtlaGates() {
        IEventBus forge = MinecraftForge.EVENT_BUS;
        forge.addListener(AtlaGates::onReload);
        forge.addListener(AtlaGates::onRegisterCommands);
        // HIGH so a bending click is claimed before blocks like doors/trapdoors/levers react to it
        forge.addListener(EventPriority.HIGH, OverrideHandler::onRightClickBlock);
        forge.addListener(PlacementQueue::onServerTick);
        forge.addListener(PlacementQueue::onServerStopping);
        forge.addListener(ZoneEnforcer::onPlayerTick);
        forge.addListener(ZoneEnforcer::onLogout);
        forge.addListener(ZoneEnforcer::onChangeDimension);
    }

    private static void onReload(AtlaReloadEvent event) {
        event.addErrors(OverrideConfig.load());
        event.addErrors(ZoneConfig.load());
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        GatesCommands.register(event.getDispatcher());
    }
}
