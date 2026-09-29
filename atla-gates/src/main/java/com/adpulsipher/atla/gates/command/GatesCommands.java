package com.adpulsipher.atla.gates.command;

import com.adpulsipher.atla.core.util.ConfigException;
import com.adpulsipher.atla.gates.override.BlockOverride;
import com.adpulsipher.atla.gates.override.OverrideConfig;
import com.adpulsipher.atla.gates.override.OverrideHandler;
import com.adpulsipher.atla.gates.override.OverrideState;
import com.adpulsipher.atla.gates.placement.PlacementQueue;
import com.adpulsipher.atla.gates.placement.UndoStore;
import com.adpulsipher.atla.gates.schematic.Schematic;
import com.adpulsipher.atla.gates.schematic.SchematicLoader;
import com.adpulsipher.atla.gates.zone.Zone;
import com.adpulsipher.atla.gates.zone.ZoneConfig;
import com.adpulsipher.atla.gates.zone.ZoneEnforcer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Map-maker tools, merged into the {@code /atla} command from atla_core:
 *
 * <pre>
 * /atla override list | info &lt;id&gt; | test &lt;id&gt; [player] | trigger &lt;id&gt; [player] | reset &lt;id&gt;|all
 * /atla zone list | check [player] | reset &lt;targets&gt; &lt;zone&gt;
 * /atla schematic info &lt;file&gt;
 * </pre>
 */
public final class GatesCommands {
    private static final DynamicCommandExceptionType UNKNOWN_OVERRIDE = new DynamicCommandExceptionType(
            id -> Component.literal("No override with id '" + id + "' in " + OverrideConfig.FILE_NAME));
    private static final SuggestionProvider<CommandSourceStack> OVERRIDES = (ctx, b) ->
            SharedSuggestionProvider.suggest(OverrideConfig.get().all().keySet(), b);
    private static final SuggestionProvider<CommandSourceStack> ZONES = (ctx, b) ->
            SharedSuggestionProvider.suggest(ZoneConfig.get().all().keySet(), b);

    private GatesCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("atla").then(overrideCommands()).then(zoneCommands()).then(schematicCommands()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> overrideCommands() {
        return Commands.literal("override").requires(s -> s.hasPermission(2))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("info").then(Commands.argument("id", StringArgumentType.string()).suggests(OVERRIDES)
                        .executes(ctx -> info(ctx.getSource(), override(ctx)))))
                .then(Commands.literal("test").then(Commands.argument("id", StringArgumentType.string()).suggests(OVERRIDES)
                        .executes(ctx -> test(ctx.getSource(), override(ctx), ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> test(ctx.getSource(), override(ctx), EntityArgument.getPlayer(ctx, "player"))))))
                .then(Commands.literal("trigger").then(Commands.argument("id", StringArgumentType.string()).suggests(OVERRIDES)
                        .executes(ctx -> trigger(ctx.getSource(), override(ctx), ctx.getSource().getPlayer()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> trigger(ctx.getSource(), override(ctx), EntityArgument.getPlayer(ctx, "player"))))))
                .then(Commands.literal("reset")
                        .then(Commands.literal("all").executes(ctx -> {
                            int n = 0;
                            for (String id : OverrideConfig.get().all().keySet()) {
                                n += reset(ctx.getSource(), id, false);
                            }
                            int total = n;
                            ctx.getSource().sendSuccess(() -> Component.literal("Reset " + total + " override(s)"), true);
                            return total;
                        }))
                        .then(Commands.argument("id", StringArgumentType.string()).suggests(OVERRIDES)
                                .executes(ctx -> reset(ctx.getSource(), StringArgumentType.getString(ctx, "id"), true))));
    }

