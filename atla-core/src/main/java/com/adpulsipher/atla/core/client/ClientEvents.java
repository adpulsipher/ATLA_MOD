package com.adpulsipher.atla.core.client;

import com.adpulsipher.atla.core.api.Element;
import com.adpulsipher.atla.core.network.AtlaNetwork;
import com.adpulsipher.atla.core.network.SelectElementPacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.lwjgl.glfw.GLFW;

/** Client-only wiring: keybinds and the HUD overlay. Only ever loaded on the physical client. */
public final class ClientEvents {
    private static final String CATEGORY = "key.categories.atla_core";
    public static final KeyMapping NEXT_ELEMENT = new KeyMapping("key.atla_core.next_element",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY);
    public static final KeyMapping PREVIOUS_ELEMENT = new KeyMapping("key.atla_core.previous_element",
            KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, CATEGORY);
    private static final KeyMapping[] SELECT = new KeyMapping[Element.values().length];

    static {
        for (Element e : Element.values()) {
            SELECT[e.ordinal()] = new KeyMapping("key.atla_core.select_" + e.id(),
                    KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, CATEGORY);
        }
    }

    private ClientEvents() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(ClientEvents::onRegisterKeys);
        modBus.addListener(ClientEvents::onRegisterOverlays);
        MinecraftForge.EVENT_BUS.addListener(ClientEvents::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(ClientEvents::onLoggedOut);
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(NEXT_ELEMENT);
        event.register(PREVIOUS_ELEMENT);
        for (KeyMapping key : SELECT) {
            event.register(key);
        }
    }

    private static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "bending_element", ElementHud::render);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getInstance().player == null) {
            return;
        }
        while (NEXT_ELEMENT.consumeClick()) {
            AtlaNetwork.sendToServer(new SelectElementPacket(SelectElementPacket.NEXT, 0));
        }
        while (PREVIOUS_ELEMENT.consumeClick()) {
            AtlaNetwork.sendToServer(new SelectElementPacket(SelectElementPacket.PREVIOUS, 0));
        }
        for (Element e : Element.values()) {
            while (SELECT[e.ordinal()].consumeClick()) {
                if (ClientProgression.isUnlocked(e)) {
                    AtlaNetwork.sendToServer(new SelectElementPacket(SelectElementPacket.SELECT, e.ordinal()));
                }
            }
        }
    }

    private static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientProgression.clear();
    }
}
