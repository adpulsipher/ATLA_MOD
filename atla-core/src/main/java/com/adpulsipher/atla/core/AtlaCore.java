package com.adpulsipher.atla.core;

import com.adpulsipher.atla.core.api.event.AtlaReloadEvent;
import com.adpulsipher.atla.core.client.ClientConfig;
import com.adpulsipher.atla.core.client.ClientEvents;
import com.adpulsipher.atla.core.command.AtlaCommands;
import com.adpulsipher.atla.core.compat.GameStagesCompat;
import com.adpulsipher.atla.core.config.ProgressionConfig;
import com.adpulsipher.atla.core.data.ProgressionCapability;
import com.adpulsipher.atla.core.logic.ProgressionManager;
import com.adpulsipher.atla.core.network.AtlaNetwork;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * ATLA Core: the story-progression brain of the Avatar adventure map.
 *
 * <p>Each player has four elements (level 0 = locked), a selected "active" element, a set of story
 * flags and a record of cleared narrative gates. Aang starts with Airbending only; everything else
 * unlocks when the gates in {@code config/story_progression.json} are cleared - typically because a
 * quest mod ran {@code /atla flag add @s <flag>} as a reward.</p>
 */
@Mod(AtlaCore.MOD_ID)
public final class AtlaCore {
    public static final String MOD_ID = "atla_core";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static int tickCounter;

    // FMLJavaModLoadingContext.get()/ModLoadingContext.get() are deprecated in late 47.x builds but are the
    // only option that works on every Forge 47 release, so they are kept on purpose.
    @SuppressWarnings("removal")
    public AtlaCore() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(ProgressionCapability::onRegisterCapabilities);
        modBus.addListener(this::onCommonSetup);

        IEventBus forge = MinecraftForge.EVENT_BUS;
        forge.addGenericListener(net.minecraft.world.entity.Entity.class, ProgressionCapability::onAttach);
        forge.addListener(ProgressionCapability::onClone);
        forge.addListener(AtlaCore::onRegisterCommands);
        forge.addListener(AtlaCore::onServerAboutToStart);
        forge.addListener(AtlaCore::onAddReloadListeners);
        forge.addListener(AtlaCore::onLogin);
        forge.addListener(AtlaCore::onRespawn);
        forge.addListener(AtlaCore::onChangeDimension);
        forge.addListener(AtlaCore::onAdvancement);
        forge.addListener(AtlaCore::onServerTick);

        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        if (FMLEnvironment.dist.isClient()) {
            ClientEvents.init(modBus);
        }
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        AtlaNetwork.register();
        event.enqueueWork(GameStagesCompat::init);
    }

    /**
     * Re-reads story_progression.json and lets addons (atla_gates) reload theirs via {@link AtlaReloadEvent},
     * then re-evaluates every online player. Returns human-readable problems.
     */
    public static List<String> reloadAll(MinecraftServer server) {
        List<String> errors = new ArrayList<>(ProgressionConfig.load());
        AtlaReloadEvent event = new AtlaReloadEvent(server);
        MinecraftForge.EVENT_BUS.post(event);
        errors.addAll(event.getErrors());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ProgressionManager.evaluateGates(player);
            ProgressionManager.resyncStages(player);
            ProgressionManager.sync(player);
        }
        return errors;
    }

    private static void onServerAboutToStart(ServerAboutToStartEvent event) {
        reloadAll(event.getServer());
    }

    /** Makes vanilla /reload re-read our JSON too (the first load at world start is skipped: the server isn't up yet). */
    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener((ResourceManagerReloadListener) resourceManager -> {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null && server.isRunning()) {
                server.execute(() -> reloadAll(server));
            }
        });
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        AtlaCommands.register(event.getDispatcher());
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ProgressionManager.ensureInitialized(player);
            ProgressionManager.evaluateGates(player);
            ProgressionManager.resyncStages(player);
            ProgressionManager.sync(player);
        }
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ProgressionManager.sync(player);
        }
    }

    private static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ProgressionManager.sync(player);
        }
    }

    private static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ProgressionManager.evaluateGates(player);
        }
    }

    /** Periodic re-check so gates that depend on /tag, scoreboards or Game Stages clear without an explicit trigger. */
    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter % ProgressionConfig.get().settings().evaluateEveryTicks() != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            ProgressionManager.evaluateGates(player);
        }
    }
}