    private static BlockOverride override(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "id");
        BlockOverride ov = OverrideConfig.get().byId(id);
        if (ov == null) {
            throw UNKNOWN_OVERRIDE.create(id);
        }
        return ov;
    }

    private static int list(CommandSourceStack source) {
        OverrideState state = OverrideState.get(source.getServer());
        var all = OverrideConfig.get().all();
        source.sendSuccess(() -> Component.literal(all.size() + " bendable override(s):").withStyle(ChatFormatting.GOLD), false);
        for (BlockOverride ov : all.values()) {
            boolean done = state.isDone(ov.id());
            MutableComponent line = Component.literal(done ? " \u2714 " : " \u25CB ")
                    .withStyle(done ? ChatFormatting.GREEN : ChatFormatting.GRAY)
                    .append(Component.literal(ov.id()).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" " + (ov.element() == null ? "any" : ov.element().id() + " " + ov.minLevel()))
                            .withStyle(ChatFormatting.GRAY));
            source.sendSuccess(() -> line, false);
        }
        return all.size();
    }

    private static int info(CommandSourceStack source, BlockOverride ov) {
        List<String> lines = new ArrayList<>();
        lines.add("Override '" + ov.id() + "' in " + ov.dimension().location());
        lines.add(" element: " + (ov.element() == null ? "any" : ov.element().id() + " level " + ov.minLevel()
                + (ov.requireActiveElement() ? " (must be selected)" : "")));
        lines.add(" triggers: " + ov.triggers() + (ov.triggerAreas().isEmpty() ? "" : " + " + ov.triggerAreas().size() + " area(s)"));
        lines.add(" actions: " + ov.actions().size() + ", once: " + ov.once() + ", animation: "
                + ov.animation().order().name().toLowerCase() + " @" + ov.animation().blocksPerTick() + "/tick");
        OverrideState.Entry done = OverrideState.get(source.getServer()).entry(ov.id());
        lines.add(done == null ? " not triggered yet" : " triggered by " + done.player() + " at game time " + done.gameTime());
        lines.add(" undo snapshot: " + (UndoStore.exists(source.getServer(), ov.id()) ? "yes" : "no"));
        for (String l : lines) {
            source.sendSuccess(() -> Component.literal(l), false);
        }
        return 1;
    }

    private static int test(CommandSourceStack source, BlockOverride ov, ServerPlayer player) {
        OverrideHandler.Outcome outcome = OverrideHandler.check(player, ov);
        boolean done = ov.once() && OverrideState.get(source.getServer()).isDone(ov.id());
        String name = player.getGameProfile().getName();
        if (done) {
            source.sendSuccess(() -> Component.literal("'" + ov.id() + "' was already triggered (use /atla override reset)").withStyle(ChatFormatting.YELLOW), false);
        }
        if (outcome == OverrideHandler.Outcome.TRIGGERED) {
            source.sendSuccess(() -> Component.literal(name + " can bend '" + ov.id() + "' right now").withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(name + " cannot bend '" + ov.id() + "': " + outcome.name().toLowerCase()).withStyle(ChatFormatting.YELLOW), false);
        for (String f : ov.requires().describeFailures(player)) {
            source.sendSuccess(() -> Component.literal(" - " + f).withStyle(ChatFormatting.GRAY), false);
        }
        return 0;
    }

    private static int trigger(CommandSourceStack source, BlockOverride ov, ServerPlayer player) {
        BlockPos clicked = ov.triggers().isEmpty() ? BlockPos.containing(source.getPosition()) : ov.triggers().iterator().next();
        if (!OverrideHandler.trigger(ov, source.getServer(), player, clicked)) {
            source.sendFailure(Component.literal("Override '" + ov.id() + "' failed; see the server log"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Triggered '" + ov.id() + "'"), true);
        return 1;
    }

    private static int reset(CommandSourceStack source, String id, boolean feedback) {
        MinecraftServer server = source.getServer();
        PlacementQueue.flush(); // finish any running animation before putting blocks back
        int restored = UndoStore.restore(server, id);
        boolean cleared = OverrideState.get(server).clear(id);
        if (feedback) {
            if (restored < 0 && !cleared) {
                source.sendFailure(Component.literal("'" + id + "' has not been triggered (no state or undo snapshot)"));
                return 0;
            }
            String msg = "Reset '" + id + "'" + (restored >= 0 ? ", restored " + restored + " block(s)" : " (no undo snapshot; blocks unchanged)");
            source.sendSuccess(() -> Component.literal(msg), true);
        }
        return restored >= 0 || cleared ? 1 : 0;
    }

    // ------------------------------------------------------------------ zones

    private static LiteralArgumentBuilder<CommandSourceStack> zoneCommands() {
        return Commands.literal("zone").requires(s -> s.hasPermission(2))
                .then(Commands.literal("list").executes(ctx -> {
                    var zones = ZoneConfig.get().all().values();
                    ctx.getSource().sendSuccess(() -> Component.literal(zones.size() + " zone(s):").withStyle(ChatFormatting.GOLD), false);
                    for (Zone z : zones) {
                        ctx.getSource().sendSuccess(() -> Component.literal(" " + z.id() + " [" + z.type().name().toLowerCase() + "] "
                                + z.dimension().location() + " " + describe(z)).withStyle(ChatFormatting.GRAY), false);
                    }
                    return zones.size();
                }))
                .then(Commands.literal("check")
                        .executes(ctx -> check(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> check(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("reset").then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("zone", StringArgumentType.string()).suggests(ZONES).executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "zone");
                            int n = 0;
                            for (ServerPlayer p : EntityArgument.getPlayers(ctx, "targets")) {
                                if (ZoneEnforcer.resetTrigger(p, id)) {
                                    n++;
                                }
                            }
                            int total = n;
                            ctx.getSource().sendSuccess(() -> Component.literal("Trigger zone '" + id + "' can fire again for " + total + " player(s)"), true);
                            return total;
                        }))));
    }

    private static String describe(Zone z) {
        var b = z.box();
        return z.fullHeight()
                ? "x " + (int) b.minX + ".." + ((int) b.maxX - 1) + ", z " + (int) b.minZ + ".." + ((int) b.maxZ - 1) + " (full height)"
                : "from [" + (int) b.minX + ", " + (int) b.minY + ", " + (int) b.minZ + "] to ["
                + ((int) b.maxX - 1) + ", " + ((int) b.maxY - 1) + ", " + ((int) b.maxZ - 1) + "]";
    }

    private static int check(CommandSourceStack source, ServerPlayer player) {
        Vec3 pos = player.position();
        var denial = ZoneEnforcer.check(player, player.level().dimension(), pos);
        String name = player.getGameProfile().getName();
        source.sendSuccess(() -> Component.literal(name + (denial == null ? " is allowed here" : " is NOT allowed here"
                + (denial.zone() == null ? " (outside every open bounds zone)" : " (zone '" + denial.zone().id() + "')")))
                .withStyle(denial == null ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        for (Zone z : ZoneConfig.get().in(player.level().dimension())) {
            if (z.contains(pos)) {
                boolean open = z.requires().test(player);
                source.sendSuccess(() -> Component.literal(" inside '" + z.id() + "' [" + z.type().name().toLowerCase() + "] requirements "
                        + (open ? "pass" : "fail")).withStyle(ChatFormatting.GRAY), false);
            }
        }
        return denial == null ? 1 : 0;
    }

    // ------------------------------------------------------------------ schematics

    private static LiteralArgumentBuilder<CommandSourceStack> schematicCommands() {
        return Commands.literal("schematic").requires(s -> s.hasPermission(2))
                .then(Commands.literal("info").then(Commands.argument("file", StringArgumentType.string()).executes(ctx -> {
                    String file = StringArgumentType.getString(ctx, "file");
                    try {
                        Schematic s = SchematicLoader.load(file);
                        int nonAir = 0;
                        for (int id : s.blocks()) {
                            if (id >= 0 && !s.palette()[id].isAir()) {
                                nonAir++;
                            }
                        }
                        int solid = nonAir;
                        ctx.getSource().sendSuccess(() -> Component.literal(s.name() + ": " + s.width() + "x" + s.height() + "x" + s.length()
                                + " (" + solid + " non-air blocks, " + s.palette().length + " states, " + s.blockEntities().size() + " block entities)"), false);
                        ctx.getSource().sendSuccess(() -> Component.literal(" paste offset (//paste): " + s.pasteOffset().toShortString()), false);
                        ctx.getSource().sendSuccess(() -> Component.literal(s.originalMin() == null
                                ? " no saved copy position - overrides must give \"origin\" or \"position\""
                                : " copied from min corner [" + s.originalMin().toShortString() + "] (used when no origin/position is given)"), false);
                        return 1;
                    } catch (ConfigException e) {
                        ctx.getSource().sendFailure(Component.literal(e.getMessage()));
                        return 0;
                    }
                })));
    }
}
