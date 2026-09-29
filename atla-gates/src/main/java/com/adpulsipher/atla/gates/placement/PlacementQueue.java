package com.adpulsipher.atla.gates.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Places block swaps a slice per server tick so they animate. If the server stops mid-animation,
 * the remaining blocks are placed immediately so the world is never left half-bent.
 */
public final class PlacementQueue {
    private static final List<Job> JOBS = new ArrayList<>();

    private PlacementQueue() {
    }

    private static final class Job {
        final ServerLevel level;
        final List<BlockChange> changes;
        final int perTick;
        final Effects effects;
        final int flags;
        int cursor;

        Job(ServerLevel level, List<BlockChange> changes, int perTick, Effects effects, int flags) {
            this.level = level;
            this.changes = changes;
            this.perTick = perTick;
            this.effects = effects;
            this.flags = flags;
        }

        boolean step(int budget) {
            int end = Math.min(changes.size(), cursor + budget);
            for (; cursor < end; cursor++) {
                BlockChange change = changes.get(cursor);
                BlockState old = place(level, change, flags);
                if (old != null) {
                    playBlockEffect(level, change.pos(), old, effects);
                }
            }
            return cursor >= changes.size();
        }
    }

    /**
     * Queues changes (already sorted by {@link Animation#sort}). {@code perTick >= changes.size()} places
     * everything right now, in this tick.
     */
    public static void submit(ServerLevel level, List<BlockChange> changes, int perTick, Effects effects, boolean updateNeighbors) {
        if (changes.isEmpty()) {
            return;
        }
        int flags = updateNeighbors ? Block.UPDATE_ALL : Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        Job job = new Job(level, changes, Math.max(1, perTick), effects, flags);
        if (!job.step(job.perTick)) {
            JOBS.add(job);
        }
    }

    public static boolean isBusy() {
        return !JOBS.isEmpty();
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || JOBS.isEmpty()) {
            return;
        }
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext()) {
            Job job = it.next();
            if (job.step(job.perTick)) {
                it.remove();
            }
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        flush();
    }

    /** Finishes every pending animation right now. */
    public static void flush() {
        for (Job job : JOBS) {
            job.step(Integer.MAX_VALUE);
        }
        JOBS.clear();
    }

    /**
     * Sets one block the way structure placement does: containers are emptied first so nothing drops
     * as items, then block-entity data (signs, chests, banners...) is applied.
     *
     * @return the previous state, or null if nothing changed
     */
    public static BlockState place(ServerLevel level, BlockChange change, int flags) {
        BlockPos pos = change.pos();
        if (!level.isInWorldBounds(pos)) {
            return null;
        }
        BlockState old = level.getBlockState(pos);
        boolean sameState = old == change.state();
        if (!sameState) {
            BlockEntity oldBe = level.getBlockEntity(pos);
            Clearable.tryClear(oldBe);
            level.setBlock(pos, change.state(), flags);
        }
        if (change.blockEntity() != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                CompoundTag tag = change.blockEntity().copy();
                tag.putInt("x", pos.getX());
                tag.putInt("y", pos.getY());
                tag.putInt("z", pos.getZ());
                be.load(tag);
                be.setChanged();
                level.sendBlockUpdated(pos, change.state(), change.state(), Block.UPDATE_CLIENTS);
            }
        }
        return sameState && change.blockEntity() == null ? null : old;
    }

    private static void playBlockEffect(ServerLevel level, BlockPos pos, BlockState old, Effects effects) {
        if (effects.particleChance() <= 0 || level.random.nextFloat() >= effects.particleChance()) {
            return;
        }
        if (effects.breakParticles() && !old.isAir()) {
            level.levelEvent(2001, pos, Block.getId(old)); // vanilla "block broken" sound + particles
        }
        if (effects.particle() != null) {
            level.sendParticles(effects.particle(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    3, 0.3, 0.3, 0.3, 0.02);
        }
    }
}
