package com.adpulsipher.atla.core.api.event;

import com.adpulsipher.atla.core.api.Element;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

/** Fired on the Forge event bus after the player switched the element shown on their HUD. */
public class ActiveElementChangedEvent extends PlayerEvent {
    @Nullable
    private final Element previous;
    @Nullable
    private final Element current;

    public ActiveElementChangedEvent(ServerPlayer player, @Nullable Element previous, @Nullable Element current) {
        super(player);
        this.previous = previous;
        this.current = current;
    }

    @Nullable
    public Element getPrevious() {
        return previous;
    }

    @Nullable
    public Element getCurrent() {
        return current;
    }
}
