package com.grandstrategy.sim

import kotlin.math.sqrt
import kotlin.random.Random

enum class Job { FARMER, LUMBERJACK, MINER, BUILDER, MERCHANT, SCHOLAR, SOLDIER, CHILD }

object AgentState {
    const val REST: Byte = 0
    const val TO_WORK: Byte = 1
    const val WORK: Byte = 2
    const val TO_HOME: Byte = 3
    const val SLEEP: Byte = 4
    const val TO_SOCIAL: Byte = 5
    const val SOCIAL: Byte = 6

    fun moving(state: Byte) = state == TO_WORK || state == TO_HOME || state == TO_SOCIAL
}

enum class DayPhase { DAWN, WORK, SOCIAL, NIGHT;
    companion object {
        fun of(dayTime: Long, raining: Boolean): DayPhase {
            val t = ((dayTime % 24000) + 24000) % 24000
            return when {
                t >= 12500 && t < 23300 -> NIGHT
                raining -> NIGHT
                t >= 23300 || t < 1000 -> DAWN
                t < 10500 -> WORK
                else -> SOCIAL
            }
        }
    }
}

data class Home(val x: Float, val z: Float, val indoor: Boolean)

interface AgentPlanner {
    fun home(nation: Int, rng: Random): Home? = null
    fun workSite(agents: Agents, i: Int, rng: Random): Pair<Float, Float>? = null
    fun meeting(nation: Int): Pair<Float, Float>? = null
    /** A public place to spend social time (one of several), chosen per villager so a town doesn't crowd one square. */
    fun meeting(nation: Int, rng: Random): Pair<Float, Float>? = meeting(nation)
    fun visibleAt(x: Float, z: Float): Boolean = false
    fun shown(i: Int): Boolean = false
    /** Whether a citizen of [nation] may stand at (x, z): its land plus one chunk around it. */
    fun allowed(nation: Int, x: Float, z: Float): Boolean = true
}

class Agents(initial: Int = 1024) {
    var capacity = initial
        private set
    var x = FloatArray(initial); private set
    var y = FloatArray(initial); private set
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
    var phase = ByteArray(initial); private set
    var alive = BooleanArray(initial); private set
    var guided = BooleanArray(initial); private set
    var indoor = BooleanArray(initial); private set

    var count = 0
        private set
    var high = 0
        private set
    private var nextUid = 1
    private val free = ArrayDeque<Int>()
    private val removedListeners = mutableListOf<(Int) -> Unit>()
    private val targetListeners = mutableListOf<(Int) -> Unit>()

    fun onRemoved(listener: (Int) -> Unit) {
        removedListeners += listener
    }

    fun onTargetChanged(listener: (Int) -> Unit) {
        targetListeners += listener
    }

    private fun grow() {
        val n = capacity * 2
        x = x.copyOf(n); y = y.copyOf(n); z = z.copyOf(n)
        targetX = targetX.copyOf(n); targetZ = targetZ.copyOf(n)
        homeX = homeX.copyOf(n); homeZ = homeZ.copyOf(n)
        speed = speed.copyOf(n); timer = timer.copyOf(n); lastStep = lastStep.copyOf(n)
        nation = nation.copyOf(n); uid = uid.copyOf(n); task = task.copyOf(n)
        job = job.copyOf(n); state = state.copyOf(n); phase = phase.copyOf(n); alive = alive.copyOf(n)
        guided = guided.copyOf(n); indoor = indoor.copyOf(n)
        capacity = n
    }

