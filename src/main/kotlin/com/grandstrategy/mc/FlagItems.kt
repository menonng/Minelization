package com.grandstrategy.mc

import com.grandstrategy.sim.Flag
import com.grandstrategy.sim.FlagLayer
import com.grandstrategy.sim.Flags
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.BannerItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.entity.BannerPatternLayers

/** Converts between a nation's [Flag] and a real banner item. Works on both sides given the world's registries. */
object FlagItems {
    fun stack(flag: Flag?, fallbackColor: Int, registries: HolderLookup.Provider): ItemStack {
        val f = flag ?: Flag(fallbackColor)
        val stack = ItemStack(Items.BANNER.pick(DyeColor.byId(f.base.coerceIn(0, 15))))
        val patterns = registries.lookupOrThrow(Registries.BANNER_PATTERN)
        val builder = BannerPatternLayers.Builder()
        for (layer in f.layers.take(Flags.MAX_LAYERS)) {
            val id = Identifier.tryParse(if (':' in layer.pattern) layer.pattern else "minecraft:${layer.pattern}") ?: continue
            builder.addIfRegistered(patterns, ResourceKey.create(Registries.BANNER_PATTERN, id), DyeColor.byId(layer.color.coerceIn(0, 15)))
        }
        stack.set(DataComponents.BANNER_PATTERNS, builder.build())
        return stack
    }

    /** The flag drawn on a banner item, or null if the stack is not a banner. */
    fun fromStack(stack: ItemStack): Flag? {
        val banner = stack.item as? BannerItem ?: return null
        val layers = stack.get(DataComponents.BANNER_PATTERNS)?.layers().orEmpty().mapNotNull { layer ->
            layer.pattern().unwrapKey().map { key ->
                val id = key.identifier()
                FlagLayer(if (id.namespace == "minecraft") id.path else id.toString(), layer.color().id)
            }.orElse(null)
        }
        return Flag(banner.color.id, layers.take(Flags.MAX_LAYERS))
    }
}
