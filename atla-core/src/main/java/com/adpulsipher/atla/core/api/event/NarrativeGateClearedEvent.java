package com.adpulsipher.atla.core.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;

/** Fired on the Forge event bus after a narrative gate was cleared and its rewards applied. */
public class NarrativeGateClearedEvent extends PlayerEvent {
    private final String gateId;

    public NarrativeGateClearedEvent(ServerPlayer player, String gateId) {
        super(player);
        this.gateId = gateId;
    }

    public String getGateId() {
        return gateId;
    }
}
