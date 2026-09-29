package com.adpulsipher.atla.gates.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** One block to set, with optional block-entity data (chests, signs, banners...). */
public record BlockChange(BlockPos pos, BlockState state, @Nullable CompoundTag blockEntity) {
}
