package com.adpulsipher.atla.gates.test;

import com.adpulsipher.atla.core.api.AtlaApi;
import com.adpulsipher.atla.core.api.Element;
import com.adpulsipher.atla.core.config.ProgressionConfig;
import com.adpulsipher.atla.core.logic.ProgressionManager;
import com.adpulsipher.atla.gates.AtlaGates;
import com.adpulsipher.atla.gates.override.OverrideConfig;
import com.adpulsipher.atla.gates.override.OverrideHandler;
import com.adpulsipher.atla.gates.override.OverrideHandler.Outcome;
import com.adpulsipher.atla.gates.override.OverrideState;
import com.adpulsipher.atla.gates.placement.UndoStore;
import com.adpulsipher.atla.gates.schematic.Schematic;
import com.adpulsipher.atla.gates.schematic.SchematicLoader;
import com.adpulsipher.atla.gates.zone.ZoneConfig;
import com.adpulsipher.atla.gates.zone.ZoneEnforcer;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.forge.ForgeAdapter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-game tests, run on a real headless 1.20.1 server with {@code ./gradlew :atla-gates:runGameTestServer}.
 * Every test uses its own batch so tests that swap the global configs never overlap.
 */
@GameTestHolder(AtlaGates.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AtlaGameTests {
    private static final String EMPTY = "empty";

    private AtlaGameTests() {
    }

    // ================================================================== atla_core: narrative gating

    @GameTest(template = EMPTY, batch = "atla_core_start")
    public static void aangStartsWithOnlyAirbending(GameTestHelper h) {
        ServerPlayer p = mockPlayer(h);
        try {
            h.assertTrue(AtlaApi.getLevel(p, Element.AIR) == 1, "Airbending should start unlocked at level 1");
            for (Element e : List.of(Element.WATER, Element.EARTH, Element.FIRE)) {
                h.assertTrue(!AtlaApi.isUnlocked(p, e), e.id() + " must start locked");
                h.assertTrue(!AtlaApi.selectElement(p, e), e.id() + " is locked and must not be selectable");
                h.assertTrue(!AtlaApi.canBend(p, e, 1, false), e.id() + " must fail bending checks while locked");
            }
            h.assertTrue(AtlaApi.getActiveElement(p) == Element.AIR, "Airbending should be the selected element");
            ProgressionManager.cycle(p, 1);
            h.assertTrue(AtlaApi.getActiveElement(p) == Element.AIR, "cycling with only air unlocked must stay on air");
        } finally {
            remove(p);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "atla_core_gates")
    public static void storyFlagsClearNarrativeGates(GameTestHelper h) {
        ProgressionConfig previous = ProgressionConfig.get();
        ServerPlayer p = mockPlayer(h);
        try {
            ProgressionConfig.install(ProgressionConfig.parse(json("""
                    {
                      "gates": [
                        {"id": "water", "requires": {"flags": ["test.katara"]}, "unlock": ["water"]},
                        {"id": "water_master", "requires": {"gates": ["water"], "flags": ["test.pakku"]}, "levels": {"water": 3}},
                        {"id": "earth", "requires": {"tags": ["test_met_toph"], "notFlags": ["test.blocked"]},
                         "unlock": ["earth"], "addFlags": ["test.earth_ok"]},
                        {"id": "chained", "requires": {"flags": ["test.earth_ok"]}, "levels": {"air": 4}}
                      ]
                    }""")));

            h.assertTrue(!AtlaApi.isUnlocked(p, Element.WATER), "water should be locked before the flag");
            AtlaApi.addFlag(p, "test.katara");
            h.assertTrue(AtlaApi.getLevel(p, Element.WATER) == 1, "flag should unlock water at level 1");
            h.assertTrue(AtlaApi.getActiveElement(p) == Element.WATER, "a newly unlocked element is auto-selected");
            h.assertTrue(AtlaApi.hasClearedGate(p, "water"), "gate 'water' should be cleared");

            AtlaApi.addFlag(p, "test.pakku");
            h.assertTrue(AtlaApi.getLevel(p, Element.WATER) == 3, "second gate should raise water to 3");

            // tags aren't announced by any event: the periodic/explicit evaluation must pick them up
            p.addTag("test_met_toph");
            h.assertTrue(!AtlaApi.isUnlocked(p, Element.EARTH), "earth must wait for gate evaluation");
            ProgressionManager.evaluateGates(p);
            h.assertTrue(AtlaApi.isUnlocked(p, Element.EARTH), "tag-based gate should unlock earth");
            h.assertTrue(AtlaApi.getLevel(p, Element.AIR) == 4, "a gate's addFlags should chain into the next gate");

            AtlaApi.setLevel(p, Element.WATER, 99);
            h.assertTrue(AtlaApi.getLevel(p, Element.WATER) == 5, "levels clamp to maxLevel (5)");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            ProgressionConfig.install(previous);
            remove(p);
        }
        h.succeed();
    }

    // ================================================================== atla_gates: bendable blocks

    @GameTest(template = EMPTY, batch = "atla_gates_fill", timeoutTicks = 200)
    public static void earthbendingClearsWallThroughRealRightClick(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<BlockPos> wall = new ArrayList<>();
        for (int y = 1; y <= 3; y++) {
            for (int z = 6; z <= 8; z++) {
                BlockPos rel = new BlockPos(5, y, z);
                h.setBlock(rel, Blocks.STONE_BRICKS);
                wall.add(h.absolutePos(rel));
            }
        }
        BlockPos trigger = h.absolutePos(new BlockPos(5, 2, 7));
        BlockPos from = h.absolutePos(new BlockPos(5, 1, 6));
        BlockPos to = h.absolutePos(new BlockPos(5, 3, 8));
        String id = "test_earth_wall_" + trigger.asLong();
        installOverrides("""
                {"settings": {"animation": {"order": "outward", "blocksPerTick": 3}},
                 "overrides": [{"id": "%s", "trigger": %s, "element": "earth", "minLevel": 2,
                   "action": {"type": "clear", "from": %s, "to": %s},
                   "setFlags": ["test.wall_opened"]}]}""".formatted(id, arr(trigger), arr(from), arr(to)));

        ServerPlayer p = mockPlayer(h);
        p.setGameMode(GameType.ADVENTURE);
        p.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 7))));

        h.assertTrue(OverrideHandler.tryBend(p, trigger) == Outcome.LOCKED, "earth is locked -> LOCKED");
        AtlaApi.unlock(p, Element.EARTH);
        h.assertTrue(OverrideHandler.tryBend(p, trigger) == Outcome.LEVEL_TOO_LOW, "earth 1 < 2 -> LEVEL_TOO_LOW");
        AtlaApi.setLevel(p, Element.EARTH, 2);
        AtlaApi.selectElement(p, Element.AIR);
        h.assertTrue(OverrideHandler.tryBend(p, trigger) == Outcome.WRONG_ELEMENT, "air selected -> WRONG_ELEMENT");
        h.assertTrue(level.getBlockState(trigger).is(Blocks.STONE_BRICKS), "failed attempts must not change the world");
        AtlaApi.selectElement(p, Element.EARTH);

        // the real adventure-mode interaction path: ServerPlayerGameMode.useItemOn -> RightClickBlock event
        InteractionResult result = p.gameMode.useItemOn(p, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(trigger), Direction.WEST, trigger, false));
        h.assertTrue(result == InteractionResult.SUCCESS, "right-click should be consumed by the override, got " + result);
        h.assertTrue(OverrideState.get(level.getServer()).isDone(id), "override should be marked done");
        h.assertTrue(AtlaApi.hasFlag(p, "test.wall_opened"), "override should set its flag");
        long cleared = wall.stream().filter(pos -> level.getBlockState(pos).isAir()).count();
        h.assertTrue(cleared > 0 && cleared < wall.size(), "clearing should be animated over several ticks (cleared " + cleared + ")");

        h.startSequence()
                .thenWaitUntil(() -> {
                    for (BlockPos pos : wall) {
                        h.assertTrue(level.getBlockState(pos).isAir(), "wall block " + pos + " not cleared yet");
                    }
                })
                .thenExecute(() -> {
                    h.assertTrue(OverrideHandler.tryBend(p, trigger) == Outcome.ALREADY_DONE || level.getBlockState(trigger).isAir(),
                            "a one-shot override must not fire twice");
                    h.assertTrue(UndoStore.restore(level.getServer(), id) == wall.size(), "undo should restore the 9 wall blocks");
                    OverrideState.get(level.getServer()).clear(id);
                    for (BlockPos pos : wall) {
                        h.assertTrue(level.getBlockState(pos).is(Blocks.STONE_BRICKS), "undo should put stone bricks back at " + pos);
                    }
                    remove(p);
                    installOverrides("{}");
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, batch = "atla_gates_worldedit", timeoutTicks = 200)
    public static void worldEditSchematicPastesBackInPlaceAndAtOrigin(GameTestHelper h) throws IOException {
        ServerLevel level = h.getLevel();
        // A little "after" scene: planks floor, east-facing stairs, a chest holding a diamond, glass and an air gap
        Map<BlockPos, BlockState> scene = new HashMap<>();
        for (int x = 3; x <= 7; x++) {
            for (int z = 3; z <= 6; z++) {
                scene.put(new BlockPos(x, 1, z), Blocks.OAK_PLANKS.defaultBlockState());
                scene.put(new BlockPos(x, 2, z), Blocks.AIR.defaultBlockState());
            }
        }
        scene.put(new BlockPos(4, 2, 4), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        scene.put(new BlockPos(6, 2, 5), Blocks.CHEST.defaultBlockState());
        scene.put(new BlockPos(3, 2, 6), Blocks.GLASS.defaultBlockState());
        scene.forEach(h::setBlock);
        if (level.getBlockEntity(h.absolutePos(new BlockPos(6, 2, 5))) instanceof ChestBlockEntity chest) {
            chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
        }

        // Save it exactly like //copy (standing at 2,1,2) + //schem save, using WorldEdit's own writer
        BlockPos min = h.absolutePos(new BlockPos(3, 1, 3));
        BlockPos max = h.absolutePos(new BlockPos(7, 2, 6));
        BlockPos weOrigin = h.absolutePos(new BlockPos(2, 1, 2));
        String name = "atla_test_we_" + min.asLong();
        Path dir = FMLPaths.CONFIGDIR.get().resolve("atla_gates").resolve("schematics");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".schem");
        var weWorld = ForgeAdapter.adapt(level);
        CuboidRegion region = new CuboidRegion(weWorld, BlockVector3.at(min.getX(), min.getY(), min.getZ()),
                BlockVector3.at(max.getX(), max.getY(), max.getZ()));
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        clipboard.setOrigin(BlockVector3.at(weOrigin.getX(), weOrigin.getY(), weOrigin.getZ()));
        try (EditSession session = WorldEdit.getInstance().newEditSession(weWorld)) {
            ForwardExtentCopy copy = new ForwardExtentCopy(session, region, clipboard, region.getMinimumPoint());
            copy.setCopyingEntities(false);
            Operations.complete(copy);
        } catch (Exception e) {
            throw new IOException("WorldEdit copy failed", e);
        }
        try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_SCHEMATIC.getWriter(new FileOutputStream(file.toFile()))) {
            writer.write(clipboard);
        }

        Schematic s;
        try {
            s = SchematicLoader.load(name);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        h.assertTrue(min.equals(s.originalMin()), "original min should be " + min + " but was " + s.originalMin());
        h.assertTrue(s.pasteOffset().equals(min.subtract(weOrigin)), "paste offset should be min - origin, was " + s.pasteOffset());

        // "before" state: rubble everywhere the scene was
        for (BlockPos rel : scene.keySet()) {
            h.setBlock(rel, Blocks.COBBLESTONE);
        }

        BlockPos trigger1 = h.absolutePos(new BlockPos(1, 1, 1));
        BlockPos trigger2 = h.absolutePos(new BlockPos(1, 1, 14));
        BlockPos shiftedOrigin = weOrigin.offset(0, 0, 8);
        h.setBlock(new BlockPos(1, 1, 1), Blocks.MOSSY_COBBLESTONE);
        h.setBlock(new BlockPos(1, 1, 14), Blocks.MOSSY_COBBLESTONE);
        installOverrides("""
                {"settings": {"animation": {"order": "instant"}},
                 "overrides": [
                   {"id": "%1$s_inplace", "trigger": %2$s, "element": "air", "action": {"type": "schematic", "file": "%1$s"}},
                   {"id": "%1$s_shifted", "trigger": %3$s, "element": "air",
                    "action": {"type": "schematic", "file": "%1$s", "origin": %4$s}}
                 ]}""".formatted(name, arr(trigger1), arr(trigger2), arr(shiftedOrigin)));

        ServerPlayer p = mockPlayer(h);
        try {
            p.setGameMode(GameType.ADVENTURE);
            h.assertTrue(OverrideHandler.tryBend(p, trigger1) == Outcome.TRIGGERED, "air level 1 should bend the in-place override");
            for (Map.Entry<BlockPos, BlockState> e : scene.entrySet()) {
                BlockState actual = level.getBlockState(h.absolutePos(e.getKey()));
                h.assertTrue(actual == e.getValue(), "in-place paste at " + e.getKey() + ": expected " + e.getValue() + " got " + actual);
            }
            h.assertTrue(level.getBlockEntity(h.absolutePos(new BlockPos(6, 2, 5))) instanceof ChestBlockEntity chest
                    && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 3, "chest contents should come back from the schematic");

            h.assertTrue(OverrideHandler.tryBend(p, trigger2) == Outcome.TRIGGERED, "shifted override should trigger");
            for (Map.Entry<BlockPos, BlockState> e : scene.entrySet()) {
                BlockState actual = level.getBlockState(h.absolutePos(e.getKey().offset(0, 0, 8)));
                h.assertTrue(actual == e.getValue(), "origin paste at " + e.getKey().offset(0, 0, 8) + ": expected " + e.getValue() + " got " + actual);
            }
        } finally {
            remove(p);
            installOverrides("{}");
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "atla_gates_schem_formats")
    public static void spongeV3AndOldDataVersionsAndLargePalettes(GameTestHelper h) throws Exception {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("atla_gates").resolve("schematics");
        Files.createDirectories(dir);

        // v3 written by an older game: "grass_path" was renamed to "dirt_path" in 1.17
        CompoundTag v3 = new CompoundTag();
        v3.putInt("Version", 3);
        v3.putInt("DataVersion", 2586); // 1.16.5
        v3.putShort("Width", (short) 2);
        v3.putShort("Height", (short) 1);
        v3.putShort("Length", (short) 1);
        v3.put("Offset", new IntArrayTag(new int[]{1, 0, -2}));
        CompoundTag we = new CompoundTag();
        we.put("Origin", new IntArrayTag(new int[]{100, 64, 100}));
        CompoundTag meta = new CompoundTag();
        meta.put("WorldEdit", we);
        v3.put("Metadata", meta);
        CompoundTag palette = new CompoundTag();
        palette.putInt("minecraft:grass_path", 0);
        palette.putInt("minecraft:oak_log[axis=x]", 1);
        CompoundTag blocks = new CompoundTag();
        blocks.put("Palette", palette);
        blocks.put("Data", new ByteArrayTag(new byte[]{0, 1}));
        v3.put("Blocks", blocks);
        CompoundTag root = new CompoundTag();
        root.put("Schematic", v3);
        NbtIo.writeCompressed(root, dir.resolve("atla_test_v3.schem").toFile());

        Schematic s3 = SchematicLoader.load("atla_test_v3");
        h.assertTrue(s3.palette()[s3.blocks()[s3.index(0, 0, 0)]].is(Blocks.DIRT_PATH), "old grass_path should be upgraded to dirt_path");
        h.assertTrue(s3.palette()[s3.blocks()[s3.index(1, 0, 0)]] == Blocks.OAK_LOG.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, Direction.Axis.X), "oak_log[axis=x] should keep its axis");
        h.assertTrue(new BlockPos(1, 0, -2).equals(s3.pasteOffset()), "v3 Offset is relative to the paste origin");
        h.assertTrue(new BlockPos(101, 64, 98).equals(s3.originalMin()), "v3 original min = Origin + Offset");

        // v2 with a 160-entry palette: indices >= 128 need two-byte varints
        List<BlockState> states = new ArrayList<>();
        states.addAll(Blocks.OAK_STAIRS.getStateDefinition().getPossibleStates());
        states.addAll(Blocks.BIRCH_STAIRS.getStateDefinition().getPossibleStates());
        CompoundTag v2 = new CompoundTag();
        v2.putInt("Version", 2);
        v2.putInt("DataVersion", 3465);
        v2.putShort("Width", (short) states.size());
        v2.putShort("Height", (short) 1);
        v2.putShort("Length", (short) 1);
        CompoundTag pal2 = new CompoundTag();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < states.size(); i++) {
            pal2.putInt(net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(states.get(i)), i);
            int v = i;
            while ((v & ~0x7F) != 0) {
                data.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            data.write(v);
        }
        v2.put("Palette", pal2);
        v2.putInt("PaletteMax", states.size());
        v2.put("BlockData", new ByteArrayTag(data.toByteArray()));
        NbtIo.writeCompressed(v2, dir.resolve("atla_test_v2_big.schem").toFile());

        Schematic s2 = SchematicLoader.load("atla_test_v2_big");
        h.assertTrue(states.size() > 128, "test needs more than 128 palette entries");
        for (int x = 0; x < states.size(); x++) {
            h.assertTrue(s2.palette()[s2.blocks()[s2.index(x, 0, 0)]] == states.get(x), "varint decode mismatch at x=" + x);
        }
        h.assertTrue(BuiltInRegistries.BLOCK.getKey(Blocks.DIRT_PATH).getPath().equals("dirt_path"), "sanity");
        h.succeed();
    }

    // ================================================================== atla_gates: boundaries

    @GameTest(template = EMPTY, batch = "atla_gates_zones")
    public static void zonesKeepPlayersOutUntilTheStoryAllows(GameTestHelper h) {
        BlockPos barrierFrom = h.absolutePos(new BlockPos(8, 0, 0));
        BlockPos barrierTo = h.absolutePos(new BlockPos(11, 7, 15));
        BlockPos trigFrom = h.absolutePos(new BlockPos(1, 1, 12));
        BlockPos trigTo = h.absolutePos(new BlockPos(2, 3, 13));
        installZones("""
                {"zones": [
                  {"id": "barrier", "type": "barrier", "from": %s, "to": %s, "requires": {"flags": ["test.zone_open"]}},
                  {"id": "trig", "type": "trigger", "from": %s, "to": %s, "setFlags": ["test.entered"]}
                ]}""".formatted(arr(barrierFrom), arr(barrierTo), arr(trigFrom), arr(trigTo)));

        ServerPlayer p = mockPlayer(h);
        try {
            p.setGameMode(GameType.ADVENTURE);
            Vec3 safe = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 1, 4)));
            Vec3 inside = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(9, 1, 4)));

            p.moveTo(safe);
            tick(p);
            p.moveTo(inside);
            tick(p);
            h.assertTrue(p.position().distanceTo(safe) < 0.01, "barrier should push the player back to " + safe + ", was " + p.position());

            AtlaApi.addFlag(p, "test.zone_open");
            p.moveTo(inside);
            tick(p);
            h.assertTrue(p.position().distanceTo(inside) < 0.01, "an opened barrier must let the player in");

            p.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 12))));
            tick(p);
            h.assertTrue(AtlaApi.hasFlag(p, "test.entered"), "trigger zone should set its flag on entry");

            // bounds: the playable area is this test's 16x16 plot (full height)
            BlockPos a = h.absolutePos(new BlockPos(0, 0, 0));
            BlockPos b = h.absolutePos(new BlockPos(15, 0, 15));
            installZones("""
                    {"zones": [{"id": "plot", "type": "bounds", "from": [%d, %d], "to": [%d, %d]}]}"""
                    .formatted(a.getX(), a.getZ(), b.getX(), b.getZ()));
            p.moveTo(safe);
            tick(p);
            p.moveTo(safe.add(20, 30, 0));
            tick(p);
            h.assertTrue(p.position().distanceTo(safe) < 0.01, "leaving the bounds should push back, was " + p.position());

            p.setGameMode(GameType.CREATIVE);
            p.moveTo(safe.add(20, 0, 0));
            tick(p);
            h.assertTrue(p.position().distanceTo(safe.add(20, 0, 0)) < 0.01, "creative players are exempt");
        } finally {
            remove(p);
            installZones("{}");
        }
        h.succeed();
    }

    // ================================================================== helpers

    /**
     * A logged-in fake player. Vanilla's GameTestHelper.makeMockServerPlayerInLevel() crashes on Forge 1.20.1
     * (its Connection has no Netty channel, and Forge's login code injects pipeline filters), so the
     * connection here is backed by an EmbeddedChannel. Login fires PlayerLoggedInEvent like a real join.
     */
    private static ServerPlayer mockPlayer(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "test-aang"));
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        channel.attr(Connection.ATTRIBUTE_PROTOCOL).set(ConnectionProtocol.PLAY);
        level.getServer().getPlayerList().placeNewPlayer(connection, player);
        return player;
    }

    private static void tick(ServerPlayer p) {
        ZoneEnforcer.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, p));
    }

    private static void remove(ServerPlayer p) {
        p.server.getPlayerList().remove(p);
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static String arr(BlockPos pos) {
        return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }

    private static void installOverrides(String text) {
        List<String> errors = new ArrayList<>();
        try {
            OverrideConfig.install(OverrideConfig.parse(json(text), errors));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("override config errors: " + errors);
        }
    }

    private static void installZones(String text) {
        List<String> errors = new ArrayList<>();
        try {
            ZoneConfig.install(ZoneConfig.parse(json(text), errors));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("zone config errors: " + errors);
        }
    }
}