    fun add(owner: Int, home: Home, startX: Float, startZ: Float, work: Job, rng: Random, now: Long): Int {
        val i = free.removeFirstOrNull() ?: run {
            if (high == capacity) grow()
            high++
            high - 1
        }
        x[i] = startX; z[i] = startZ; y[i] = Float.NaN
        homeX[i] = home.x; homeZ[i] = home.z; indoor[i] = home.indoor
        speed[i] = 1.15f + rng.nextFloat() * 0.35f
        timer[i] = 0f
        lastStep[i] = now
        nation[i] = owner
        uid[i] = nextUid++
        task[i] = 0
        job[i] = work.ordinal.toByte()
        phase[i] = -1
        guided[i] = false
        alive[i] = true
        count++
        if (startX == home.x && startZ == home.z) {
            state[i] = AgentState.REST
            timer[i] = rng.nextFloat() * 10f
        } else {
            go(i, AgentState.TO_HOME, home.x, home.z)
        }
        return i
    }

    fun remove(i: Int) {
        if (!alive[i]) return
        alive[i] = false
        guided[i] = false
        count--
        free.addLast(i)
        removedListeners.forEach { it(i) }
    }

    fun go(i: Int, moving: Byte, tx: Float, tz: Float) {
        state[i] = moving
        targetX[i] = tx
        targetZ[i] = tz
        targetListeners.forEach { it(i) }
    }

    fun rehome(i: Int, owner: Int, home: Home) {
        nation[i] = owner
        homeX[i] = home.x
        homeZ[i] = home.z
        indoor[i] = home.indoor
        if (state[i] == AgentState.TO_HOME || state[i] == AgentState.SLEEP) go(i, AgentState.TO_HOME, home.x, home.z)
    }

    fun assignTask(i: Int, order: Int, siteX: Float, siteZ: Float) {
        task[i] = order
        go(i, AgentState.TO_WORK, siteX, siteZ)
    }

    fun releaseTask(i: Int) {
        if (task[i] == 0) return
        task[i] = 0
        state[i] = AgentState.REST
        timer[i] = 2f
    }

    fun sleeping(i: Int) = state[i] == AgentState.SLEEP

    fun moving(i: Int): Boolean = AgentState.moving(state[i])

    fun arrive(i: Int, rng: Random) {
        when (state[i]) {
            AgentState.TO_WORK -> {
                state[i] = AgentState.WORK
                timer[i] = if (task[i] != 0) 1e9f else 12f + rng.nextFloat() * 24f
            }
            AgentState.TO_SOCIAL -> {
                state[i] = AgentState.SOCIAL
                timer[i] = 8f + rng.nextFloat() * 16f
            }
            AgentState.TO_HOME -> {
                if (phase[i] == DayPhase.NIGHT.ordinal.toByte() && indoor[i]) {
                    state[i] = AgentState.SLEEP
                    timer[i] = 1e9f
                } else {
                    state[i] = AgentState.REST
                    timer[i] = 4f + rng.nextFloat() * 10f
                }
            }
        }
    }

    fun step(i: Int, now: Long, dayPhase: DayPhase, planner: AgentPlanner, rng: Random) {
        if (!alive[i]) return
        val dt = ((now - lastStep[i]) / 20f).coerceIn(0f, 5f)
        lastStep[i] = now
        if (phase[i] != dayPhase.ordinal.toByte()) {
            phase[i] = dayPhase.ordinal.toByte()
            if (task[i] == 0) decide(i, dayPhase, planner, rng, phaseChanged = true)
        }
        if (dt <= 0f) return
        when (state[i]) {
            AgentState.REST, AgentState.WORK, AgentState.SOCIAL, AgentState.SLEEP -> {
                timer[i] -= dt
                if (timer[i] <= 0f) decide(i, dayPhase, planner, rng, phaseChanged = false)
            }
            else -> if (!guided[i]) {
                val dx = targetX[i] - x[i]
                val dz = targetZ[i] - z[i]
                val dist = sqrt(dx * dx + dz * dz)
                val move = speed[i] * dt
                val nx = if (dist <= move) targetX[i] else x[i] + dx / dist * move
                val nz = if (dist <= move) targetZ[i] else z[i] + dz / dist * move
                val n = nation[i]
                if (planner.allowed(n, x[i], z[i]) && !planner.allowed(n, nx, nz)) {
                    // The route leaves the nation's land: slide along the border, or stop at it.
                    when {
                        planner.allowed(n, nx, z[i]) -> x[i] = nx
                        planner.allowed(n, x[i], nz) -> z[i] = nz
                        else -> arrive(i, rng)
                    }
                } else if (dist <= move) {
                    x[i] = targetX[i]
                    z[i] = targetZ[i]
                    arrive(i, rng)
                } else {
                    x[i] = nx
                    z[i] = nz
                }
            }
        }
    }

