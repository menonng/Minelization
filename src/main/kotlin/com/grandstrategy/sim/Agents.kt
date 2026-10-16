package com.grandstrategy.sim

import kotlin.math.sqrt
import kotlin.random.Random

enum class Job { FARMER, LUMBERJACK, MINER, BUILDER, MERCHANT, SCHOLAR, SOLDIER, CHILD }

object AgentState {
    const val REST: Byte = 0
    const val TO_WORK: Byte = 1
    const val WORK: Byte = 2
    const val TO_HOME: Byte = 3
}

fun interface WorkPicker {
    fun pick(nation: Int, homeX: Float, homeZ: Float, rng: Random): Long
}

class Agents(initial: Int = 1024) {
    var capacity = initial
        private set
    var x = FloatArray(initial); private set
    var z = FloatArray(initial); private set
    var targetX = FloatArray(initial); private set
    var targetZ = FloatArray(initial); private set
    var homeX = FloatArray(initial); private set
    var homeZ = FloatArray(initial); private set
    var speed = FloatArray(initial); private set
    var timer = FloatArray(initial); private set
    var lastStep = LongArray(initial); private set
    var nation = IntArray(initial); private set
    var uid = IntArray(initial); private set
    var task = IntArray(initial); private set
    var job = ByteArray(initial); private set
    var state = ByteArray(initial); private set
    var alive = BooleanArray(initial); private set

    var count = 0
        private set
    var high = 0
        private set
    private var nextUid = 1
    private val free = ArrayDeque<Int>()
    private val removedListeners = mutableListOf<(Int) -> Unit>()

    fun onRemoved(listener: (Int) -> Unit) {
        removedListeners += listener
    }

    private fun grow() {
        val n = capacity * 2
        x = x.copyOf(n); z = z.copyOf(n)
        targetX = targetX.copyOf(n); targetZ = targetZ.copyOf(n)
        homeX = homeX.copyOf(n); homeZ = homeZ.copyOf(n)
        speed = speed.copyOf(n); timer = timer.copyOf(n); lastStep = lastStep.copyOf(n)
        nation = nation.copyOf(n); uid = uid.copyOf(n); task = task.copyOf(n)
        job = job.copyOf(n); state = state.copyOf(n); alive = alive.copyOf(n)
        capacity = n
    }

    fun add(owner: Int, hx: Float, hz: Float, work: Job, rng: Random, now: Long): Int {
        val i = free.removeFirstOrNull() ?: run {
            if (high == capacity) grow()
            high++
            high - 1
        }
        x[i] = hx; z[i] = hz
        homeX[i] = hx; homeZ[i] = hz
        targetX[i] = hx; targetZ[i] = hz
        speed[i] = 1.2f + rng.nextFloat() * 0.6f
        timer[i] = rng.nextFloat() * 20f
        lastStep[i] = now
        nation[i] = owner
        uid[i] = nextUid++
        task[i] = 0
        job[i] = work.ordinal.toByte()
        state[i] = AgentState.REST
        alive[i] = true
        count++
        return i
    }

    fun remove(i: Int) {
        if (!alive[i]) return
        alive[i] = false
        count--
        free.addLast(i)
        removedListeners.forEach { it(i) }
    }

    fun rehome(i: Int, owner: Int, hx: Float, hz: Float) {
        nation[i] = owner
        homeX[i] = hx
        homeZ[i] = hz
        if (state[i] == AgentState.TO_HOME || state[i] == AgentState.REST) {
            targetX[i] = hx
            targetZ[i] = hz
            state[i] = AgentState.TO_HOME
        }
    }

    fun assignTask(i: Int, order: Int, siteX: Float, siteZ: Float) {
        task[i] = order
        targetX[i] = siteX
        targetZ[i] = siteZ
        state[i] = AgentState.TO_WORK
    }

    fun releaseTask(i: Int) {
        if (task[i] == 0) return
        task[i] = 0
        targetX[i] = homeX[i]
        targetZ[i] = homeZ[i]
        state[i] = AgentState.TO_HOME
    }

    fun step(i: Int, now: Long, picker: WorkPicker, rng: Random) {
        if (!alive[i]) return
        val dt = ((now - lastStep[i]) / 20f).coerceIn(0f, 5f)
        lastStep[i] = now
        if (dt <= 0f) return
        when (state[i]) {
            AgentState.REST, AgentState.WORK -> {
                timer[i] -= dt
                if (timer[i] <= 0f) {
                    if (state[i] == AgentState.REST) {
                        val site = picker.pick(nation[i], homeX[i], homeZ[i], rng)
                        targetX[i] = Chunks.x(site) * 16f + rng.nextFloat() * 16f
                        targetZ[i] = Chunks.z(site) * 16f + rng.nextFloat() * 16f
                        state[i] = AgentState.TO_WORK
                    } else {
                        targetX[i] = homeX[i] + rng.nextFloat() * 4f - 2f
                        targetZ[i] = homeZ[i] + rng.nextFloat() * 4f - 2f
                        state[i] = AgentState.TO_HOME
                    }
                }
            }

            else -> {
                val dx = targetX[i] - x[i]
                val dz = targetZ[i] - z[i]
                val dist = sqrt(dx * dx + dz * dz)
                val move = speed[i] * dt
                if (dist <= move) {
                    x[i] = targetX[i]
                    z[i] = targetZ[i]
                    if (state[i] == AgentState.TO_WORK) {
                        state[i] = AgentState.WORK
                        timer[i] = if (task[i] != 0) 1e9f else 15f + rng.nextFloat() * 30f
                    } else {
                        state[i] = AgentState.REST
                        timer[i] = 10f + rng.nextFloat() * 25f
                    }
                } else {
                    x[i] += dx / dist * move
                    z[i] += dz / dist * move
                }
            }
        }
    }

