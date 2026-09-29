package com.adpulsipher.atla.gates.util;

import com.adpulsipher.atla.core.util.ConfigException;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Either;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Matches block states the way /fill ... replace does: {@code "minecraft:ice"},
 * {@code "#minecraft:ice"} (block tag) or {@code "minecraft:oak_log[axis=y]"} (only the listed
 * properties must match).
 */
public final class BlockMatcher implements Predicate<BlockState> {
    private final String raw;
    private final Predicate<BlockState> test;

    private BlockMatcher(String raw, Predicate<BlockState> test) {
        this.raw = raw;
        this.test = test;
    }

    public static BlockMatcher parse(String raw, String where) throws ConfigException {
        Either<BlockStateParser.BlockResult, BlockStateParser.TagResult> parsed;
        try {
            parsed = BlockStateParser.parseForTesting(BuiltInRegistries.BLOCK.asLookup(), raw, false);
        } catch (CommandSyntaxException e) {
            throw new ConfigException(where + ": '" + raw + "' is not a block or #tag (" + e.getMessage() + ")");
        }
        return parsed.map(block -> {
            Block b = block.blockState().getBlock();
            Map<Property<?>, Comparable<?>> props = block.properties();
            return new BlockMatcher(raw, state -> state.is(b) && props.entrySet().stream()
                    .allMatch(e -> Objects.equals(state.getValue(e.getKey()), e.getValue())));
        }, tag -> {
            HolderSet<Block> set = tag.tag();
            Map<String, String> props = tag.vagueProperties();
            return new BlockMatcher(raw, state -> state.is(set) && props.entrySet().stream().allMatch(e -> {
                Property<?> p = state.getBlock().getStateDefinition().getProperty(e.getKey());
                return p != null && valueName(state, p).equals(e.getValue());
            }));
        });
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    public static BlockState parseState(String raw, String where) throws ConfigException {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), raw, false).blockState();
        } catch (CommandSyntaxException e) {
            throw new ConfigException(where + ": '" + raw + "' is not a valid block state (" + e.getMessage() + ")");
        }
    }

    @Override
    public boolean test(BlockState state) {
        return test.test(state);
    }

    @Override
    public String toString() {
        return raw;
    }
}
