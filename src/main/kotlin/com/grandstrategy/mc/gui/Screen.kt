package com.grandstrategy.mc.gui

import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.TooltipDisplay
import java.util.LinkedHashSet

enum class Click { LEFT, RIGHT, SHIFT }

abstract class Screen(val player: ServerPlayer, private val rows: Int) {
    private val container = SimpleContainer(rows * 9)
    private val handlers = HashMap<Int, (Click) -> Unit>()
    private var menu: Menu? = null

    abstract fun title(): Component
    abstract fun build()

    fun open() {
        render()
        player.openMenu(SimpleMenuProvider({ id, inventory, _ -> Menu(id, inventory).also { menu = it } }, title()))
    }

    fun refresh() {
        render()
        menu?.broadcastChanges()
    }

    private fun render() {
        container.clearContent()
        handlers.clear()
        build()
        for (i in 0 until rows * 9) if (container.getItem(i).isEmpty) container.setItem(i, filler())
    }

    protected fun set(slot: Int, stack: ItemStack, handler: ((Click) -> Unit)? = null) {
        if (slot !in 0 until rows * 9) return
        container.setItem(slot, stack)
        if (handler != null) handlers[slot] = handler
    }

    protected fun navigate(next: Screen) {
        next.open()
    }

    private inner class Menu(id: Int, inventory: Inventory) :
        ChestMenu(menuType(rows), id, inventory, container, rows) {

        override fun clicked(slot: Int, button: Int, input: ContainerInput, clicker: Player) {
            val handler = if (slot in 0 until rows * 9) handlers[slot] else null
            carried = ItemStack.EMPTY
            if (handler != null && (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE)) {
                val click = when {
                    input == ContainerInput.QUICK_MOVE -> Click.SHIFT
                    button == 1 -> Click.RIGHT
                    else -> Click.LEFT
                }
                handler(click)
            }
            sendAllDataToRemote()
        }

        override fun quickMoveStack(player: Player, slot: Int): ItemStack = ItemStack.EMPTY

        override fun stillValid(player: Player): Boolean = true
    }

    companion object {
        private val HIDDEN = LinkedHashSet(listOf(DataComponents.ATTRIBUTE_MODIFIERS, DataComponents.ENCHANTMENTS))

        fun menuType(rows: Int): MenuType<ChestMenu> = when (rows) {
            1 -> MenuType.GENERIC_9x1
            2 -> MenuType.GENERIC_9x2
            3 -> MenuType.GENERIC_9x3
            4 -> MenuType.GENERIC_9x4
            5 -> MenuType.GENERIC_9x5
            else -> MenuType.GENERIC_9x6
        }

        fun icon(item: Item, name: Component, lore: List<Component> = emptyList(), glint: Boolean = false, count: Int = 1): ItemStack {
            val stack = ItemStack(item, count.coerceIn(1, 99))
            stack.set(DataComponents.CUSTOM_NAME, Component.empty().append(name).withStyle { it.withItalic(false) })
            if (lore.isNotEmpty()) {
                stack.set(DataComponents.LORE, ItemLore(lore.map { line -> Component.empty().append(line).withStyle { it.withItalic(false) } }))
            }
            stack.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay(false, HIDDEN))
            if (glint) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
            return stack
        }

        fun line(text: String, color: ChatFormatting = ChatFormatting.GRAY): Component = Component.literal(text).withStyle(color)

        fun filler(): ItemStack = icon(Items.STAINED_GLASS_PANE.gray(), Component.literal(" "))
    }
}