    fun moving(i: Int): Boolean = state[i] == AgentState.TO_WORK || state[i] == AgentState.TO_HOME

    fun chunkOf(i: Int): Long = Chunks.pack(kotlin.math.floor(x[i] / 16f).toInt(), kotlin.math.floor(z[i] / 16f).toInt())

    fun homeChunk(i: Int): Long = Chunks.pack(kotlin.math.floor(homeX[i] / 16f).toInt(), kotlin.math.floor(homeZ[i] / 16f).toInt())

    inline fun forEachAlive(action: (Int) -> Unit) {
        for (i in 0 until high) if (alive[i]) action(i)
    }
}

class AgentPopulation(val sim: Simulation) {
    val agents = Agents()
    var benchmarkTotal: Int? = null
    private var tick = 0L
    private val picker = WorkPicker { owner, hx, hz, rng -> pickWork(owner, hx, hz, rng) }
    private val claimLists = HashMap<Int, LongArray>()

    val tuning: AgentTuning get() = Balance.config.agents

    fun desired(): Map<Int, Int> {
        val nations = sim.state.alive()
        val bench = benchmarkTotal
        if (bench != null) {
            val claims = nations.sumOf { it.claims.size }.coerceAtLeast(1)
            return nations.associate { it.id to (bench.toLong() * it.claims.size / claims).toInt() }
        }
        val raw = nations.associate { it.id to (it.population * tuning.perPopulation).toInt() }
        val total = raw.values.sum()
        if (total <= tuning.max) return raw
        val scale = tuning.max.toDouble() / total
        return raw.mapValues { (it.value * scale).toInt() }
    }

    fun sync() {
        claimLists.clear()
        for (n in sim.state.alive()) claimLists[n.id] = n.claims.toLongArray()
        val rng = sim.rng
        val counts = HashMap<Int, Int>()
        agents.forEachAlive { i ->
            val home = agents.homeChunk(i)
            val current = sim.state.nation(agents.nation[i])
            val owner = sim.ownerOf(home)
            when {
                current != null && owner?.id == current.id -> {}
                owner != null -> agents.rehome(i, owner.id, agents.homeX[i], agents.homeZ[i])
                current != null && current.claims.isNotEmpty() -> {
                    val (hx, hz) = randomHome(current.id, rng) ?: return@forEachAlive
                    agents.rehome(i, current.id, hx, hz)
                }
                else -> {
                    agents.remove(i)
                    return@forEachAlive
                }
            }
            counts.merge(agents.nation[i], 1, Int::plus)
        }
        val want = desired()
        val deficits = want.mapValues { (id, target) -> (target - (counts[id] ?: 0)).coerceAtLeast(0) }
        val totalDeficit = deficits.values.sum().coerceAtLeast(1)
        val budget = tuning.spawnPerTurn
        for ((id, target) in want) {
            var have = counts[id] ?: 0
            val deficit = deficits[id] ?: 0
            var share = if (deficit == 0) 0 else maxOf(minOf(deficit, 20), (budget.toLong() * deficit / totalDeficit).toInt())
            while (have < target && share > 0) {
                val (hx, hz) = randomHome(id, rng) ?: break
                agents.add(id, hx, hz, pickJob(rng), rng, tick)
                have++
                share--
            }
            if (have > target) {
                var excess = have - target
                agents.forEachAlive { i ->
                    if (excess > 0 && agents.nation[i] == id) {
                        agents.remove(i)
                        excess--
                    }
                }
            }
        }
    }

    fun tick(hot: Iterable<Int>) {
        tick++
        val bucket = (tick % FAR_INTERVAL).toInt()
        val rng = sim.rng
        val high = agents.high
        var i = bucket
        while (i < high) {
            agents.step(i, tick, picker, rng)
            i += FAR_INTERVAL
        }
        if (tick % NEAR_INTERVAL == 0L) {
            for (j in hot) if (tick - agents.lastStep[j] >= NEAR_INTERVAL) agents.step(j, tick, picker, rng)
        }
    }

    fun now(): Long = tick

    private fun randomHome(id: Int, rng: kotlin.random.Random): Pair<Float, Float>? {
        val claims = claimLists[id] ?: sim.state.nation(id)?.claims?.toLongArray()?.also { claimLists[id] = it } ?: return null
        if (claims.isEmpty()) return null
        val nation = sim.state.nation(id) ?: return null
        val chunk = if (rng.nextInt(3) == 0) nation.capital else claims[rng.nextInt(claims.size)]
        return (Chunks.x(chunk) * 16f + 2f + rng.nextFloat() * 12f) to (Chunks.z(chunk) * 16f + 2f + rng.nextFloat() * 12f)
    }

    private fun pickWork(owner: Int, hx: Float, hz: Float, rng: kotlin.random.Random): Long {
        val home = Chunks.pack(kotlin.math.floor(hx / 16f).toInt(), kotlin.math.floor(hz / 16f).toInt())
        repeat(6) {
            val c = Chunks.pack(Chunks.x(home) + rng.nextInt(-3, 4), Chunks.z(home) + rng.nextInt(-3, 4))
            if (sim.state.owners[c] == owner) return c
        }
        return home
    }

    private fun pickJob(rng: kotlin.random.Random): Job {
        val r = rng.nextInt(100)
        return when {
            r < 30 -> Job.FARMER
            r < 45 -> Job.LUMBERJACK
            r < 57 -> Job.MINER
            r < 65 -> Job.BUILDER
            r < 73 -> Job.MERCHANT
            r < 78 -> Job.SCHOLAR
            r < 88 -> Job.SOLDIER
            else -> Job.CHILD
        }
    }

    companion object {
        const val FAR_INTERVAL = 20
        const val NEAR_INTERVAL = 2L
    }
}
