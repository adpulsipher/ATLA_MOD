package com.adpulsipher.atla.gates.schematic;

import com.adpulsipher.atla.core.util.ConfigException;
import com.adpulsipher.atla.gates.AtlaGates;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads WorldEdit / Sponge {@code .schem} files (Sponge schematic versions 1, 2 and 3, as written by
 * WorldEdit 7.x) and vanilla structure-block {@code .nbt} files, with no WorldEdit needed at runtime.
 *
 * <p>Files are looked up by name in, in order: {@code config/atla_gates/schematics/},
 * {@code config/schematics/} and {@code config/worldedit/schematics/} - the last one is where
 * WorldEdit for Forge saves {@code //schem save <name>}, so authors can reference schematics
 * directly. Block names from older Minecraft versions (e.g. schematics saved in 1.18) are upgraded
 * with Minecraft's own DataFixer.</p>
 */
public final class SchematicLoader {
    private static final String[] EXTENSIONS = {"", ".schem", ".schematic", ".nbt"};
    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(long modified, Schematic schematic) {
    }

    private SchematicLoader() {
    }

    public static List<Path> searchDirectories() {
        Path config = FMLPaths.CONFIGDIR.get();
        return List.of(config.resolve("atla_gates").resolve("schematics"), config.resolve("schematics"),
                config.resolve("worldedit").resolve("schematics"));
    }

    public static Path resolve(String name) throws ConfigException {
        if (name.contains("..")) {
            throw new ConfigException("schematic name '" + name + "' may not contain '..'");
        }
        for (Path dir : searchDirectories()) {
            for (String ext : EXTENSIONS) {
                Path candidate = dir.resolve(name + ext);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        throw new ConfigException("schematic '" + name + "' not found in " + searchDirectories());
    }

    /** Loads (or returns the cached copy of) a schematic by file name. */
    public static Schematic load(String name) throws ConfigException {
        Path file = resolve(name);
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            Cached cached = CACHE.get(file);
            if (cached != null && cached.modified == modified) {
                return cached.schematic;
            }
            Schematic s = read(file);
            CACHE.put(file, new Cached(modified, s));
            return s;
        } catch (IOException e) {
            throw new ConfigException("could not read schematic " + file + ": " + e.getMessage(), e);
        }
    }

    public static Schematic read(Path file) throws IOException, ConfigException {
        CompoundTag root = readNbt(file);
        String name = file.getFileName().toString();
        if (name.endsWith(".nbt") || (root.contains("size", Tag.TAG_LIST) && root.contains("blocks", Tag.TAG_LIST))) {
            return readStructure(root, name);
        }
        return readSponge(root, name);
    }

    /** Most files are gzipped; fall back to raw NBT for tools that skip compression. */
    private static CompoundTag readNbt(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return NbtIo.readCompressed(in);
        } catch (IOException gzipFailed) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                return NbtIo.read(in);
            }
        }
    }

    // ------------------------------------------------------------------ Sponge / WorldEdit .schem

    public static Schematic readSponge(CompoundTag root, String name) throws ConfigException {
        // v3 nests everything in a "Schematic" compound; v1/v2 use the root tag itself
        CompoundTag s = root.contains("Schematic", Tag.TAG_COMPOUND) ? root.getCompound("Schematic") : root;
        int version = s.getInt("Version");
        if (!s.contains("Width") || !s.contains("Height") || !s.contains("Length")) {
            if (s.contains("Materials") || s.contains("Blocks", Tag.TAG_BYTE_ARRAY)) {
                throw new ConfigException(name + " is a legacy MCEdit schematic (pre-1.13 numeric ids). "
                        + "Re-save it with WorldEdit 7 (//schem save) to get a .schem file.");
            }
            throw new ConfigException(name + " is not a Sponge/WorldEdit schematic (no Width/Height/Length)");
        }
        int width = s.getShort("Width") & 0xFFFF;
        int height = s.getShort("Height") & 0xFFFF;
        int length = s.getShort("Length") & 0xFFFF;
        int dataVersion = s.contains("DataVersion") ? s.getInt("DataVersion") : -1;

        CompoundTag paletteTag;
        byte[] data;
        ListTag blockEntityList;
        boolean nestedData;
        if (version >= 3) {
            CompoundTag blocks = s.getCompound("Blocks");
            paletteTag = blocks.getCompound("Palette");
            data = blocks.getByteArray("Data");
            blockEntityList = blocks.getList("BlockEntities", Tag.TAG_COMPOUND);
            nestedData = true;
        } else {
            paletteTag = s.getCompound("Palette");
            data = s.getByteArray("BlockData");
            blockEntityList = s.contains("BlockEntities", Tag.TAG_LIST)
                    ? s.getList("BlockEntities", Tag.TAG_COMPOUND) : s.getList("TileEntities", Tag.TAG_COMPOUND);
            nestedData = false;
        }

        // Offsets: see WorldEdit's SpongeSchematicV1Reader / V3Reader.
        BlockPos pasteOffset = BlockPos.ZERO;
        BlockPos originalMin = null;
        int[] offset = s.getIntArray("Offset");
        CompoundTag meta = s.getCompound("Metadata");
        if (version >= 3) {
            if (offset.length == 3) {
                pasteOffset = new BlockPos(offset[0], offset[1], offset[2]);
            }
            int[] origin = meta.getCompound("WorldEdit").getIntArray("Origin");
            if (origin.length == 3) {
                originalMin = new BlockPos(origin[0], origin[1], origin[2]).offset(pasteOffset);
            }
        } else {
            if (offset.length == 3) {
                originalMin = new BlockPos(offset[0], offset[1], offset[2]);
            }
            if (meta.contains("WEOffsetX")) {
                pasteOffset = new BlockPos(meta.getInt("WEOffsetX"), meta.getInt("WEOffsetY"), meta.getInt("WEOffsetZ"));
            }
        }

        int paletteSize = 0;
        for (String key : paletteTag.getAllKeys()) {
            paletteSize = Math.max(paletteSize, paletteTag.getInt(key) + 1);
        }
        BlockState[] palette = new BlockState[paletteSize];
        Arrays.fill(palette, Blocks.AIR.defaultBlockState());
        List<String> unknown = new ArrayList<>();
        for (String key : paletteTag.getAllKeys()) {
            palette[paletteTag.getInt(key)] = parseState(key, dataVersion, unknown);
        }
        if (!unknown.isEmpty()) {
            AtlaGates.LOGGER.warn("Schematic {} uses blocks that do not exist here and will be placed as air: {}", name, unknown);
        }

        int volume = width * height * length;
        int[] blocks = decodeVarInts(data, volume, name);
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] < 0 || blocks[i] >= palette.length) {
                throw new ConfigException(name + ": block data refers to palette entry " + blocks[i] + " but the palette has " + palette.length);
            }
        }

        Map<Integer, CompoundTag> blockEntities = new HashMap<>();
        for (int i = 0; i < blockEntityList.size(); i++) {
            CompoundTag be = blockEntityList.getCompound(i);
            int[] pos = be.getIntArray("Pos");
            if (pos.length != 3) {
                continue;
            }
            CompoundTag tag;
            if (nestedData) {
                tag = be.getCompound("Data").copy();
            } else {
                tag = be.copy();
                tag.remove("Pos");
                tag.remove("Id");
            }
            tag.putString("id", be.getString("Id"));
            blockEntities.put(pos[0] + pos[2] * width + pos[1] * width * length, fixBlockEntity(tag, dataVersion));
        }
        return new Schematic(name, width, height, length, palette, blocks, blockEntities, pasteOffset, originalMin);
    }

    private static int[] decodeVarInts(byte[] data, int expected, String name) throws ConfigException {
        int[] out = new int[expected];
        int index = 0;
        int i = 0;
        while (i < data.length) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                if (i >= data.length) {
                    throw new ConfigException(name + ": block data ends in the middle of a value");
                }
                b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > 35) {
                    throw new ConfigException(name + ": block data contains an oversized value");
                }
            } while ((b & 0x80) != 0);
            if (index >= expected) {
                throw new ConfigException(name + ": block data has more entries than Width*Height*Length");
            }
            out[index++] = value;
        }
        if (index != expected) {
            throw new ConfigException(name + ": block data has " + index + " entries, expected " + expected);
        }
        return out;
    }

    // ------------------------------------------------------------------ vanilla structure .nbt

    public static Schematic readStructure(CompoundTag root, String name) throws ConfigException {
        ListTag size = root.getList("size", Tag.TAG_INT);
        if (size.size() != 3) {
            throw new ConfigException(name + " is not a structure file (missing size)");
        }
        int width = size.getInt(0);
        int height = size.getInt(1);
        int length = size.getInt(2);
        int dataVersion = root.contains("DataVersion") ? root.getInt("DataVersion") : -1;

        ListTag paletteList = root.contains("palette", Tag.TAG_LIST)
                ? root.getList("palette", Tag.TAG_COMPOUND)
                : root.getList("palettes", Tag.TAG_LIST).getList(0);
        BlockState[] palette = new BlockState[paletteList.size()];
        HolderLookup<Block> lookup = BuiltInRegistries.BLOCK.asLookup();
        for (int i = 0; i < paletteList.size(); i++) {
            palette[i] = NbtUtils.readBlockState(lookup, fixStateTag(paletteList.getCompound(i), dataVersion));
        }

        int[] blocks = new int[width * height * length];
        Arrays.fill(blocks, -1);
        Map<Integer, CompoundTag> blockEntities = new HashMap<>();
        ListTag blockList = root.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag b = blockList.getCompound(i);
            ListTag pos = b.getList("pos", Tag.TAG_INT);
            int x = pos.getInt(0);
            int y = pos.getInt(1);
            int z = pos.getInt(2);
            if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
                continue;
            }
            int index = x + z * width + y * width * length;
            blocks[index] = b.getInt("state");
            if (b.contains("nbt", Tag.TAG_COMPOUND)) {
                blockEntities.put(index, fixBlockEntity(b.getCompound("nbt").copy(), dataVersion));
            }
        }
        return new Schematic(name, width, height, length, palette, blocks, blockEntities, BlockPos.ZERO, null);
    }

    // ------------------------------------------------------------------ block states & data fixing

    private static int currentDataVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    /** Parses "minecraft:oak_stairs[facing=north]", upgrading it first if it was saved by an older game version. */
    private static BlockState parseState(String raw, int dataVersion, List<String> unknown) {
        String text = raw;
        if (dataVersion > 0 && dataVersion < currentDataVersion()) {
            CompoundTag fixed = fixStateTag(stateStringToTag(raw), dataVersion);
            text = tagToStateString(fixed);
        }
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), text, false).blockState();
        } catch (CommandSyntaxException e) {
            unknown.add(raw);
            return Blocks.AIR.defaultBlockState();
        }
    }

    private static CompoundTag fixStateTag(CompoundTag tag, int dataVersion) {
        if (dataVersion <= 0 || dataVersion >= currentDataVersion()) {
            return tag;
        }
        return (CompoundTag) DataFixers.getDataFixer()
                .update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, tag), dataVersion, currentDataVersion())
                .getValue();
    }

    private static CompoundTag fixBlockEntity(CompoundTag tag, int dataVersion) {
        if (dataVersion <= 0 || dataVersion >= currentDataVersion()) {
            return tag;
        }
        return (CompoundTag) DataFixers.getDataFixer()
                .update(References.BLOCK_ENTITY, new Dynamic<>(NbtOps.INSTANCE, tag), dataVersion, currentDataVersion())
                .getValue();
    }

    private static CompoundTag stateStringToTag(String raw) {
        CompoundTag tag = new CompoundTag();
        int bracket = raw.indexOf('[');
        tag.putString("Name", bracket < 0 ? raw : raw.substring(0, bracket));
        if (bracket >= 0 && raw.endsWith("]")) {
            CompoundTag props = new CompoundTag();
            for (String pair : raw.substring(bracket + 1, raw.length() - 1).split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    props.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
            tag.put("Properties", props);
        }
        return tag;
    }

    private static String tagToStateString(CompoundTag tag) {
        StringBuilder sb = new StringBuilder(tag.getString("Name"));
        CompoundTag props = tag.getCompound("Properties");
        if (!props.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (String key : props.getAllKeys()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(key).append('=').append(props.getString(key));
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }

    public static void clearCache() {
        CACHE.clear();
    }
}
