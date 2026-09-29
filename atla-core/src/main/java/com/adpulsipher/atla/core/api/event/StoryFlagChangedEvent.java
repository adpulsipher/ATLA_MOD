package com.adpulsipher.atla.core.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;

/** Fired on the Forge event bus after a story flag was added to or removed from a player. */
public class StoryFlagChangedEvent extends PlayerEvent {
    private final String flag;
    private final boolean added;

    public StoryFlagChangedEvent(ServerPlayer player, String flag, boolean added) {
        super(player);
        this.flag = flag;
        this.added = added;
    }

    public String getFlag() {
        return flag;
    }

    public boolean isAdded() {
        return added;
    }
}
