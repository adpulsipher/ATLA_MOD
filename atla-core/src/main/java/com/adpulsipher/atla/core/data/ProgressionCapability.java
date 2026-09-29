package com.adpulsipher.atla.core.data;

import com.adpulsipher.atla.core.AtlaCore;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Attaches {@link BendingProgression} to every player and keeps it across death and
 * End-portal "respawns".
 */
public final class ProgressionCapability {
    public static final Capability<BendingProgression> CAPABILITY = CapabilityManager.get(new CapabilityToken<>() {});
    @SuppressWarnings("removal")
    private static final ResourceLocation KEY = new ResourceLocation(AtlaCore.MOD_ID, "progression");

    private ProgressionCapability() {
    }

    /** Never returns null; a player that somehow lacks the capability gets a detached, empty instance. */
    public static BendingProgression get(Player player) {
        return player.getCapability(CAPABILITY).orElseGet(BendingProgression::new);
    }

    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.register(BendingProgression.class);
    }

    public static void onAttach(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player) {
            event.addCapability(KEY, new Provider());
        }
    }

    public static void onClone(PlayerEvent.Clone event) {
        Player original = event.getOriginal();
        original.reviveCaps();
        try {
            original.getCapability(CAPABILITY).ifPresent(old ->
                    event.getEntity().getCapability(CAPABILITY).ifPresent(fresh -> fresh.copyFrom(old)));
        } finally {
            original.invalidateCaps();
        }
    }

    private static final class Provider implements ICapabilitySerializable<CompoundTag> {
        private final BendingProgression data = new BendingProgression();
        private final LazyOptional<BendingProgression> optional = LazyOptional.of(() -> data);

        @Override
        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
            return CAPABILITY.orEmpty(cap, optional);
        }

        @Override
        public CompoundTag serializeNBT() {
            return data.save();
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            data.load(nbt);
        }
    }
}
