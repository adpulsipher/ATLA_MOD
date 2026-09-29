package com.adpulsipher.atla.core.network;

import com.adpulsipher.atla.core.AtlaCore;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class AtlaNetwork {
    private static final String PROTOCOL = "1";

    @SuppressWarnings("removal")
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(AtlaCore.MOD_ID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private AtlaNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(SyncProgressionPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SyncProgressionPacket::encode)
                .decoder(SyncProgressionPacket::decode)
                .consumerMainThread(SyncProgressionPacket::handle)
                .add();
        CHANNEL.messageBuilder(SelectElementPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SelectElementPacket::encode)
                .decoder(SelectElementPacket::decode)
                .consumerMainThread(SelectElementPacket::handle)
                .add();
    }

    public static void sendTo(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }
}
