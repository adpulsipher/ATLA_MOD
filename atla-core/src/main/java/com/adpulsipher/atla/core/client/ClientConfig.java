package com.adpulsipher.atla.core.client;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/atla_core-client.toml - purely cosmetic, per-player settings. */
public final class ClientConfig {
    public enum Anchor { HOTBAR_RIGHT, HOTBAR_LEFT, TOP_LEFT, TOP_RIGHT }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue HUD_ENABLED;
    public static final ForgeConfigSpec.EnumValue<Anchor> HUD_ANCHOR;
    public static final ForgeConfigSpec.IntValue HUD_OFFSET_X;
    public static final ForgeConfigSpec.IntValue HUD_OFFSET_Y;
    public static final ForgeConfigSpec.BooleanValue SHOW_ELEMENT_NAME;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("hud");
        HUD_ENABLED = b.comment("Show the active bending element next to the hotbar").define("enabled", true);
        HUD_ANCHOR = b.comment("Where the element slot is drawn").defineEnum("anchor", Anchor.HOTBAR_RIGHT);
        HUD_OFFSET_X = b.comment("Extra horizontal offset in GUI pixels").defineInRange("offsetX", 0, -1000, 1000);
        HUD_OFFSET_Y = b.comment("Extra vertical offset in GUI pixels").defineInRange("offsetY", 0, -1000, 1000);
        SHOW_ELEMENT_NAME = b.comment("Briefly show the element name when switching").define("showElementName", true);
        b.pop();
        SPEC = b.build();
    }

    private ClientConfig() {
    }
}
