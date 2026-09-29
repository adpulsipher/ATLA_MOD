package com.adpulsipher.atla.core.client;

import com.adpulsipher.atla.core.AtlaCore;
import com.adpulsipher.atla.core.api.Element;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/**
 * Draws the selected element in a slot styled after the KyoshiCraft hotbar (dark bark-brown slot,
 * riveted corners, bevelled edge) with level pips underneath the icon.
 *
 * <p>Texture layout of {@code element_hud.png} (128x32, made by tools/make_hud_textures.py):
 * slot frame 22x22 at (0,0) - the same height as the hotbar; element icons 16x16 at (32 + 16*i, 0)
 * for air, water, earth, fire; empty pip 3x3 at (0,24); filled pip (white, tinted per element) at (4,24).
 * Level pips stack upwards in a column just right of the slot.</p>
 */
public final class ElementHud {
    @SuppressWarnings("removal")
    private static final ResourceLocation TEXTURE = new ResourceLocation(AtlaCore.MOD_ID, "textures/gui/element_hud.png");
    private static final int TEX_W = 128;
    private static final int TEX_H = 32;
    /** Slot size; matches the hotbar's 22px height so it lines up with it. */
    private static final int SIZE = 22;
    /** Slot plus the pip column beside it. */
    private static final int WIDTH = SIZE + 4;
    private static final int NAME_TICKS = 50;

    private ElementHud() {
    }

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        Element active = ClientProgression.active();
        if (mc.options.hideGui || player == null || player.isSpectator() || active == null || !ClientConfig.HUD_ENABLED.get()) {
            return;
        }

        int x;
        int y;
        boolean textOnLeft = false;
        switch (ClientConfig.HUD_ANCHOR.get()) {
            case HOTBAR_LEFT -> {
                x = screenWidth / 2 - 91 - 4 - WIDTH;
                y = screenHeight - SIZE;
                // vanilla draws the off-hand slot here for right-handed players
                if (player.getMainArm() == HumanoidArm.RIGHT && !player.getOffhandItem().isEmpty()) {
                    x -= 29;
                }
                textOnLeft = true;
            }
            case TOP_LEFT -> {
                x = 4;
                y = 4;
            }
            case TOP_RIGHT -> {
                x = screenWidth - WIDTH - 4;
                y = 4;
                textOnLeft = true;
            }
            default -> {
                x = screenWidth / 2 + 91 + 4;
                y = screenHeight - SIZE;
                // step around the off-hand slot (left-handed) and the hotbar attack indicator
                if (player.getMainArm() == HumanoidArm.LEFT && !player.getOffhandItem().isEmpty()) {
                    x += 29;
                }
                if (mc.options.attackIndicator().get() == AttackIndicatorStatus.HOTBAR) {
                    x += 22;
                }
            }
        }
        x += ClientConfig.HUD_OFFSET_X.get();
        y += ClientConfig.HUD_OFFSET_Y.get();

        RenderSystem.enableBlend();
        g.blit(TEXTURE, x, y, 0, 0, SIZE, SIZE, TEX_W, TEX_H);
        g.blit(TEXTURE, x + 3, y + 3, 32 + 16 * active.ordinal(), 0, 16, 16, TEX_W, TEX_H);
        drawLevel(g, mc, active, x, y);

        if (ClientConfig.SHOW_ELEMENT_NAME.get() && mc.level != null) {
            long age = mc.level.getGameTime() - ClientProgression.changedAt();
            if (age >= 0 && age < NAME_TICKS) {
                float alpha = Mth.clamp((NAME_TICKS - age - partialTick) / 10f, 0f, 1f);
                if (alpha > 0.05f) {
                    Component name = active.displayName();
                    int w = mc.font.width(name);
                    int tx = textOnLeft ? x - 4 - w : x + WIDTH + 3;
                    int color = ((int) (alpha * 255) << 24) | 0xFFFFFF;
                    g.drawString(mc.font, name, tx, y + 8, color, true);
                }
            }
        }
        RenderSystem.disableBlend();
    }

    private static void drawLevel(GuiGraphics g, Minecraft mc, Element element, int x, int y) {
        int level = ClientProgression.level(element);
        int max = ClientProgression.maxLevel(element);
        if (max <= 5) {
            int column = x + SIZE + 1;
            int bottom = y + (SIZE + (max * 4 - 1)) / 2 - 3;
            for (int i = 0; i < max; i++) {
                int py = bottom - i * 4;
                if (i < level) {
                    int c = element.color();
                    g.setColor(((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f, 1f);
                    g.blit(TEXTURE, column, py, 4, 24, 3, 3, TEX_W, TEX_H);
                    g.setColor(1f, 1f, 1f, 1f);
                } else {
                    g.blit(TEXTURE, column, py, 0, 24, 3, 3, TEX_W, TEX_H);
                }
            }
        } else {
            // too many levels for pips: show the number in the slot corner, like an item count
            String text = String.valueOf(level);
            g.pose().pushPose();
            g.pose().translate(0, 0, 200);
            g.drawString(mc.font, text, x + SIZE - 2 - mc.font.width(text), y + SIZE - 10, 0xFFFFFF, true);
            g.pose().popPose();
        }
    }
}
