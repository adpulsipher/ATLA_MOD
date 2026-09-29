package com.adpulsipher.atla.core.network;

import com.adpulsipher.atla.core.api.Element;
import com.adpulsipher.atla.core.client.ClientProgression;
import com.adpulsipher.atla.core.config.ProgressionConfig;
import com.adpulsipher.atla.core.data.BendingProgression;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client: element levels, max levels and the selected element, for the HUD.
 * Story flags are deliberately not sent (no spoilers in the client).
 */
public record SyncProgressionPacket(int[] levels, int[] maxLevels, int active) {

    public static SyncProgressionPacket of(BendingProgression p, ProgressionConfig cfg) {
        Element[] all = Element.values();
        int[] levels = new int[all.length];
        int[] max = new int[all.length];
        for (Element e : all) {
            levels[e.ordinal()] = p.level(e);
            max[e.ordinal()] = cfg.maxLevel(e);
        }
        return new SyncProgressionPacket(levels, max, p.active() == null ? -1 : p.active().ordinal());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarIntArray(levels);
        buf.writeVarIntArray(maxLevels);
        buf.writeVarInt(active);
    }

    public static SyncProgressionPacket decode(FriendlyByteBuf buf) {
        return new SyncProgressionPacket(buf.readVarIntArray(16), buf.readVarIntArray(16), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientProgression.apply(this));
        ctx.get().setPacketHandled(true);
    }
}
