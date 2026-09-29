package com.adpulsipher.atla.core.config;

import com.adpulsipher.atla.core.util.ConfigException;
import com.adpulsipher.atla.core.util.Json;
import com.adpulsipher.atla.core.util.Sounds;
import com.adpulsipher.atla.core.util.Text;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Optional on-screen feedback: {@code {"title", "subtitle", "actionbar", "message", "sound"}}.
 * Text uses {@link Text} rules (plain text with &amp; colour codes, or JSON components).
 */
public record Announcement(@Nullable String title, @Nullable String subtitle, @Nullable String actionbar,
                           @Nullable String message, @Nullable Sounds sound) {
    public static final Announcement NONE = new Announcement(null, null, null, null, null);

    public static Announcement read(@Nullable JsonObject o, String where) throws ConfigException {
        if (o == null) {
            return NONE;
        }
        return new Announcement(Json.string(o, "title", null), Json.string(o, "subtitle", null),
                Json.string(o, "actionbar", null), Json.string(o, "message", null), Sounds.read(o.get("sound"), where + " sound"));
    }

    public boolean isEmpty() {
        return title == null && subtitle == null && actionbar == null && message == null && sound == null;
    }

    public void show(ServerPlayer player, Map<String, String> placeholders) {
        Component t = Text.parse(title, placeholders);
        Component s = Text.parse(subtitle, placeholders);
        if (t != null || s != null) {
            showTitle(player, t != null ? t : Component.empty(), s);
        }
        Component bar = Text.parse(actionbar, placeholders);
        if (bar != null) {
            player.connection.send(new ClientboundSetActionBarTextPacket(bar));
        }
        Component msg = Text.parse(message, placeholders);
        if (msg != null) {
            player.sendSystemMessage(msg);
        }
        if (sound != null) {
            sound.playFor(player);
        }
    }

    public static void showTitle(ServerPlayer player, Component title, @Nullable Component subtitle) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
        if (subtitle != null) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        }
        player.connection.send(new ClientboundSetTitleTextPacket(title));
    }
}
