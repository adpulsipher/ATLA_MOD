package com.adpulsipher.atla.gates.zone;

import com.adpulsipher.atla.core.api.AtlaApi;
import com.adpulsipher.atla.core.util.Text;
import com.adpulsipher.atla.gates.AtlaGates;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.LogicalSide;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps players out of zones the story hasn't opened yet. Every server tick each (non-exempt) player
 * is checked; if they are somewhere they may not be, they're put back at the last spot where they
 * were allowed (or the zone's fallback / their spawn point), whatever moved them there - walking,
 * gliding, boats, ender pearls or /tp.
 */
public final class ZoneEnforcer {
    private static final String TRIGGERED_KEY = "atla_gates_triggered_zones";
    private static final Map<UUID, Tracker> TRACKERS = new HashMap<>();

    private ZoneEnforcer() {
    }

    private static final class Tracker {
        @Nullable
        Vec3 lastSafe;
        @Nullable
        ResourceKey<Level> lastSafeDimension;
        float lastYaw;
        float lastPitch;
        /** Most recent bounds zone the player stood in, for its message. */
        @Nullable
        Zone lastBounds;
        final Set<String> insideTriggers = new HashSet<>();
        long lastMessage = Long.MIN_VALUE;
        boolean warnedStuck;
    }

    /** Why a position is off-limits: the zone responsible, or null for "outside every open bounds zone". */
    public record Denial(@Nullable Zone zone) {
    }

    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER
                || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        ZoneConfig cfg = ZoneConfig.get();
        if (cfg.isEmpty() || !player.isAlive()) {
            return;
        }
        Tracker t = TRACKERS.computeIfAbsent(player.getUUID(), k -> new Tracker());
        if (cfg.settings().exemptGameModes().contains(player.gameMode.getGameModeForPlayer())) {
            t.lastSafe = null; // builders flying around shouldn't leave a stale "safe" spot behind
            return;
        }

        ResourceKey<Level> dim = player.level().dimension();
        Vec3 pos = player.position();
        Denial denial = check(player, dim, pos, t);
        if (denial == null) {
            t.lastSafe = pos;
            t.lastSafeDimension = dim;
            t.lastYaw = player.getYRot();
            t.lastPitch = player.getXRot();
            t.warnedStuck = false;
        } else {
            pushBack(player, t, denial, cfg);
        }

        handleTriggers(player, dim, player.position(), t, cfg);
        if (cfg.settings().showWalls() && cfg.settings().wallParticle() != null && player.tickCount % 5 == 0) {
            drawWalls(player, dim, t, cfg);
        }
    }

    /** Returns null if the player may be at {@code pos}. */
    @Nullable
    public static Denial check(ServerPlayer player, ResourceKey<Level> dim, Vec3 pos) {
        return check(player, dim, pos, null);
    }

    @Nullable
    private static Denial check(ServerPlayer player, ResourceKey<Level> dim, Vec3 pos, @Nullable Tracker t) {
        List<Zone> zones = ZoneConfig.get().in(dim);
        boolean anyOpenBounds = false;
        boolean insideOpenBounds = false;
        for (Zone z : zones) {
            if (z.type() == Zone.Type.BARRIER) {
                if (z.contains(pos) && !z.requires().test(player)) {
                    return new Denial(z);
                }
            } else if (z.type() == Zone.Type.BOUNDS && z.requires().test(player)) {
                anyOpenBounds = true;
                if (z.contains(pos)) {
                    insideOpenBounds = true;
                    if (t != null) {
                        t.lastBounds = z;
                    }
                }
            }
        }
        // If no bounds zone is open yet for this player, bounds don't restrict them at all.
        if (anyOpenBounds && !insideOpenBounds) {
            return new Denial(t == null ? null : t.lastBounds);
        }
        return null;
    }

    private static void pushBack(ServerPlayer player, Tracker t, Denial denial, ZoneConfig cfg) {
        ResourceKey<Level> dim = player.level().dimension();
        Vec3 target = null;
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        if (t.lastSafe != null && dim.equals(t.lastSafeDimension) && check(player, dim, t.lastSafe, null) == null) {
            target = t.lastSafe;
            yaw = t.lastYaw;
            pitch = t.lastPitch;
        } else {
            target = fallback(player, denial);
        }
        if (target == null || check(player, dim, target, null) != null) {
            if (!t.warnedStuck) {
                t.warnedStuck = true;
                AtlaGates.LOGGER.warn("{} is inside a closed zone at {} and there is no allowed place to send them; "
                        + "add a \"fallback\" to zone '{}'", player.getGameProfile().getName(), player.blockPosition(),
                        denial.zone() == null ? "<bounds>" : denial.zone().id());
            }
            return;
        }
        if (player.isPassenger()) {
            player.stopRiding();
        }
        player.stopFallFlying();
        player.setDeltaMovement(Vec3.ZERO);
        player.connection.teleport(target.x, target.y, target.z, yaw, pitch);
        player.resetFallDistance();

        long now = player.level().getGameTime();
        if (now - t.lastMessage >= cfg.settings().messageCooldownTicks() || now < t.lastMessage) {
            t.lastMessage = now;
            Component msg = null;
            if (denial.zone() != null) {
                msg = Text.parse(denial.zone().message(), Map.of("player", player.getGameProfile().getName()));
            }
            if (msg == null) {
                msg = Text.parse(cfg.settings().defaultMessage());
            }
            if (msg == null) {
                msg = Component.translatableWithFallback("atla_gates.zone.blocked", "You can't go this way yet.");
            }
            player.displayClientMessage(msg, true);
            if (cfg.settings().pushSound() != null) {
                cfg.settings().pushSound().playFor(player);
            }
        }
    }

    @Nullable
    private static Vec3 fallback(ServerPlayer player, Denial denial) {
        if (denial.zone() != null && denial.zone().fallback() != null) {
            return Vec3.atBottomCenterOf(denial.zone().fallback());
        }
        ServerLevel level = player.serverLevel();
        BlockPos respawn = player.getRespawnPosition();
        if (respawn != null && level.dimension().equals(player.getRespawnDimension())) {
            return Vec3.atBottomCenterOf(respawn);
        }
        return Vec3.atBottomCenterOf(level.getSharedSpawnPos());
    }

    private static void handleTriggers(ServerPlayer player, ResourceKey<Level> dim, Vec3 pos, Tracker t, ZoneConfig cfg) {
        for (Zone z : cfg.in(dim)) {
            if (z.type() != Zone.Type.TRIGGER) {
                continue;
            }
            boolean inside = z.contains(pos);
            boolean wasInside = t.insideTriggers.contains(z.id());
            if (inside && !wasInside) {
                t.insideTriggers.add(z.id());
                if (z.requires().test(player) && !(z.once() && hasTriggered(player, z.id()))) {
                    fireTrigger(player, z);
                }
            } else if (!inside && wasInside) {
                t.insideTriggers.remove(z.id());
            }
        }
    }

    private static void fireTrigger(ServerPlayer player, Zone z) {
        if (z.once()) {
            markTriggered(player, z.id());
        }
        AtlaGates.LOGGER.info("{} entered trigger zone '{}'", player.getGameProfile().getName(), z.id());
        for (String flag : z.setFlags()) {
            AtlaApi.addFlag(player, flag);
        }
        AtlaApi.runCommands(player, z.commands());
        Component msg = Text.parse(z.enterMessage(), Map.of("player", player.getGameProfile().getName()));
        if (msg != null) {
            player.displayClientMessage(msg, true);
        }
    }

    /** Draws a faint wall where a closed zone's edge is close to the player (only that player sees it). */
    private static void drawWalls(ServerPlayer player, ResourceKey<Level> dim, Tracker t, ZoneConfig cfg) {
        double reach = cfg.settings().wallDistance();
        Vec3 eye = player.getEyePosition();
        ServerLevel level = player.serverLevel();
        for (Zone z : cfg.in(dim)) {
            if (!z.showWall() || z.type() == Zone.Type.TRIGGER) {
                continue;
            }
            AABB box = z.box();
            if (z.type() == Zone.Type.BARRIER) {
                if (z.contains(eye) || z.requires().test(player)) {
                    continue;
                }
                Vec3 closest = new Vec3(clamp(eye.x, box.minX, box.maxX), z.fullHeight() ? eye.y : clamp(eye.y, box.minY, box.maxY),
                        clamp(eye.z, box.minZ, box.maxZ));
                if (closest.distanceTo(eye) <= reach) {
                    spawnWall(level, player, closest, eye, cfg);
                }
            } else if (z.type() == Zone.Type.BOUNDS && z.contains(eye) && z.requires().test(player)) {
                // check each side face; only draw it if what lies beyond is actually closed
                tryBoundsFace(level, player, dim, eye, new Vec3(box.minX, eye.y, eye.z), new Vec3(-0.5, 0, 0), reach, cfg);
                tryBoundsFace(level, player, dim, eye, new Vec3(box.maxX, eye.y, eye.z), new Vec3(0.5, 0, 0), reach, cfg);
                tryBoundsFace(level, player, dim, eye, new Vec3(eye.x, eye.y, box.minZ), new Vec3(0, 0, -0.5), reach, cfg);
                tryBoundsFace(level, player, dim, eye, new Vec3(eye.x, eye.y, box.maxZ), new Vec3(0, 0, 0.5), reach, cfg);
            }
        }
    }

    private static void tryBoundsFace(ServerLevel level, ServerPlayer player, ResourceKey<Level> dim, Vec3 eye, Vec3 face,
                                      Vec3 outward, double reach, ZoneConfig cfg) {
        if (face.distanceTo(eye) <= reach && check(player, dim, face.add(outward), null) != null) {
            spawnWall(level, player, face, eye, cfg);
        }
    }

    private static void spawnWall(ServerLevel level, ServerPlayer player, Vec3 at, Vec3 eye, ZoneConfig cfg) {
        // a small patch of mist on the wall plane, spread sideways and vertically around the closest point
        Vec3 normal = eye.subtract(at);
        boolean alongX = Math.abs(normal.x) < Math.abs(normal.z);
        for (int i = 0; i < 6; i++) {
            double side = (level.random.nextDouble() - 0.5) * 3.0;
            double up = (level.random.nextDouble() - 0.5) * 2.5;
            double x = at.x + (alongX ? side : 0);
            double z = at.z + (alongX ? 0 : side);
            level.sendParticles(player, cfg.settings().wallParticle(), false, x, at.y + up, z, 1, 0, 0, 0, 0);
        }
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    // ------------------------------------------------------------------ trigger bookkeeping (survives death)

    private static boolean hasTriggered(Player player, String id) {
        ListTag list = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getList(TRIGGERED_KEY, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            if (list.getString(i).equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static void markTriggered(Player player, String id) {
        CompoundTag data = player.getPersistentData();
        CompoundTag persisted = data.getCompound(Player.PERSISTED_NBT_TAG);
        ListTag list = persisted.getList(TRIGGERED_KEY, Tag.TAG_STRING);
        list.add(StringTag.valueOf(id));
        persisted.put(TRIGGERED_KEY, list);
        data.put(Player.PERSISTED_NBT_TAG, persisted);
    }

    /** Lets a trigger zone fire again for this player (used by /atla zone reset). */
    public static boolean resetTrigger(Player player, String id) {
        CompoundTag data = player.getPersistentData();
        CompoundTag persisted = data.getCompound(Player.PERSISTED_NBT_TAG);
        ListTag list = persisted.getList(TRIGGERED_KEY, Tag.TAG_STRING);
        boolean removed = list.removeIf(tag -> tag.getAsString().equals(id));
        persisted.put(TRIGGERED_KEY, list);
        data.put(Player.PERSISTED_NBT_TAG, persisted);
        TRACKERS.computeIfPresent(player.getUUID(), (k, t) -> {
            t.insideTriggers.remove(id);
            return t;
        });
        return removed;
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TRACKERS.remove(event.getEntity().getUUID());
    }

    /** After a teleport across dimensions the old safe spot is meaningless. */
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        Tracker t = TRACKERS.get(event.getEntity().getUUID());
        if (t != null) {
            t.lastSafe = null;
            t.insideTriggers.clear();
        }
    }
}
