package com.adpulsipher.atla.gates.placement;

import com.adpulsipher.atla.core.util.ConfigException;
import com.adpulsipher.atla.core.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * How a block swap plays out over time, so bending looks like bending instead of a pop:
 * {@code {"order": "outward", "blocksPerTick": 48}}.
 *
 * <ul>
 *   <li>{@code instant} - everything in one tick</li>
 *   <li>{@code outward} - spreads from the block the player clicked (ice melting from the touch)</li>
 *   <li>{@code top_down} - highest layer first (a wall sinking into the ground)</li>
 *   <li>{@code bottom_up} - lowest layer first (a pillar rising)</li>
 *   <li>{@code random} - scattered (a rock wall crumbling)</li>
 * </ul>
 */
public record Animation(Order order, int blocksPerTick) {
    public enum Order { INSTANT, OUTWARD, TOP_DOWN, BOTTOM_UP, RANDOM }

    public static final Animation DEFAULT = new Animation(Order.OUTWARD, 48);

    public static Animation read(@Nullable JsonObject o, Animation fallback, String where) throws ConfigException {
        if (o == null) {
            return fallback;
        }
        String raw = Json.string(o, "order", fallback.order.name());
        Order order;
        try {
            order = Order.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(where + ": animation order '" + raw + "' must be instant, outward, top_down, bottom_up or random");
        }
        return new Animation(order, Math.max(1, Json.integer(o, "blocksPerTick", fallback.blocksPerTick)));
    }

    /** Returns the changes in the order they should be placed. */
    public List<BlockChange> sort(List<BlockChange> changes, BlockPos clicked, RandomSource random) {
        List<BlockChange> list = new ArrayList<>(changes);
        switch (order) {
            case OUTWARD -> list.sort(Comparator.comparingDouble(c -> c.pos().distSqr(clicked)));
            case TOP_DOWN -> list.sort(Comparator.comparingInt((BlockChange c) -> -c.pos().getY()));
            case BOTTOM_UP -> list.sort(Comparator.comparingInt((BlockChange c) -> c.pos().getY()));
            case RANDOM -> {
                for (int i = list.size() - 1; i > 0; i--) {
                    int j = random.nextInt(i + 1);
                    BlockChange tmp = list.get(i);
                    list.set(i, list.get(j));
                    list.set(j, tmp);
                }
            }
            default -> {
            }
        }
        return list;
    }

    public int perTick(int total) {
        return order == Order.INSTANT ? Math.max(1, total) : blocksPerTick;
    }
}
