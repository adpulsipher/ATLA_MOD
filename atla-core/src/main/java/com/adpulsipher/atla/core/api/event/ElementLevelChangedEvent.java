package com.adpulsipher.atla.core.api.event;

import com.adpulsipher.atla.core.api.Element;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;

/**
 * Fired on the Forge event bus after a player's level in an element changed.
 * {@code oldLevel == 0} means the element was just unlocked; {@code newLevel == 0} means it was locked.
 */
public class ElementLevelChangedEvent extends PlayerEvent {
    private final Element element;
    private final int oldLevel;
    private final int newLevel;

    public ElementLevelChangedEvent(ServerPlayer player, Element element, int oldLevel, int newLevel) {
        super(player);
        this.element = element;
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
    }

    public Element getElement() {
        return element;
    }

    public int getOldLevel() {
        return oldLevel;
    }

    public int getNewLevel() {
        return newLevel;
    }

    public boolean isUnlock() {
        return oldLevel == 0 && newLevel > 0;
    }

    public boolean isLock() {
        return oldLevel > 0 && newLevel == 0;
    }
}
