package com.adpulsipher.atla.core.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Small helpers for the hand-written JSON configs. Parsing is lenient, so map makers can use
 * {@code //} and {@code #} comments and trailing commas in their files.
 */
public final class Json {
    public static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Json() {
    }

    public static JsonObject readObject(Path file) throws ConfigException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonReader json = new JsonReader(reader);
            json.setLenient(true);
            JsonElement element = JsonParser.parseReader(json);
            if (!element.isJsonObject()) {
                throw new ConfigException(file.getFileName() + ": the top level must be a JSON object { ... }");
            }
            return element.getAsJsonObject();
        } catch (IOException e) {
            throw new ConfigException("Could not read " + file + ": " + e.getMessage(), e);
        } catch (JsonParseException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ConfigException(file.getFileName() + " is not valid JSON: " + cause.getMessage(), e);
        }
    }

    public static String string(JsonObject obj, String key, @Nullable String def) {
        JsonElement e = obj.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    public static String requireString(JsonObject obj, String key, String where) throws ConfigException {
        String s = string(obj, key, null);
        if (s == null || s.isBlank()) {
            throw new ConfigException(where + ": missing \"" + key + "\"");
        }
        return s;
    }

    public static int integer(JsonObject obj, String key, int def) throws ConfigException {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        try {
            return e.getAsInt();
        } catch (RuntimeException ex) {
            throw new ConfigException("\"" + key + "\" must be a whole number, got " + e);
        }
    }

    public static double number(JsonObject obj, String key, double def) throws ConfigException {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            throw new ConfigException("\"" + key + "\" must be a number, got " + e);
        }
    }

    public static boolean bool(JsonObject obj, String key, boolean def) {
        JsonElement e = obj.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsBoolean();
    }

    @Nullable
    public static JsonObject object(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** Accepts either a single string or an array of strings. */
    public static List<String> strings(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) {
            return Collections.emptyList();
        }
        if (e.isJsonArray()) {
            List<String> out = new ArrayList<>();
            for (JsonElement item : e.getAsJsonArray()) {
                out.add(item.getAsString());
            }
            return out;
        }
        return List.of(e.getAsString());
    }

    public static List<JsonObject> objects(JsonObject obj, String key) throws ConfigException {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) {
            return Collections.emptyList();
        }
        if (e.isJsonObject()) {
            return List.of(e.getAsJsonObject());
        }
        if (!e.isJsonArray()) {
            throw new ConfigException("\"" + key + "\" must be a list of objects");
        }
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement item : e.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                throw new ConfigException("every entry in \"" + key + "\" must be an object { ... }, got " + item);
            }
            out.add(item.getAsJsonObject());
        }
        return out;
    }

    /**
     * Reads a block position written as {@code [x, y, z]}, {@code "x y z"} or {@code {"x":..,"y":..,"z":..}}.
     */
    public static BlockPos blockPos(JsonElement e, String where) throws ConfigException {
        int[] v = ints(e, 3, where);
        return new BlockPos(v[0], v[1], v[2]);
    }

    /** Reads exactly {@code count} integers from an array, a space separated string or an x/y/z object. */
    public static int[] ints(JsonElement e, int count, String where) throws ConfigException {
        int[] out = new int[count];
        try {
            if (e.isJsonArray()) {
                JsonArray arr = e.getAsJsonArray();
                if (arr.size() != count) {
                    throw new ConfigException(where + ": expected " + count + " numbers, got " + arr);
                }
                for (int i = 0; i < count; i++) {
                    out[i] = (int) Math.floor(arr.get(i).getAsDouble());
                }
                return out;
            }
            if (e.isJsonPrimitive()) {
                String[] parts = e.getAsString().trim().split("[\\s,]+");
                if (parts.length != count) {
                    throw new ConfigException(where + ": expected " + count + " numbers, got \"" + e.getAsString() + "\"");
                }
                for (int i = 0; i < count; i++) {
                    out[i] = (int) Math.floor(Double.parseDouble(parts[i]));
                }
                return out;
            }
            if (e.isJsonObject() && count == 3) {
                JsonObject o = e.getAsJsonObject();
                out[0] = o.get("x").getAsInt();
                out[1] = o.get("y").getAsInt();
                out[2] = o.get("z").getAsInt();
                return out;
            }
        } catch (ConfigException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ConfigException(where + ": could not read coordinates from " + e);
        }
        throw new ConfigException(where + ": could not read coordinates from " + e);
    }

    public static ResourceLocation id(String raw, String where) throws ConfigException {
        ResourceLocation rl = ResourceLocation.tryParse(raw.trim());
        if (rl == null) {
            throw new ConfigException(where + ": \"" + raw + "\" is not a valid id (expected namespace:path)");
        }
        return rl;
    }

    /** Writes {@code content} to {@code file} if it does not exist yet. Returns true if written. */
    public static boolean writeDefault(Path file, String content) throws IOException {
        if (Files.exists(file)) {
            return false;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return true;
    }
}
