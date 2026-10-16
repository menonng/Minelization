package com.grandstrategy.mc

import com.grandstrategy.sim.EventKind
import com.grandstrategy.sim.GameEvent
import com.grandstrategy.sim.Nation
import com.grandstrategy.sim.Outcome
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.item.DyeColor

object Texts {
    fun dye(nation: Nation): DyeColor = DyeColor.byId(nation.color)

    fun nation(nation: Nation): MutableComponent = Component.literal(nation.name).withColor(dye(nation).textColor)

    fun prefix(): MutableComponent = Component.literal("[국가] ").withStyle(ChatFormatting.GOLD)

    fun plain(text: String, color: ChatFormatting = ChatFormatting.GRAY): MutableComponent =
        Component.literal(text).withStyle(color)

    fun outcome(outcome: Outcome): Component =
        prefix().append(plain(outcome.message, if (outcome.ok) ChatFormatting.GREEN else ChatFormatting.RED))

    fun event(event: GameEvent): Component {
        val color = when (event.kind) {
            EventKind.MAJOR -> ChatFormatting.YELLOW
            EventKind.WAR -> ChatFormatting.RED
            EventKind.DIPLOMACY -> ChatFormatting.AQUA
            EventKind.ECONOMY -> ChatFormatting.GREEN
            EventKind.INFO -> ChatFormatting.GRAY
        }
        return prefix().append(plain("${event.turn}턴 ", ChatFormatting.DARK_GRAY)).append(plain(event.text, color))
    }

    fun button(label: String, command: String, hover: String, color: ChatFormatting): MutableComponent =
        Component.literal("[$label]").withStyle { style ->
            style.withColor(color)
                .withClickEvent(ClickEvent.RunCommand(command))
                .withHoverEvent(HoverEvent.ShowText(Component.literal(hover)))
        }

    fun number(value: Double): String = when {
        value >= 1000 -> "%.1fk".format(value / 1000)
        value >= 100 -> "%.0f".format(value)
        else -> "%.1f".format(value)
    }

    fun signed(value: Double): String = (if (value >= 0) "+" else "") + number(value)
}
