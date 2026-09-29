package com.adpulsipher.atla.core.api.event;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.Event;

import java.util.ArrayList;
import java.util.List;

/**
 * Fired on the Forge event bus when {@code /atla reload} or vanilla {@code /reload} runs, so addon
 * mods (like atla_gates) can reload their own JSON files. Listeners report problems with {@link #addError}.
 */
public class AtlaReloadEvent extends Event {
    private final MinecraftServer server;
    private final List<String> errors = new ArrayList<>();

    public AtlaReloadEvent(MinecraftServer server) {
        this.server = server;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public void addError(String error) {
        errors.add(error);
    }

    public void addErrors(List<String> list) {
        errors.addAll(list);
    }

    public List<String> getErrors() {
        return errors;
    }
}
