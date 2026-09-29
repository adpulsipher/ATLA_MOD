package com.adpulsipher.atla.core.command;

import com.adpulsipher.atla.core.AtlaCore;
import com.adpulsipher.atla.core.api.Element;
import com.adpulsipher.atla.core.config.NarrativeGate;
import com.adpulsipher.atla.core.config.ProgressionConfig;
import com.adpulsipher.atla.core.data.BendingProgression;
import com.adpulsipher.atla.core.logic.ProgressionManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;

/**
 * {@code /atla ...} - the glue between this mod and quest mods, command blocks and the map maker.
 *
 * <pre>
 * /atla flag add|remove &lt;targets&gt; &lt;flag&gt;      story flags (quest rewards call this)
 * /atla flag has &lt;player&gt; &lt;flag&gt;             result 1/0, usable with /execute store
 * /atla flag list [player]
 * /atla element unlock|lock &lt;targets&gt; &lt;element&gt;
 * /atla element set &lt;targets&gt; &lt;element&gt; &lt;level&gt;
 * /atla element levelup &lt;targets&gt; &lt;element&gt;
 * /atla element select &lt;element&gt; [targets]     players may select their own unlocked elements
 * /atla gate list [player] | test &lt;player&gt; &lt;gate&gt; | clear|forget &lt;targets&gt; &lt;gate&gt; | evaluate &lt;targets&gt;
 * /atla progress [player]
 * /atla reset &lt;targets&gt;
 * /atla reload                                 re-reads every ATLA json config
 * /atla pos                                    prints the looked-at block as a copyable [x, y, z]
 * </pre>
 */
public final class AtlaCommands {
    private static final DynamicCommandExceptionType UNKNOWN_ELEMENT = new DynamicCommandExceptionType(
            raw -> Component.literal("Unknown element '" + raw + "' (air, water, earth, fire)"));
    private static final DynamicCommandExceptionType UNKNOWN_GATE = new DynamicCommandExceptionType(
            raw -> Component.literal("No narrative gate with id '" + raw + "' in " + ProgressionConfig.FILE_NAME));

