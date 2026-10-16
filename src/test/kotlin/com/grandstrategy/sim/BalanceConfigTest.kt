package com.grandstrategy.sim

import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BalanceConfigTest {
    private val json = Json { encodeDefaults = true; allowStructuredMapKeys = true; ignoreUnknownKeys = true }

    @AfterTest
    fun reset() {
        Balance.config = BalanceConfig()
    }

    @Test
    fun defaultsRoundTripThroughJson() {
        val text = json.encodeToString(BalanceConfig.serializer(), BalanceConfig())
        assertEquals(BalanceConfig(), json.decodeFromString(BalanceConfig.serializer(), text))
        assertTrue(BalanceConfig().problems().isEmpty())
    }

    @Test
    fun partialFileKeepsOtherDefaults() {
        val config = json.decodeFromString(BalanceConfig.serializer(), """{"growthRate": 0.1, "ai": {"strategicThreshold": 0.2}}""")
        assertEquals(0.1, config.growthRate)
        assertEquals(0.2, config.ai.strategicThreshold)
        assertEquals(BalanceConfig().foodPerPop, config.foodPerPop)
        assertEquals(BalanceConfig().ai.economicThreshold, config.ai.economicThreshold)
    }

    @Test
    fun changedValuesTakeEffectImmediately() {
        val nation = Nation(id = 1, name = "t", color = 0, capital = 0, personality = Personality(0.5, 0.5, 0.5))
        val before = Balance.claimCost(nation)[Resource.WOOD]!!
        Balance.config = BalanceConfig(claimCost = mapOf(Resource.WOOD to 60.0))
        assertEquals(before * 10, Balance.claimCost(nation)[Resource.WOOD]!!, 1e-9)
        Balance.config = BalanceConfig(terrain = mapOf(Terrain.PLAINS to Yields(food = 9.0)))
        assertEquals(9.0, Terrain.PLAINS.yield(Resource.FOOD))
    }

    @Test
    fun invalidValuesAreReported() {
        assertTrue(BalanceConfig(workersPerChunk = 0.0).problems().isNotEmpty())
        assertTrue(BalanceConfig(claimCost = mapOf(Resource.GOLD to -1.0)).problems().isNotEmpty())
    }
}
