package com.adpulsipher.atla.core.network;

import com.adpulsipher.atla.core.api.Element;
import com.adpulsipher.atla.core.config.ProgressionConfig;
import com.adpulsipher.atla.core.logic.ProgressionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server: the player pressed the element key. {@code mode} is NEXT, PREVIOUS or a
 * specific element ordinal (SELECT). The server validates that the element is unlocked.
 */
public record SelectElementPacket(int mode, int element) {
    public static final int NEXT = 0;
    public static final int PREVIOUS = 1;
    public static final int SELECT = 2;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(mode);
        buf.writeVarInt(element);
    }

    public static SelectElementPacket decode(FriendlyByteBuf buf) {
        return new SelectElementPacket(buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (player == null || !ProgressionConfig.get().settings().allowPlayerElementSwitching()) {
            return;
        }
        switch (mode) {
            case NEXT -> ProgressionManager.cycle(player, 1);
            case PREVIOUS -> ProgressionManager.cycle(player, -1);
            case SELECT -> {
                Element[] all = Element.values();
                if (element >= 0 && element < all.length) {
                    ProgressionManager.select(player, all[element]);
                }
            }
            default -> {
            }
        }
    }
}
