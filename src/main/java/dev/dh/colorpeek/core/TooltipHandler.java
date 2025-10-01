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
    private static final double COLOR_DISTANCE_THRESHOLD = 70d;
    private static final double MIN_COLOR_RATIO = 0.001;

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
            List<double[]> pixelsLab = new ArrayList<>();
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

                        double[] lab = RgbToLab(r, g, b);
                        pixelsLab.add(lab);
                    }
                }
            }

            if (pixelsLab.isEmpty())
                return null;

            double[] avgColor = new double[]{0, 0, 0};
            for (double[] lab : pixelsLab) {
                avgColor[0] += lab[0];
                avgColor[1] += lab[1];
                avgColor[2] += lab[2];
            }
            avgColor[0] /= pixelsLab.size();
            avgColor[1] /= pixelsLab.size();
            avgColor[2] /= pixelsLab.size();

            List<Integer> clusters = ClusterColors(pixelsLab);

            return new BlockColors(clusters, labToRgb(avgColor));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Integer> ClusterColors(List<double[]> pixelsLab) {
        if (pixelsLab.isEmpty())
            return null;

        int totalPixels = pixelsLab.size();
        int minPixelsForCluster = (int) (totalPixels * MIN_COLOR_RATIO);

        Map<Integer, List<double[]>> colorGroups = new WeakHashMap<>();

        for (double[] lab : pixelsLab) {
            int tempColor = labToRgb(lab);

            boolean foundGroup = false;
            for (Map.Entry<Integer, List<double[]>> entry : colorGroups.entrySet()) {
                List<double[]> group = entry.getValue();
                if (group.isEmpty())
                    continue;

                double[] groupLab = group.get(0);
                if (DeltaE(lab, groupLab) < COLOR_DISTANCE_THRESHOLD / 2) {
                    entry.getValue().add(lab);
                    foundGroup = true;
                    break;
                }
            }

            if (!foundGroup) {
                List<double[]> newGroup = new ArrayList<>();
                newGroup.add(lab);
                colorGroups.put(tempColor, newGroup);
            }
        }

        List<ClusterInfo> clusters = new ArrayList<>();
        for (List<double[]> group : colorGroups.values()) {
            if (group.size() < minPixelsForCluster)
                continue;

            double sumL = 0, sumA = 0, sumB = 0;
            for (double[] lab : group) {
                sumL += lab[0];
                sumA += lab[1];
                sumB += lab[2];
            }

            int size = group.size();
            double[] avgLab = new double[]{
                    sumL / size,
                    sumA / size,
                    sumB / size
            };

            int color = labToRgb(avgLab);
            clusters.add(new ClusterInfo(color, avgLab, size));
        }

        if (clusters.size() > 1 && clusters.size() <= MAX_COLORS)
            clusters = RefineWithKMeans(pixelsLab, clusters, 20);

        clusters.sort((a, b) -> Integer.compare(b.count, a.count));

        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_COLORS, clusters.size()); i++)
            result.add(clusters.get(i).color);

        return result.isEmpty() ? null : result;
    }

    private static double[] RgbToLab(int r, int g, int b) {
        double rNorm = r / 255d;
        double gNorm = g / 255d;
        double bNorm = b / 255d;

        rNorm = (rNorm > 0.04045) ? Math.pow((rNorm + 0.055) / 1.055, 2.4) : rNorm / 12.92;
        gNorm = (gNorm > 0.04045) ? Math.pow((gNorm + 0.055) / 1.055, 2.4) : gNorm / 12.92;
        bNorm = (bNorm > 0.04045) ? Math.pow((bNorm + 0.055) / 1.055, 2.4) : bNorm / 12.92;

        double x = rNorm * 0.4124564 + gNorm * 0.3575761 + bNorm * 0.1804375;
        double y = rNorm * 0.2126729 + gNorm * 0.7151522 + bNorm * 0.0721750;
        double z = rNorm * 0.0193339 + gNorm * 0.1191920 + bNorm * 0.9503041;

        x = x / 0.95047;
        z = z / 1.08883;

        x = (x > 0.008856) ? Math.pow(x, 1d / 3d) : (7.787 * x + 16d / 116d);
        y = (y > 0.008856) ? Math.pow(y, 1d / 3d) : (7.787 * y + 16d / 116d);
        z = (z > 0.008856) ? Math.pow(z, 1d / 3d) : (7.787 * z + 16d / 116d);

        double L = 116.0 * y - 16.0;
        double a = 500.0 * (x - y);
        double bVal = 200.0 * (y - z);

        return new double[]{L, a, bVal};
    }

    private static int labToRgb(double[] lab) {
        double L = lab[0];
        double a = lab[1];
        double bVal = lab[2];

        double y = (L + 16.0) / 116.0;
        double x = a / 500.0 + y;
        double z = y - bVal / 200.0;

        double x3 = x * x * x;
        double y3 = y * y * y;
        double z3 = z * z * z;

        x = (x3 > 0.008856) ? x3 : (x - 16.0 / 116.0) / 7.787;
        y = (y3 > 0.008856) ? y3 : (y - 16.0 / 116.0) / 7.787;
        z = (z3 > 0.008856) ? z3 : (z - 16.0 / 116.0) / 7.787;

        x *= 0.95047;
        z *= 1.08883;

        double r = x * 3.2404542 + y * -1.5371385 + z * -0.4985314;
        double g = x * -0.9692660 + y * 1.8760108 + z * 0.0415560;
        double b = x * 0.0556434 + y * -0.2040259 + z * 1.0572252;

        r = (r > 0.0031308) ? 1.055 * Math.pow(r, 1.0 / 2.4) - 0.055 : 12.92 * r;
        g = (g > 0.0031308) ? 1.055 * Math.pow(g, 1.0 / 2.4) - 0.055 : 12.92 * g;
        b = (b > 0.0031308) ? 1.055 * Math.pow(b, 1.0 / 2.4) - 0.055 : 12.92 * b;

        int rInt = Math.max(0, Math.min(255, (int) (r * 255d + 0.5)));
        int gInt = Math.max(0, Math.min(255, (int) (g * 255d + 0.5)));
        int bInt = Math.max(0, Math.min(255, (int) (b * 255d + 0.5)));

        return (rInt << 16) | (gInt << 8) | bInt;
    }

    private static double DeltaE(double[] lab1, double[] lab2) {
        double dL = lab1[0] - lab2[0];
        double da = lab1[1] - lab2[1];
        double db = lab1[2] - lab2[2];
        return Math.sqrt(dL * dL + da * da + db * db);
    }

    private static List<ClusterInfo> RefineWithKMeans(List<double[]> pixelsLab, List<ClusterInfo> initialClusters, int iteration) {
        List<double[]> centroids = new ArrayList<>();
        for (ClusterInfo cluster : initialClusters)
            centroids.add(cluster.lab.clone());

        for (int iter = 0; iter < iteration; iter++) {
            List<List<double[]>> clusters = new ArrayList<>();
            for (int i = 0; i < centroids.size(); i++)
                clusters.add(new ArrayList<>());

            for (double[] lab : pixelsLab) {
                int nearest = 0;
                double minDist = Double.MAX_VALUE;

                for (int i = 0; i < centroids.size(); i++) {
                    double dist = DeltaE(lab, centroids.get(i));
                    if (dist < minDist) {
                        minDist = dist;
                        nearest = i;
                    }
                }
                clusters.get(nearest).add(lab);
            }

            boolean changed = false;
            for (int i = 0; i < centroids.size(); i++) {
                if (clusters.get(i).isEmpty())
                    continue;

                double sumL = 0, sumA = 0, sumB = 0;
                for (double[] lab : clusters.get(i)) {
                    sumL += lab[0];
                    sumA += lab[1];
                    sumB += lab[2];
                }

                int size = clusters.get(i).size();
                double[] newCentroid = new double[]{
                        sumL / size,
                        sumA / size,
                        sumB / size
                };

                if (DeltaE(centroids.get(i), newCentroid) > 1d) {
                    changed = true;
                    centroids.set(i, newCentroid);
                }
            }

            if (!changed)
                break;
        }

        List<ClusterInfo> result = new ArrayList<>();
        for (int i = 0; i < centroids.size(); i++) {
            int count = 0;
            for (double[] lab : pixelsLab) {
                int nearestCluster = 0;
                double minDist = Double.MAX_VALUE;

                for (int k = 0; k < centroids.size(); k++) {
                    double dist = DeltaE(lab, centroids.get(k));
                    if (dist < minDist) {
                        minDist = dist;
                        nearestCluster = k;
                    }
                }

                if (nearestCluster == i)
                    count++;
            }

            if (count > 0) {
                int color = labToRgb(centroids.get(i));
                result.add(new ClusterInfo(color, centroids.get(i), count));
            }
        }

        return result;
    }

    private static class ClusterInfo {
        int color;
        double[] lab;
        int count;

        ClusterInfo(int color, double[] lab, int count) {
            this.color = color;
            this.lab = lab;
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