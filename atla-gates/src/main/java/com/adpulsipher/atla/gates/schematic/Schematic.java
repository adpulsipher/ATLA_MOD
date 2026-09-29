package com.adpulsipher.atla.gates.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * A loaded schematic, independent of file format. Blocks are stored as palette indices in
 * {@code x + z * width + y * width * length} order (the Sponge layout); index {@code -1} means
 * "leave the world block alone" (vanilla structure voids).
 *
 * @param pasteOffset offset from the paste point to the minimum corner, exactly what WorldEdit's
 *                    {@code //paste} applies (the player's position when they ran {@code //copy})
 * @param originalMin absolute minimum corner the region was copied from, if the file records it;
 *                    lets an override paste "back where it came from" like {@code //paste -o}
 */
public record Schematic(String name, int width, int height, int length, BlockState[] palette, int[] blocks,
                        Map<Integer, CompoundTag> blockEntities, BlockPos pasteOffset, @Nullable BlockPos originalMin) {

    public int index(int x, int y, int z) {
        return x + z * width + y * width * length;
    }

    public int volume() {
        return width * height * length;
    }
}
