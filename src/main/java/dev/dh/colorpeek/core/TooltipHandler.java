package dev.dh.colorpeek.core;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

@Mod.EventBusSubscriber(Dist.CLIENT)
public class TooltipHandler {
    private static final Map<Block, Integer> colorCache = new HashMap<>();

    @SubscribeEvent
    public static void OnItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();

        if (stack.getItem() instanceof BlockItem item && event.getEntity() != null) {
            Block block = item.getBlock();

            int color = colorCache.computeIfAbsent(block, TooltipHandler::GetBlockColor);
            String hex = String.format("#%06X", (0xFFFFFF & color));

            if (color != -1) {
                event.getToolTip().add(Component.literal(""));
                event.getToolTip().add(Component.literal("■ " + hex).withStyle(style -> style.withColor(color)));
            }
        }
    }

    public static int GetBlockColor(Block block) {
        Set<TextureAtlasSprite> sprites = new HashSet<>();
        try {
            BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(block.defaultBlockState());

            for (Direction direction : Direction.values()) {
                List<BakedQuad> quads = model.getQuads(block.defaultBlockState(), direction, RandomSource.create());
                if (!quads.isEmpty())
                    sprites.add(quads.get(0).getSprite());
            }

            List<BakedQuad> generalQuads = model.getQuads(block.defaultBlockState(), null, RandomSource.create());
            if (!generalQuads.isEmpty())
                sprites.add(generalQuads.get(0).getSprite());
        } catch (Exception e) {
            e.printStackTrace();
        }

        long totalR = 0, totalG = 0, totalB = 0, count = 0;

        for (TextureAtlasSprite sprite : sprites) {
            try {
                NativeImage image = sprite.contents().getOriginalImage();
                int w = image.getWidth();
                int h = image.getHeight();

                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int pixel = image.getPixelRGBA(x, y);

                        int a = (pixel >> 24) & 0xFF;
                        if (a == 0) continue;

                        int b = (pixel >> 16) & 0xFF;
                        int g = (pixel >> 8) & 0xFF;
                        int r = pixel & 0xFF;

                        totalR += r;
                        totalG += g;
                        totalB += b;
                        count++;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (count == 0)
            return -1;

        int avgR = (int) (totalR / count);
        int avgG = (int) (totalG / count);
        int avgB = (int) (totalB / count);

        return (avgR << 16) | (avgG << 8) | avgB;
    }
}