    private static final SuggestionProvider<CommandSourceStack> ELEMENTS = (ctx, b) ->
            SharedSuggestionProvider.suggest(Arrays.stream(Element.values()).map(Element::id), b);
    private static final SuggestionProvider<CommandSourceStack> GATES = (ctx, b) ->
            SharedSuggestionProvider.suggest(ProgressionConfig.get().gates().keySet(), b);
    private static final SuggestionProvider<CommandSourceStack> FLAGS = (ctx, b) -> {
        Set<String> flags = new TreeSet<>(ProgressionConfig.get().knownFlags());
        for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            flags.addAll(ProgressionManager.get(p).flags());
        }
        return SharedSuggestionProvider.suggest(flags, b);
    };

    private AtlaCommands() {
    }

    private static boolean op(CommandSourceStack s) {
        return s.hasPermission(2);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("atla")
                .then(Commands.literal("reload").requires(AtlaCommands::op)
                        .executes(ctx -> reload(ctx.getSource())))
                .then(Commands.literal("pos").requires(AtlaCommands::op)
                        .executes(ctx -> pos(ctx.getSource())))
                .then(Commands.literal("progress")
                        .executes(ctx -> progress(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player()).requires(AtlaCommands::op)
                                .executes(ctx -> progress(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("reset").requires(AtlaCommands::op)
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> forEach(ctx, (s, p) -> {
                                    ProgressionManager.reset(p);
                                    return true;
                                }, "Reset story progression"))))
                .then(flagCommands())
                .then(elementCommands())
                .then(gateCommands()));
    }

    // ------------------------------------------------------------------ /atla flag

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> flagCommands() {
        return Commands.literal("flag").requires(AtlaCommands::op)
                .then(Commands.literal("add").then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("flag", StringArgumentType.string()).suggests(FLAGS)
                                .executes(ctx -> {
                                    String flag = StringArgumentType.getString(ctx, "flag");
                                    return forEach(ctx, (s, p) -> ProgressionManager.addFlag(p, flag), "Added flag '" + flag + "'");
                                }))))
                .then(Commands.literal("remove").then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("flag", StringArgumentType.string()).suggests(FLAGS)
                                .executes(ctx -> {
                                    String flag = StringArgumentType.getString(ctx, "flag");
                                    return forEach(ctx, (s, p) -> ProgressionManager.removeFlag(p, flag), "Removed flag '" + flag + "'");
                                }))))
                .then(Commands.literal("has").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("flag", StringArgumentType.string()).suggests(FLAGS)
                                .executes(ctx -> {
                                    ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                                    String flag = StringArgumentType.getString(ctx, "flag");
                                    boolean has = ProgressionManager.get(p).hasFlag(flag);
                                    ctx.getSource().sendSuccess(() -> Component.literal(p.getGameProfile().getName()
                                            + (has ? " has" : " does not have") + " flag '" + flag + "'"), false);
                                    return has ? 1 : 0;
                                }))))
                .then(Commands.literal("list")
                        .executes(ctx -> listFlags(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> listFlags(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))));
    }

    private static int listFlags(CommandSourceStack source, ServerPlayer player) {
        Set<String> flags = ProgressionManager.get(player).flags();
        source.sendSuccess(() -> Component.literal(player.getGameProfile().getName() + " has " + flags.size() + " flag(s): ")
                .append(Component.literal(String.join(", ", flags)).withStyle(ChatFormatting.GRAY)), false);
        return flags.size();
    }

    // ------------------------------------------------------------------ /atla element

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> elementCommands() {
        return Commands.literal("element")
                .then(Commands.literal("unlock").requires(AtlaCommands::op)
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("element", StringArgumentType.word()).suggests(ELEMENTS)
                                        .executes(ctx -> {
                                            Element e = element(ctx);
                                            return forEach(ctx, (s, p) -> ProgressionManager.unlock(p, e), "Unlocked " + e.id());
                                        }))))
                .then(Commands.literal("lock").requires(AtlaCommands::op)
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("element", StringArgumentType.word()).suggests(ELEMENTS)
                                        .executes(ctx -> {
                                            Element e = element(ctx);
                                            return forEach(ctx, (s, p) -> ProgressionManager.lock(p, e), "Locked " + e.id());
                                        }))))
                .then(Commands.literal("set").requires(AtlaCommands::op)
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("element", StringArgumentType.word()).suggests(ELEMENTS)
                                        .then(Commands.argument("level", IntegerArgumentType.integer(0))
                                                .executes(ctx -> {
                                                    Element e = element(ctx);
                                                    int level = IntegerArgumentType.getInteger(ctx, "level");
                                                    return forEach(ctx, (s, p) -> ProgressionManager.setLevel(p, e, level),
                                                            "Set " + e.id() + " to level " + level);
                                                })))))
                .then(Commands.literal("levelup").requires(AtlaCommands::op)
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("element", StringArgumentType.word()).suggests(ELEMENTS)
                                        .executes(ctx -> {
                                            Element e = element(ctx);
                                            return forEach(ctx, (s, p) -> {
                                                BendingProgression prog = ProgressionManager.get(p);
                                                // level-ups never unlock: a locked element stays locked
                                                return prog.isUnlocked(e) && ProgressionManager.setLevel(p, e, prog.level(e) + 1);
                                            }, "Raised " + e.id() + " by one level");
                                        }))))
                .then(Commands.literal("select")
                        .then(Commands.argument("element", StringArgumentType.word()).suggests(ELEMENTS)
                                .executes(ctx -> {
                                    Element e = element(ctx);
                                    ServerPlayer self = ctx.getSource().getPlayerOrException();
                                    if (!op(ctx.getSource()) && !ProgressionConfig.get().settings().allowPlayerElementSwitching()) {
                                        ctx.getSource().sendFailure(Component.translatable("atla_core.command.switching_disabled"));
                                        return 0;
                                    }
                                    if (!ProgressionManager.get(self).isUnlocked(e)) {
                                        ctx.getSource().sendFailure(Component.translatable("atla_core.command.element_locked", e.displayName()));
                                        return 0;
                                    }
                                    ProgressionManager.select(self, e);
                                    return 1;
                                })
                                .then(Commands.argument("targets", EntityArgument.players()).requires(AtlaCommands::op)
                                        .executes(ctx -> {
                                            Element e = element(ctx);
                                            return forEach(ctx, (s, p) -> ProgressionManager.select(p, e), "Selected " + e.id());
                                        }))));
    }

    private static Element element(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(ctx, "element");
        Element e = Element.byId(raw);
        if (e == null) {
            throw UNKNOWN_ELEMENT.create(raw);
        }
        return e;
    }

    // ------------------------------------------------------------------ /atla gate

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> gateCommands() {
        return Commands.literal("gate").requires(AtlaCommands::op)
                .then(Commands.literal("list")
                        .executes(ctx -> listGates(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> listGates(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("test").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("gate", StringArgumentType.string()).suggests(GATES)
                                .executes(ctx -> testGate(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), gate(ctx))))))
                .then(Commands.literal("clear").then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("gate", StringArgumentType.string()).suggests(GATES)
                                .executes(ctx -> {
                                    NarrativeGate gate = gate(ctx);
                                    return forEach(ctx, (s, p) -> ProgressionManager.clearGate(p, gate), "Cleared gate '" + gate.id() + "'");
                                }))))
                .then(Commands.literal("forget").then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("gate", StringArgumentType.string()).suggests(GATES)
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "gate");
                                    return forEach(ctx, (s, p) -> ProgressionManager.unclearGate(p, id),
                                            "Forgot gate '" + id + "' (rewards are kept; it can clear again)");
                                }))))
                .then(Commands.literal("evaluate").then(Commands.argument("targets", EntityArgument.players())
                        .executes(ctx -> forEach(ctx, (s, p) -> {
                            int before = ProgressionManager.get(p).clearedGates().size();
                            ProgressionManager.evaluateGates(p);
                            return ProgressionManager.get(p).clearedGates().size() > before;
                        }, "Evaluated narrative gates"))));
    }

    private static NarrativeGate gate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "gate");
        NarrativeGate gate = ProgressionConfig.get().gates().get(id);
        if (gate == null) {
            throw UNKNOWN_GATE.create(id);
        }
        return gate;
    }

    private static int listGates(CommandSourceStack source, ServerPlayer player) {
        BendingProgression p = ProgressionManager.get(player);
        Collection<NarrativeGate> gates = ProgressionConfig.get().gates().values();
        source.sendSuccess(() -> Component.literal("Narrative gates for " + player.getGameProfile().getName() + ":").withStyle(ChatFormatting.GOLD), false);
        for (NarrativeGate gate : gates) {
            boolean cleared = p.hasClearedGate(gate.id());
            MutableComponent line = Component.literal(cleared ? " ✔ " : " ✖ ").withStyle(cleared ? ChatFormatting.GREEN : ChatFormatting.RED)
                    .append(Component.literal(gate.id()).withStyle(ChatFormatting.WHITE));
            if (!gate.description().isEmpty()) {
                line.append(Component.literal(" - " + gate.description()).withStyle(ChatFormatting.GRAY));
            }
            source.sendSuccess(() -> line, false);
        }
        return gates.size();
    }

    private static int testGate(CommandSourceStack source, ServerPlayer player, NarrativeGate gate) {
        if (ProgressionManager.get(player).hasClearedGate(gate.id())) {
            source.sendSuccess(() -> Component.literal("Gate '" + gate.id() + "' is already cleared for " + player.getGameProfile().getName()), false);
            return 1;
        }
        List<String> failures = gate.requires().describeFailures(player);
        if (failures.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Gate '" + gate.id() + "' passes and will clear on the next evaluation").withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Gate '" + gate.id() + "' is blocked because:").withStyle(ChatFormatting.YELLOW), false);
        for (String f : failures) {
            source.sendSuccess(() -> Component.literal(" - " + f).withStyle(ChatFormatting.GRAY), false);
        }
        return 0;
    }

    // ------------------------------------------------------------------ misc

    private static int progress(CommandSourceStack source, ServerPlayer player) {
        BendingProgression p = ProgressionManager.get(player);
        ProgressionConfig cfg = ProgressionConfig.get();
        source.sendSuccess(() -> Component.literal("== " + player.getGameProfile().getName() + " ==").withStyle(ChatFormatting.GOLD), false);
        for (Element e : Element.values()) {
            int level = p.level(e);
            MutableComponent line = Component.literal(" ").append(e.displayName()).append(": ");
            if (level == 0) {
                line.append(Component.translatable("atla_core.progress.locked").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                line.append(Component.literal(cfg.levelName(level) + " (" + level + "/" + cfg.maxLevel(e) + ")").withStyle(ChatFormatting.WHITE));
                if (p.active() == e) {
                    line.append(Component.literal(" ◀").withStyle(ChatFormatting.GOLD));
                }
            }
            source.sendSuccess(() -> line, false);
        }
        if (op(source)) {
            source.sendSuccess(() -> Component.literal(" Flags: " + String.join(", ", p.flags())).withStyle(ChatFormatting.GRAY), false);
            source.sendSuccess(() -> Component.literal(" Gates cleared: " + p.clearedGates().size() + "/" + cfg.gates().size())
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        List<String> errors = AtlaCore.reloadAll(source.getServer());
        if (errors.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Reloaded ATLA story configs").withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        source.sendFailure(Component.literal("ATLA configs reloaded with " + errors.size() + " problem(s). Broken entries were "
                + "skipped; a file that isn't valid JSON keeps its previous settings:"));
        for (String error : errors) {
            source.sendFailure(Component.literal(" - " + error));
        }
        return 0;
    }

    private static int pos(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        HitResult hit = player.pick(64, 0, false);
        String dim = player.level().dimension().location().toString();
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bhr.getBlockPos();
            String block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).getBlock()).toString();
            source.sendSuccess(() -> Component.literal("Looking at " + block + " in " + dim + ": ").append(copyable(pos)), false);
        }
        BlockPos feet = player.blockPosition();
        source.sendSuccess(() -> Component.literal("Standing at: ").append(copyable(feet)), false);
        return 1;
    }

    private static Component copyable(BlockPos pos) {
        String text = "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
        return Component.literal(text).withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to copy"))));
    }

    /** Applies an action to every target and reports how many actually changed. */
    private static int forEach(CommandContext<CommandSourceStack> ctx, BiPredicate<CommandSourceStack, ServerPlayer> action,
                               String what) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        int changed = 0;
        for (ServerPlayer p : targets) {
            if (action.test(ctx.getSource(), p)) {
                changed++;
            }
        }
        int n = changed;
        ctx.getSource().sendSuccess(() -> Component.literal(what + " for " + n + "/" + targets.size() + " player(s)"), true);
        return changed;
    }
}