    private fun decide(i: Int, dayPhase: DayPhase, planner: AgentPlanner, rng: Random, phaseChanged: Boolean) {
        if (task[i] != 0) return
        val job = Job.entries[job[i].toInt()]
        when (dayPhase) {
            DayPhase.NIGHT -> {
                if (state[i] == AgentState.SLEEP) {
                    timer[i] = 1e9f
                    return
                }
                if (state[i] == AgentState.TO_HOME && !phaseChanged) return
                go(i, AgentState.TO_HOME, homeX[i], homeZ[i])
            }
            DayPhase.DAWN -> {
                if (state[i] == AgentState.SLEEP || phaseChanged) {
                    state[i] = AgentState.REST
                    timer[i] = 2f + rng.nextFloat() * 20f
                    return
                }
                wander(i, homeX[i], homeZ[i], 4f, AgentState.TO_HOME, rng)
            }
            DayPhase.WORK -> {
                if (state[i] == AgentState.SLEEP) {
                    state[i] = AgentState.REST
                    timer[i] = rng.nextFloat() * 6f
                    return
                }
                if (job == Job.CHILD) {
                    val (mx, mz) = planner.meeting(nation[i]) ?: (homeX[i] to homeZ[i])
                    wander(i, mx, mz, 10f, AgentState.TO_SOCIAL, rng)
                    return
                }
                val site = planner.workSite(this, i, rng)
                if (site != null) go(i, AgentState.TO_WORK, site.first, site.second)
                else wander(i, homeX[i], homeZ[i], 12f, AgentState.TO_WORK, rng)
            }
            DayPhase.SOCIAL -> {
                if (state[i] == AgentState.SLEEP) {
                    state[i] = AgentState.REST
                    timer[i] = 1f
                    return
                }
                // A third head for a public place; the rest visit neighbours near home.
                val public = if (rng.nextFloat() < 0.35f) planner.meeting(nation[i], rng) else null
                if (public != null) wander(i, public.first, public.second, 10f, AgentState.TO_SOCIAL, rng)
                else wander(i, homeX[i], homeZ[i], 9f, AgentState.TO_SOCIAL, rng)
            }
        }
    }

    private fun wander(i: Int, cx: Float, cz: Float, radius: Float, moving: Byte, rng: Random) {
        go(i, moving, cx + (rng.nextFloat() * 2 - 1) * radius, cz + (rng.nextFloat() * 2 - 1) * radius)
    }

    fun chunkOf(i: Int): Long = Chunks.pack(kotlin.math.floor(x[i] / 16f).toInt(), kotlin.math.floor(z[i] / 16f).toInt())

    fun homeChunk(i: Int): Long = Chunks.pack(kotlin.math.floor(homeX[i] / 16f).toInt(), kotlin.math.floor(homeZ[i] / 16f).toInt())

    inline fun forEachAlive(action: (Int) -> Unit) {
        for (i in 0 until high) if (alive[i]) action(i)
    }
}

class AgentPopulation(val sim: Simulation) {
    val agents = Agents()
    var benchmarkTotal: Int? = null
    var planner: AgentPlanner = DefaultPlanner()
    var dayTime = 6000L
    var raining = false
    private var tick = 0L
    private val claimLists = HashMap<Int, LongArray>()

    val tuning: AgentTuning get() = Balance.config.agents

