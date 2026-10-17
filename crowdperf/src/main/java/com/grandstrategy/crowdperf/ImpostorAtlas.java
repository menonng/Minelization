package com.grandstrategy.crowdperf;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.metadata.animation.VillagerMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * One texture for the whole batch. A villager is a base skin plus biome and profession overlays drawn over the same
 * model with the same UV layout, so stacking the overlays onto the skin once, texel by texel, looks the same as
 * drawing three layers: each look (kind × biome × profession) becomes one 64×64 slot here, and every batched
 * villager is drawn in a single call with one texture.
 */
final class ImpostorAtlas {
    static final Identifier ID = Identifier.fromNamespaceAndPath("crowdperf", "batch_atlas");
    static final int SLOT = 64;
    static final int COLUMNS = 16;
    static final int ROWS = 16;
    /** The entity shaders' alpha cutout (0.1). */
    private static final int CUTOUT = 26;

    /** UV areas of the head, hat and hat rim: where a hidden biome hat must not show (see VillagerProfessionLayer). */
    private static final int[][] HAT_AREAS = {{0, 0, 64, 18}, {30, 47, 64, 64}};

    private final Map<String, Integer> slots = new HashMap<>();
    private final Map<Identifier, Optional<NativeImage>> images = new HashMap<>();
    private final Map<Identifier, VillagerMetadataSection.Hat> hats = new HashMap<>();
    private DynamicTexture texture;
    private boolean dirty;

    /** The slot for this look, composing it on first use (a null base only looks it up); -1 when unknown or full. */
    int slot(String key, Identifier base, List<Layer> layers) {
        Integer slot = slots.get(key);
        if (slot != null) return slot;
        if (base == null || slots.size() >= COLUMNS * ROWS) return -1;
        if (texture == null) {
            texture = new DynamicTexture(() -> "CrowdPerf batch atlas", SLOT * COLUMNS, SLOT * ROWS, true);
            Minecraft.getInstance().getTextureManager().register(ID, texture);
        }
        int index = slots.size();
        compose(index, base, layers);
        slots.put(key, index);
        dirty = true;
        return index;
    }

    /** Uploads new slots; called once per rebuild. */
    void flush() {
        if (dirty && texture != null) {
            texture.upload();
            dirty = false;
        }
    }

    int size() {
        return slots.size();
    }

    void clear() {
        slots.clear();
        images.values().forEach(o -> o.ifPresent(NativeImage::close));
        images.clear();
        hats.clear();
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(ID);
            texture = null;
        }
    }

    VillagerMetadataSection.Hat hat(Identifier id) {
        return hats.computeIfAbsent(id, k -> {
            ResourceManager resources = Minecraft.getInstance().getResourceManager();
            Optional<Resource> resource = resources.getResource(k);
            if (resource.isEmpty()) return VillagerMetadataSection.Hat.NONE;
            try {
                return resource.get().metadata().getSection(VillagerMetadataSection.TYPE).map(VillagerMetadataSection::hat)
                    .orElse(VillagerMetadataSection.Hat.NONE);
            } catch (IOException e) {
                return VillagerMetadataSection.Hat.NONE;
            }
        });
    }

    /** An overlay texture; {@code skipHat} leaves the head and hat areas out, as the hat-less model would. */
    record Layer(Identifier texture, boolean skipHat) {}

    private void compose(int index, Identifier base, List<Layer> layers) {
        NativeImage atlas = texture.getPixels();
        int x0 = (index % COLUMNS) * SLOT;
        int y0 = (index / COLUMNS) * SLOT;
        List<Layer> all = new ArrayList<>(layers.size() + 1);
        all.add(new Layer(base, false));
        all.addAll(layers);
        for (int y = 0; y < SLOT; y++) for (int x = 0; x < SLOT; x++) atlas.setPixel(x0 + x, y0 + y, 0);
        for (Layer layer : all) {
            NativeImage image = image(layer.texture());
            if (image == null) continue;
            for (int y = 0; y < SLOT; y++) {
                for (int x = 0; x < SLOT; x++) {
                    if (layer.skipHat() && inHat(x, y)) continue;
                    // Resource packs may use bigger skins: sample them down to the slot.
                    int over = image.getPixel(x * image.getWidth() / SLOT, y * image.getHeight() / SLOT);
                    // Entity layers are drawn with alpha cutout and no blending: a texel past the cutout replaces
                    // what is under it, anything fainter is discarded.
                    if ((over >>> 24) < CUTOUT) continue;
                    atlas.setPixel(x0 + x, y0 + y, over | 0xFF000000);
                }
            }
        }
    }

    private static boolean inHat(int x, int y) {
        for (int[] a : HAT_AREAS) if (x >= a[0] && y >= a[1] && x < a[2] && y < a[3]) return true;
        return false;
    }

    private NativeImage image(Identifier id) {
        return images.computeIfAbsent(id, k -> {
            try (InputStream in = Minecraft.getInstance().getResourceManager().open(k)) {
                return Optional.of(NativeImage.read(in));
            } catch (IOException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }
}
