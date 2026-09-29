package com.adpulsipher.atla.gates.placement;

import com.adpulsipher.atla.gates.AtlaGates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Before an override changes the world, the affected blocks are saved to
 * {@code <world>/atla_gates/undo/<override id>.dat}. {@code /atla override reset <id>} puts them back,
 * which makes testing a map much less painful. (Players never see this.)
 */
public final class UndoStore {
    private UndoStore() {
    }

    private static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("atla_gates").resolve("undo");
    }

    private static Path file(MinecraftServer server, String id) {
        return dir(server).resolve(id.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".dat");
    }

    public static boolean exists(MinecraftServer server, String id) {
        return Files.isRegularFile(file(server, id));
    }

    /** Records the current world state at every position the changes will touch. */
    public static void capture(ServerLevel level, String id, List<BlockChange> changes) {
        Map<BlockState, Integer> paletteIndex = new HashMap<>();
        ListTag palette = new ListTag();
        long[] positions = new long[changes.size()];
        int[] states = new int[changes.size()];
        ListTag blockEntities = new ListTag();
        for (int i = 0; i < changes.size(); i++) {
            BlockPos pos = changes.get(i).pos();
            BlockState state = level.getBlockState(pos);
            Integer idx = paletteIndex.get(state);
            if (idx == null) {
                idx = palette.size();
                paletteIndex.put(state, idx);
                palette.add(NbtUtils.writeBlockState(state));
            }
            positions[i] = pos.asLong();
            states[i] = idx;
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                CompoundTag entry = new CompoundTag();
                entry.putInt("Index", i);
                entry.put("Data", be.saveWithFullMetadata());
                blockEntities.add(entry);
            }
        }
        CompoundTag root = new CompoundTag();
        root.putString("Dimension", level.dimension().location().toString());
        root.put("Palette", palette);
        root.putLongArray("Positions", positions);
        root.putIntArray("States", states);
        root.put("BlockEntities", blockEntities);
        Path target = file(level.getServer(), id);
        try {
            Files.createDirectories(target.getParent());
            NbtIo.writeCompressed(root, target.toFile());
        } catch (IOException e) {
            AtlaGates.LOGGER.error("Could not write undo snapshot for override '{}'", id, e);
        }
    }

    /** Restores a snapshot instantly and deletes it. Returns the number of blocks restored, or -1 if there was none. */
    public static int restore(MinecraftServer server, String id) {
        Path source = file(server, id);
        if (!Files.isRegularFile(source)) {
            return -1;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(source.toFile());
            @SuppressWarnings("removal")
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(root.getString("Dimension")));
            ServerLevel level = server.getLevel(dim);
            if (level == null) {
                AtlaGates.LOGGER.error("Undo snapshot for '{}' is for missing dimension {}", id, dim.location());
                return -1;
            }
            ListTag paletteTag = root.getList("Palette", Tag.TAG_COMPOUND);
            BlockState[] palette = new BlockState[paletteTag.size()];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteTag.getCompound(i));
            }
            long[] positions = root.getLongArray("Positions");
            int[] states = root.getIntArray("States");
            Map<Integer, CompoundTag> bes = new HashMap<>();
            ListTag beList = root.getList("BlockEntities", Tag.TAG_COMPOUND);
            for (int i = 0; i < beList.size(); i++) {
                bes.put(beList.getCompound(i).getInt("Index"), beList.getCompound(i).getCompound("Data"));
            }
            List<BlockChange> changes = new ArrayList<>(positions.length);
            for (int i = 0; i < positions.length; i++) {
                changes.add(new BlockChange(BlockPos.of(positions[i]), palette[states[i]], bes.get(i)));
            }
            for (BlockChange change : changes) {
                PlacementQueue.place(level, change, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
            Files.delete(source);
            return changes.size();
        } catch (IOException e) {
            AtlaGates.LOGGER.error("Could not restore undo snapshot for override '{}'", id, e);
            return -1;
        }
    }
}
