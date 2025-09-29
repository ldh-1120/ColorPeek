package dev.dh.colorpeek.core;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.ChatFormatting;
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
    private static final Map<Block, BlockColors> colorCache = new WeakHashMap<>();
    private static final int MAX_COLORS = 5;
    private static final double COLOR_DISTANCE_THRESHOLD = 120d;

    @SubscribeEvent
    public static void OnItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();

        if (stack.getItem() instanceof BlockItem item && event.getEntity() != null) {
            Block block = item.getBlock();

            BlockColors colors = colorCache.computeIfAbsent(block, TooltipHandler::GetBlockColors);

            if (colors == null)
                return;

            String hex = String.format("#%06X", (0xFFFFFF & colors.avgColor));
            event.getToolTip().add(Component.literal("avg: ").withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY)));
            event.getToolTip().add(Component.literal("  ■ " + hex).withStyle(style -> style.withColor(colors.avgColor)));

            if (colors.clusterColors.size() > 1) {
                event.getToolTip().add(Component.literal("clusters: ").withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY)));
                for (int color : colors.clusterColors) {
                    hex = String.format("#%06X", (0xFFFFFF & color));
                    event.getToolTip().add(Component.literal("  ■ " + hex).withStyle(style -> style.withColor(color)));
                }
            }
        }
    }

    public static BlockColors GetBlockColors(Block block) {
        List<TextureAtlasSprite> sprites = new ArrayList<>();
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
            return null;
        }

        if (sprites.isEmpty())
            return null;

        try {
            List<int[]> pixels = new ArrayList<>();
            for (TextureAtlasSprite sprite : sprites) {
                NativeImage image = sprite.contents().getOriginalImage();
                int w = image.getWidth();
                int h = image.getHeight();

                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int pixel = image.getPixelRGBA(x, y);
                        if ((pixel & 0xFF000000) == 0)
                            continue;

                        int r = pixel & 0xFF;
                        int g = (pixel >> 8) & 0xFF;
                        int b = (pixel >> 16) & 0xFF;

                        pixels.add(new int[]{r, g, b});
                    }
                }
            }

            if (pixels.isEmpty())
                return null;

            List<Integer> result = new ArrayList<>();

            int[] avgColor = new int[] { 0, 0, 0 };
            for (int[] color : pixels) {
                avgColor[0] += color[0];
                avgColor[1] += color[1];
                avgColor[2] += color[2];
            }
            avgColor[0] /= pixels.size();
            avgColor[1] /= pixels.size();
            avgColor[2] /= pixels.size();

            List<Integer> clusters = ClusterColors(pixels);

            return new BlockColors(clusters, (avgColor[0] << 16) | (avgColor[1] << 8) | avgColor[2]);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Integer> ClusterColors(List<int[]> pixels) {
        if (pixels.isEmpty())
            return null;

        List<int[]> centroids = new ArrayList<>();
        Random random = new Random();

        centroids.add(pixels.get(random.nextInt(pixels.size())).clone());
        while (centroids.size() < Math.min(MAX_COLORS, pixels.size())) {
            double maxDist = 0;
            int[] farthest = null;

            for (int[] pixel : pixels) {
                double minDist = Double.MAX_VALUE;
                for (int[] centroid : centroids) {
                    double dist = ColorDistance(pixel, centroid);
                    minDist = Math.min(dist, minDist);
                }
                if (minDist > maxDist) {
                    maxDist = minDist;
                    farthest = pixel;
                }
            }

            if (maxDist < COLOR_DISTANCE_THRESHOLD)
                break;
            centroids.add(farthest.clone());
        }

        for (int iter = 0; iter < 10; iter++) {
            List<List<int[]>> clusters = new ArrayList<>();
            for (int i = 0; i < centroids.size(); i++)
                clusters.add(new ArrayList<>());

            for (int[] pixel : pixels) {
                int nearest = 0;
                double minDist = Double.MAX_VALUE;

                for (int i = 0; i < centroids.size(); i++) {
                    double dist = ColorDistance(pixel, centroids.get(i));
                    if (dist < minDist) {
                        minDist = dist;
                        nearest = i;
                    }
                }

                clusters.get(nearest).add(pixel);
            }

            boolean changed = false;
            for (int i = 0; i < centroids.size(); i++) {
                if (clusters.get(i).isEmpty())
                    continue;

                long sumR = 0, sumG = 0, sumB = 0;
                for (int[] pixel : clusters.get(i)) {
                    sumR += pixel[0];
                    sumG += pixel[1];
                    sumB += pixel[2];
                }

                int size = clusters.get(i).size();
                int[] newCentroid = new int[]{
                        (int) (sumR / size),
                        (int) (sumG / size),
                        (int) (sumB / size)
                };

                if (ColorDistance(centroids.get(i), newCentroid) > 1d) {
                    changed = true;
                    centroids.set(i, newCentroid);
                }
            }

            if (!changed)
                break;
        }

        List<ClusterInfo> clusterInfos = new ArrayList<>();
        for (int i = 0; i < centroids.size(); i++) {
            int count = 0;
            for (int[] pixel : pixels) {
                int nearestCluster = 0;
                double minDist = Double.MAX_VALUE;

                for (int k = 0; k < centroids.size(); k++) {
                    double dist = ColorDistance(pixel, centroids.get(k));
                    if (dist < minDist) {
                        minDist = dist;
                        nearestCluster = k;
                    }
                }

                if (nearestCluster == i)
                    count++;
            }

            if (count > 0) {
                int[] centroid = centroids.get(i);
                int color = (centroid[0] << 16) | (centroid[1] << 8) | centroid[2];
                clusterInfos.add(new ClusterInfo(color, count));
            }
        }

        clusterInfos.sort((a, b) -> Integer.compare(b.count, a.count));

        List<Integer> result = new ArrayList<>();
        for (ClusterInfo info : clusterInfos)
            result.add(info.color);

        return result.isEmpty() ? null : result;
    }

    private static double ColorDistance(int[] c1, int[] c2) {
        int dr = c1[0] - c2[0];
        int dg = c1[1] - c2[1];
        int db = c1[2] - c2[2];

        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static class ClusterInfo {
        int color;
        int count;

        ClusterInfo(int color, int count) {
            this.color = color;
            this.count = count;
        }
    }

    private static class BlockColors {
        List<Integer> clusterColors;
        int avgColor;

        BlockColors(List<Integer> clusterColors, int avgColor) {
            this.avgColor = avgColor;
            this.clusterColors = clusterColors;
        }
    }
}