    fun phase(): DayPhase = DayPhase.of(dayTime, raining)

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
                owner != null -> agents.rehome(i, owner.id, Home(agents.homeX[i], agents.homeZ[i], agents.indoor[i]))
                current != null && current.claims.isNotEmpty() -> {
                    val h = home(current.id, rng) ?: return@forEachAlive
                    agents.rehome(i, current.id, h)
                }
                else -> {
                    if (!planner.shown(i)) {
                        agents.remove(i)
                        return@forEachAlive
                    }
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
                val h = home(id, rng) ?: break
                val (sx, sz) = hiddenStart(id, h, rng)
                agents.add(id, h, sx, sz, pickJob(rng), rng, tick)
                have++
                share--
            }
            if (have > target) {
                var excess = have - target
                agents.forEachAlive { i ->
                    if (excess > 0 && agents.nation[i] == id && agents.task[i] == 0 && !planner.shown(i)) {
                        agents.remove(i)
                        excess--
                    }
                }
            }
        }
    }

    private fun hiddenStart(id: Int, home: Home, rng: Random): Pair<Float, Float> {
        if (!planner.visibleAt(home.x, home.z)) return home.x to home.z
        val claims = claimLists[id] ?: return home.x to home.z
        repeat(12) {
            val c = claims[rng.nextInt(claims.size)]
            val x = Chunks.x(c) * 16f + 8f
            val z = Chunks.z(c) * 16f + 8f
            if (!planner.visibleAt(x, z)) return x to z
        }
        return home.x to home.z
    }

    fun tick(hot: Iterable<Int>, warm: Iterable<Int> = emptyList()) {
        tick++
        val bucket = (tick % FAR_INTERVAL).toInt()
        val rng = sim.rng
        val dayPhase = phase()
        val high = agents.high
        var i = bucket
        while (i < high) {
            agents.step(i, tick, dayPhase, planner, rng)
            i += FAR_INTERVAL
        }
        if (tick % NEAR_INTERVAL == 0L) {
            for (j in hot) if (tick - agents.lastStep[j] >= NEAR_INTERVAL) agents.step(j, tick, dayPhase, planner, rng)
        }
        // Agents on someone's screen but not walked by A*: step often enough that their motion looks continuous.
        for (j in warm) if (tick - agents.lastStep[j] >= WARM_INTERVAL) agents.step(j, tick, dayPhase, planner, rng)
    }

    fun now(): Long = tick

    fun home(id: Int, rng: Random): Home? {
        planner.home(id, rng)?.let { return it }
        val claims = claimLists[id] ?: sim.state.nation(id)?.claims?.toLongArray()?.also { claimLists[id] = it } ?: return null
        if (claims.isEmpty()) return null
        val nation = sim.state.nation(id) ?: return null
        val chunk = if (rng.nextInt(10) == 0) nation.capital else claims[rng.nextInt(claims.size)]
        return Home(Chunks.x(chunk) * 16f + 2f + rng.nextFloat() * 12f, Chunks.z(chunk) * 16f + 2f + rng.nextFloat() * 12f, false)
    }

    private fun pickJob(rng: Random): Job {
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

    inner class DefaultPlanner : AgentPlanner {
        override fun workSite(agents: Agents, i: Int, rng: Random): Pair<Float, Float>? {
            val owner = agents.nation[i]
            val home = agents.homeChunk(i)
            repeat(6) {
                val c = Chunks.pack(Chunks.x(home) + rng.nextInt(-3, 4), Chunks.z(home) + rng.nextInt(-3, 4))
                if (sim.state.owners[c] == owner) return (Chunks.x(c) * 16f + rng.nextFloat() * 16f) to (Chunks.z(c) * 16f + rng.nextFloat() * 16f)
            }
            return null
        }

        override fun meeting(nation: Int): Pair<Float, Float>? =
            sim.state.nation(nation)?.capital?.let { (Chunks.x(it) * 16f + 8f) to (Chunks.z(it) * 16f + 8f) }
    }

    companion object {
        const val FAR_INTERVAL = 20
        const val NEAR_INTERVAL = 2L
        const val WARM_INTERVAL = 4L
    }
}
