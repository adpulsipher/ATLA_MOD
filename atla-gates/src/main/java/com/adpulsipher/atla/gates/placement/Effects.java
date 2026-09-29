package com.adpulsipher.atla.gates.placement;

import com.adpulsipher.atla.core.api.Element;
import com.adpulsipher.atla.core.util.ConfigException;
import com.adpulsipher.atla.core.util.Json;
import com.adpulsipher.atla.core.util.Sounds;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.ParticleArgument;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Sound and particles for a block swap. Each element has a default "feel"; any field can be
 * overridden per override: {@code {"sound": "...", "particle": "minecraft:flame", "particleChance": 0.4,
 * "breakParticles": true}}. Set {@code "particle": ""} to turn particles off.
 *
 * @param sound          played once where the player bent
 * @param particle       spawned at a share of the changed blocks as they change
 * @param particleChance share (0..1) of changed blocks that get a particle puff
 * @param breakParticles also play the vanilla break effect of the block being removed (earth crumbling)
 */
public record Effects(@Nullable Sounds sound, @Nullable ParticleOptions particle, float particleChance,
                      boolean breakParticles) {

    public static final Effects NONE = new Effects(null, null, 0f, false);

    public static Effects forElement(@Nullable Element element) {
        if (element == null) {
            return new Effects(sound("block.amethyst_block.chime", 1f, 0.8f), ParticleTypes.END_ROD, 0.2f, false);
        }
        return switch (element) {
            case AIR -> new Effects(sound("entity.phantom.flap", 1f, 0.6f), ParticleTypes.CLOUD, 0.35f, false);
            case WATER -> new Effects(sound("entity.player.splash", 0.8f, 1.2f), ParticleTypes.SPLASH, 0.5f, false);
            case EARTH -> new Effects(sound("block.stone.break", 1f, 0.6f), null, 0.3f, true);
            case FIRE -> new Effects(sound("item.firecharge.use", 1f, 1f), ParticleTypes.FLAME, 0.35f, false);
        };
    }

    public static Effects read(@Nullable JsonObject o, @Nullable Element element, String where) throws ConfigException {
        Effects base = forElement(element);
        if (o == null) {
            return base;
        }
        Sounds snd = o.has("sound") ? Sounds.read(o.get("sound"), where + " effects.sound") : base.sound;
        ParticleOptions particle = base.particle;
        if (o.has("particle")) {
            String raw = Json.string(o, "particle", "");
            particle = raw.isBlank() ? null : parseParticle(raw, where);
        }
        return new Effects(snd, particle, (float) Json.number(o, "particleChance", base.particleChance),
                Json.bool(o, "breakParticles", base.breakParticles));
    }

    public static ParticleOptions parseParticle(String raw, String where) throws ConfigException {
        try {
            return ParticleArgument.readParticle(new StringReader(raw), BuiltInRegistries.PARTICLE_TYPE.asLookup());
        } catch (CommandSyntaxException e) {
            throw new ConfigException(where + ": '" + raw + "' is not a valid particle (" + e.getMessage() + ")");
        }
    }

    @SuppressWarnings("removal")
    private static Sounds sound(String path, float volume, float pitch) {
        return new Sounds(new ResourceLocation("minecraft", path), volume, pitch);
    }
}
