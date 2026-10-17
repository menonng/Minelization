package com.grandstrategy.mc

import com.grandstrategy.GrandStrategy
import com.grandstrategy.mc.gui.LogScreen
import com.grandstrategy.mc.gui.NationScreen
import com.grandstrategy.sim.Action
import com.grandstrategy.sim.Actions
import com.grandstrategy.sim.Balance
import com.grandstrategy.sim.Chunks
import com.grandstrategy.sim.EventKind
import com.grandstrategy.sim.Facility
import com.grandstrategy.sim.Nation
import com.grandstrategy.sim.Outcome
import com.grandstrategy.sim.Policy
import com.grandstrategy.sim.Resource
import com.grandstrategy.sim.Settlements
import com.grandstrategy.sim.TechBranch
import com.grandstrategy.sim.Treaty
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import com.grandstrategy.net.CutsceneKind
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object Deposit {
    fun value(stack: ItemStack): Pair<Resource, Double>? {
        val item = stack.item
        val holder = stack.typeHolder()
        return when {
            item == Items.WHEAT || item == Items.POTATO || item == Items.CARROT || item == Items.BEETROOT || item == Items.APPLE -> Resource.FOOD to 1.0
            item == Items.BREAD || item == Items.BAKED_POTATO -> Resource.FOOD to 3.0
            item == Items.COOKED_BEEF || item == Items.COOKED_PORKCHOP || item == Items.COOKED_MUTTON ||
                item == Items.COOKED_CHICKEN || item == Items.COOKED_COD || item == Items.COOKED_SALMON -> Resource.FOOD to 3.0
            item == Items.HAY_BLOCK -> Resource.FOOD to 9.0
            holder.`is`(ItemTags.LOGS) -> Resource.WOOD to 4.0
            holder.`is`(ItemTags.PLANKS) -> Resource.WOOD to 1.0
            item == Items.COBBLESTONE || item == Items.STONE || item == Items.COBBLED_DEEPSLATE || item == Items.DEEPSLATE ||
                item == Items.STONE_BRICKS || item == Items.GRANITE || item == Items.DIORITE || item == Items.ANDESITE -> Resource.STONE to 1.0
            item == Items.IRON_INGOT || item == Items.RAW_IRON -> Resource.IRON to 1.0
            item == Items.IRON_BLOCK -> Resource.IRON to 9.0
            item == Items.GOLD_INGOT || item == Items.RAW_GOLD -> Resource.GOLD to 1.0
            item == Items.GOLD_BLOCK -> Resource.GOLD to 9.0
            item == Items.EMERALD -> Resource.GOLD to 2.0
            else -> null
        }
    }

    fun fromHand(rt: Runtime, player: ServerPlayer, nation: Nation): Outcome {
        val stack = player.getItemInHand(InteractionHand.MAIN_HAND)
        if (stack.isEmpty) return Outcome.fail("손에 아무것도 들고 있지 않다.")
        val (resource, each) = value(stack) ?: return Outcome.fail("국고가 받지 않는 물건이다.")
        val count = stack.count
        val amount = each * count
        stack.shrink(count)
        Actions.deposit(rt.sim, nation, resource, amount, player.gameProfile.name())
        return Outcome.ok("${resource.label} ${Texts.number(amount)}을(를) ${nation.name} 국고에 납부했다.")
    }
}

object Found {
    fun found(rt: Runtime, player: ServerPlayer, name: String): Outcome {
        val uuid = player.uuid.toString()
        if (rt.state.rulerNation(uuid) != null) return Outcome.fail("이미 나라를 다스리고 있다. 먼저 /gs leave")
        if (player.level() != rt.server.overworld()) return Outcome.fail("오버월드에서만 나라를 세울 수 있다.")
        if (rt.state.byName(name) != null) return Outcome.fail("같은 이름의 나라가 있다.")
        val here = player.chunkPosition().pack()
        rt.sim.foundingError(here)?.let { return Outcome.fail(it) }
        rt.state.alive().forEach { it.citizens.remove(uuid) }
        val nation = rt.sim.createNation(name, here, ruler = uuid)
        nation.rulerName = player.gameProfile.name()
        return Outcome.ok("${nation.name}을(를) 세웠다. /gs 로 나라를 운영한다.")
    }
}

object GsCommands {
    private val NATIONS = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(GrandStrategy.runtime?.state?.alive()?.map { it.name } ?: emptyList(), builder)
    }

    private fun rt(): Runtime = GrandStrategy.runtime ?: error("Grand Strategy is not running")

    private fun reply(ctx: CommandContext<CommandSourceStack>, outcome: Outcome): Int {
        ctx.source.sendSystemMessage(Texts.outcome(outcome))
        return if (outcome.ok) 1 else 0
    }

    private fun reply(ctx: CommandContext<CommandSourceStack>, text: Component): Int {
        ctx.source.sendSystemMessage(text)
        return 1
    }

    private fun ruled(ctx: CommandContext<CommandSourceStack>, block: (ServerPlayer, Nation) -> Outcome): Int {
        val player = ctx.source.playerOrException
        val nation = rt().ruledNation(player) ?: return reply(ctx, Outcome.fail("다스리는 나라가 없다. /gs found 또는 /gs pick"))
        return reply(ctx, block(player, nation))
    }

    private fun nationArg(ctx: CommandContext<CommandSourceStack>): Nation? =
        rt().state.byName(StringArgumentType.getString(ctx, "nation"))

    private inline fun <reified E : Enum<E>> enumLiterals(name: String, crossinline build: (E) -> LiteralArgumentBuilder<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> {
        val root = literal(name)
        for (value in enumValues<E>()) root.then(build(value))
        return root
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val gs = literal("gs")
            .executes { ctx ->
                val player = ctx.source.playerOrException
                if (!rt().menus.openFor(player)) NationScreen(player, rt()).open()
                1
            }
            .then(literal("chest").executes { ctx -> NationScreen(ctx.source.playerOrException, rt()).open(); 1 })
            .then(literal("menu").then(argument("page", StringArgumentType.word())
                .suggests { _, b -> SharedSuggestionProvider.suggest(listOf("overview", "map", "diplomacy", "research", "log"), b) }
                .executes { ctx ->
                    val player = ctx.source.playerOrException
                    if (!rt().menus.openFor(player, StringArgumentType.getString(ctx, "page"))) NationScreen(player, rt()).open()
                    1
                }))
            .then(literal("list").executes(::list))
            .then(literal("info").executes { info(it, null) }
                .then(argument("nation", StringArgumentType.greedyString()).suggests(NATIONS).executes { info(it, nationArg(it)) }))
            .then(literal("here").executes(::here))
            .then(literal("log").executes { ctx -> LogScreen(ctx.source.playerOrException, rt(), null).open(); 1 })
            .then(literal("observe").executes { observe(it, false) }.then(literal("all").executes { observe(it, true) }))
            .then(literal("found").then(argument("name", StringArgumentType.greedyString()).executes { ctx ->
                reply(ctx, Found.found(rt(), ctx.source.playerOrException, StringArgumentType.getString(ctx, "name").take(32)))
            }))
            .then(literal("pick").then(argument("nation", StringArgumentType.greedyString()).suggests(NATIONS).executes(::pick)))
            .then(literal("join").then(argument("nation", StringArgumentType.greedyString()).suggests(NATIONS).executes(::join)))
            .then(literal("leave").executes(::leave))
            .then(literal("rename").then(argument("name", StringArgumentType.greedyString()).executes { ctx ->
                ruled(ctx) { _, n ->
                    val name = StringArgumentType.getString(ctx, "name").take(32)
                    if (rt().state.byName(name) != null) Outcome.fail("같은 이름의 나라가 있다.")
                    else { val old = n.name; n.name = name; rt().sim.log(EventKind.MAJOR, "$old 이(가) 국호를 $name(으)로 바꿨다", n.id); Outcome.ok("국호를 바꿨다.") }
                }
            }))
            .then(literal("auto").executes { ctx -> ruled(ctx) { _, n -> n.autoGovern = !n.autoGovern; Outcome.ok("자동 통치 ${if (n.autoGovern) "켬" else "끔"}") } })
            .then(literal("claim").executes { ctx -> ruled(ctx) { p, n -> Actions.execute(rt().sim, n, Action.Claim(p.chunkPosition().pack())) } })
            .then(enumLiterals<Facility>("build") { f -> literal(f.name.lowercase()).executes { ctx -> ruled(ctx) { _, n -> Actions.execute(rt().sim, n, Action.Build(f)) } } })
            .then(enumLiterals<TechBranch>("research") { t -> literal(t.name.lowercase()).executes { ctx -> ruled(ctx) { _, n -> Actions.execute(rt().sim, n, Action.Research(t)) } } })
            .then(enumLiterals<Policy>("policy") { p -> literal(p.name.lowercase()).executes { ctx -> ruled(ctx) { _, n -> Actions.execute(rt().sim, n, Action.SetPolicy(p)) } } })
            .then(literal("recruit").then(argument("amount", IntegerArgumentType.integer(1, 500)).executes { ctx ->
                ruled(ctx) { _, n -> Actions.execute(rt().sim, n, Action.Recruit(IntegerArgumentType.getInteger(ctx, "amount"))) }
            }))
            .then(literal("deposit").executes { ctx ->
                val player = ctx.source.playerOrException
                val nation = rt().citizenNation(player) ?: return@executes reply(ctx, Outcome.fail("소속 국가가 없다."))
                reply(ctx, Deposit.fromHand(rt(), player, nation))
            })
            .then(literal("envoy").executes { ctx -> ruled(ctx) { p, n -> nearestTown(p, n)?.let { Actions.execute(rt().sim, n, Action.Envoy(it)) } ?: Outcome.fail("주변에 자유 마을이 없다.") } })
            .then(literal("conquer").executes { ctx -> ruled(ctx) { p, n -> nearestTown(p, n)?.let { Actions.execute(rt().sim, n, Action.Conquer(it)) } ?: Outcome.fail("주변에 자유 마을이 없다.") } })
            .then(enumLiterals<Treaty>("treaty") { t ->
                literal(t.name.lowercase()).then(argument("nation", StringArgumentType.greedyString()).suggests(NATIONS).executes { ctx ->
                    ruled(ctx) { _, n -> nationArg(ctx)?.let { Actions.execute(rt().sim, n, Action.ProposeTreaty(it.id, t)) } ?: Outcome.fail("그런 나라가 없다.") }.also { rt().notifyProposals() }
                })
            })
            .then(enumLiterals<Treaty>("untreaty") { t ->
                literal(t.name.lowercase()).then(argument("nation", StringArgumentType.greedyString()).suggests(NATIONS).executes { ctx ->
                    ruled(ctx) { _, n -> nationArg(ctx)?.let { Actions.execute(rt().sim, n, Action.CancelTreaty(it.id, t)) } ?: Outcome.fail("그런 나라가 없다.") }
                })
            })
            .then(literal("war").then(argument("nation", StringArgumentType.greedyString()).suggests(NATIONS).executes { ctx ->
                ruled(ctx) { _, n -> nationArg(ctx)?.let { Actions.execute(rt().sim, n, Action.DeclareWar(it.id)) } ?: Outcome.fail("그런 나라가 없다.") }
            }))
            .then(literal("peace").then(argument("nation", StringArgumentType.greedyString()).suggests(NATIONS).executes { ctx ->
                ruled(ctx) { _, n -> nationArg(ctx)?.let { Actions.execute(rt().sim, n, Action.ProposePeace(it.id)) } ?: Outcome.fail("그런 나라가 없다.") }.also { rt().notifyProposals() }
            }))
            .then(literal("accept").then(argument("id", IntegerArgumentType.integer()).executes { answer(it, true) }))
            .then(literal("decline").then(argument("id", IntegerArgumentType.integer()).executes { answer(it, false) }))
            .then(literal("setup").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes { ctx -> ctx.source.player?.let { rt().setup.offer(it) }; reply(ctx, Outcome.ok("세계 크기: 500, 1000, 2000, 3500, 5000 (/gs setup <크기> [미리생성 true|false] [조물주 true|false])")) }
                .then(argument("size", IntegerArgumentType.integer(500, 5000))
                    .executes { ctx -> setup(ctx, true, true) }
                    .then(argument("pregen", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                        .executes { ctx -> setup(ctx, com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "pregen"), true) }
                        .then(argument("god", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes { ctx -> setup(ctx, com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "pregen"), com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "god")) }))))
            .then(literal("mode")
                .then(literal("spectator").executes { ctx -> reply(ctx, rt().setup.setMode(ctx.source.playerOrException, false)) })
                .then(literal("adventure").executes { ctx -> reply(ctx, rt().setup.setMode(ctx.source.playerOrException, true)) }))
            .then(workCommands())
            .then(admin())
        dispatcher.register(gs)
    }

    private fun workCommands(): LiteralArgumentBuilder<CommandSourceStack> {
        fun i(ctx: CommandContext<CommandSourceStack>, n: String) = IntegerArgumentType.getInteger(ctx, n)
        fun box(ctx: CommandContext<CommandSourceStack>, y: Boolean) = if (y)
            com.grandstrategy.sim.Box.of(i(ctx, "x1"), i(ctx, "y1"), i(ctx, "z1"), i(ctx, "x2"), i(ctx, "y2"), i(ctx, "z2"))
        else com.grandstrategy.sim.Box.of(i(ctx, "x1"), 0, i(ctx, "z1"), i(ctx, "x2"), 0, i(ctx, "z2"))
        fun xz(tail: com.mojang.brigadier.Command<CommandSourceStack>) = argument("x1", IntegerArgumentType.integer()).then(argument("z1", IntegerArgumentType.integer())
            .then(argument("x2", IntegerArgumentType.integer()).then(argument("z2", IntegerArgumentType.integer()).executes(tail))))
        fun xyz(tail: com.mojang.brigadier.Command<CommandSourceStack>) = argument("x1", IntegerArgumentType.integer()).then(argument("y1", IntegerArgumentType.integer())
            .then(argument("z1", IntegerArgumentType.integer()).then(argument("x2", IntegerArgumentType.integer()).then(argument("y2", IntegerArgumentType.integer())
                .then(argument("z2", IntegerArgumentType.integer()).executes(tail))))))
        fun send(ctx: CommandContext<CommandSourceStack>, m: com.grandstrategy.net.ToServer): Int {
            rt().workService.handle(ctx.source.playerOrException, com.grandstrategy.net.WorkJson.json.encodeToString(com.grandstrategy.net.ToServer.serializer(), m))
            return 1
        }
        val templates = SuggestionProvider<CommandSourceStack> { _, b ->
            rt().work.templates.all.forEach { b.suggest(it.index, com.mojang.brigadier.Message { "${it.kind.label} ${it.name}" }) }
            b.buildFuture()
        }
        return literal("work")
            .then(literal("templates").executes { ctx ->
                val text = rt().work.templates.all.joinToString("\n") { "${it.index}: ${it.kind.label} ${it.name} ${it.sizeX}×${it.sizeY}×${it.sizeZ}" }
                reply(ctx, Texts.prefix().append(Texts.plain(text, ChatFormatting.WHITE)))
            })
            .then(literal("build").then(argument("template", IntegerArgumentType.integer(0)).suggests(templates)
                .then(argument("rotation", IntegerArgumentType.integer(0, 3)).then(xz { ctx ->
                    send(ctx, com.grandstrategy.net.ToServer.Order(com.grandstrategy.net.WorkTool.BUILD, box(ctx, false), i(ctx, "template"), i(ctx, "rotation"), true))
                }))))
            .then(literal("walls").then(xz { ctx -> send(ctx, com.grandstrategy.net.ToServer.Order(com.grandstrategy.net.WorkTool.WALLS, box(ctx, false), conscript = true)) }))
            .then(literal("flatten").then(xz { ctx -> send(ctx, com.grandstrategy.net.ToServer.Order(com.grandstrategy.net.WorkTool.FLATTEN, box(ctx, false), conscript = true)) }))
            .then(literal("demolish").then(xyz { ctx -> send(ctx, com.grandstrategy.net.ToServer.Order(com.grandstrategy.net.WorkTool.DEMOLISH, box(ctx, true), conscript = true)) }))
            .then(literal("select").then(argument("x", IntegerArgumentType.integer()).then(argument("y", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer()).executes { ctx ->
                send(ctx, com.grandstrategy.net.ToServer.Select(i(ctx, "x"), i(ctx, "y"), i(ctx, "z")))
            }))))
            .then(literal("building").then(argument("id", IntegerArgumentType.integer()).then(argument("action", StringArgumentType.word())
                .suggests { _, b -> SharedSuggestionProvider.suggest(listOf("demolish", "assign", "forget"), b) }
                .executes { ctx -> buildingAction(ctx, null) }
                .then(argument("kind", StringArgumentType.word())
                    .suggests { _, b -> SharedSuggestionProvider.suggest(com.grandstrategy.sim.BuildingKind.entries.map { it.name.lowercase() }, b) }
                    .executes { ctx -> buildingAction(ctx, runCatching { com.grandstrategy.sim.BuildingKind.valueOf(StringArgumentType.getString(ctx, "kind").uppercase()) }.getOrNull()) }))))
            .then(literal("orders").executes { ctx ->
                val r = rt()
                val text = r.state.orders.values.joinToString("\n") { o ->
                    val pct = if (o.total > 0) o.progress * 100 / o.total else 0
                    "#${o.id} ${r.state.nations[o.nation]?.name} ${o.type.label} ${o.kind?.label ?: ""} $pct% (${o.progress}/${o.total}) 인부 ${r.work.crewSize(o.id)} 현장 ${r.work.workers(o, r.state.nations[o.nation]!!).toInt()}${if (o.pendingPlacement) " 배치대기" else ""} ${o.box}"
                }.ifEmpty { "진행 중인 작업 없음" }
                reply(ctx, Texts.prefix().append(Texts.plain(text, ChatFormatting.WHITE)))
            })
            .then(literal("buildings").executes { ctx ->
                val r = rt()
                val text = r.state.buildings.values.toList().takeLast(30).joinToString("\n") { b ->
                    "#${b.id} ${r.state.nations[b.nation ?: -1]?.name ?: "-"} ${b.kind.label} ${if (b.complete) "완공" else "공사중"}${if (b.detected) " 인식" else ""} ${b.box}"
                }.ifEmpty { "건물 없음" }
                reply(ctx, Texts.prefix().append(Texts.plain(text, ChatFormatting.WHITE)))
            })
    }

    private fun setup(ctx: CommandContext<CommandSourceStack>, pregen: Boolean, god: Boolean): Int {
        val size = IntegerArgumentType.getInteger(ctx, "size")
        val tier = WorldTier.of(size) ?: return reply(ctx, Outcome.fail("크기는 500, 1000, 2000, 3500, 5000 중 하나"))
        return reply(ctx, rt().setup.apply(tier, pregen, god, ctx.source.textName))
    }

    private fun buildingAction(ctx: CommandContext<CommandSourceStack>, kind: com.grandstrategy.sim.BuildingKind?): Int {
        val player = ctx.source.playerOrException
        player.connection.send(net.minecraft.network.protocol.game.ClientboundContainerClosePacket(0))
        val message = com.grandstrategy.net.ToServer.BuildingAction(IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "action"), kind, true)
        rt().workService.handle(player, com.grandstrategy.net.WorkJson.json.encodeToString(com.grandstrategy.net.ToServer.serializer(), message))
        return 1
    }

    private fun admin(): LiteralArgumentBuilder<CommandSourceStack> = literal("admin")
        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
        .then(literal("speed").then(argument("ticks", IntegerArgumentType.integer(1, 72000)).executes { ctx ->
            rt().settings.turnTicks = IntegerArgumentType.getInteger(ctx, "ticks")
            reply(ctx, Outcome.ok("1턴 = ${rt().settings.turnTicks}틱 (${"%.1f".format(rt().settings.turnTicks / 20.0)}초)"))
        }))
        .then(literal("pause").executes { ctx -> rt().settings.paused = true; reply(ctx, Outcome.ok("시뮬레이션 정지")) })
        .then(literal("resume").executes { ctx -> rt().settings.paused = false; reply(ctx, Outcome.ok("시뮬레이션 재개")) })
        .then(literal("step").executes { step(it, 1) }
            .then(argument("turns", IntegerArgumentType.integer(1, 1000)).executes { step(it, IntegerArgumentType.getInteger(it, "turns")) }))
        .then(literal("seed").then(argument("count", IntegerArgumentType.integer(1, 32)).executes { ctx ->
            val r = rt()
            val made = r.sim.seedNations(IntegerArgumentType.getInteger(ctx, "count"), r.sim.spawnCenter, r.settings.seedRadiusChunks)
            r.state.seeded = true
            reply(ctx, Outcome.ok("나라 ${made}개를 새로 세웠다."))
        }))
        .then(literal("perf").executes { ctx ->
            val r = rt()
            val claims = r.state.alive().sumOf { it.claims.size }
            reply(ctx, Texts.prefix().append(Texts.plain(
                "턴 ${r.state.turn} · 나라 ${r.state.alive().size} · 영토 $claims · 마을 ${r.state.settlements.size} · 전쟁 ${r.state.wars.size}\n" +
                    "턴 처리 최근 ${"%.2f".format(r.lastTurnMs)}ms · 평균 ${"%.2f".format(r.avgTurnMs)}ms · 최대 ${"%.2f".format(r.maxTurnMs)}ms (${r.measuredTurns}턴 측정)",
                ChatFormatting.WHITE)))
        })
        .then(literal("save").executes { ctx -> rt().save(); reply(ctx, Outcome.ok("저장했다.")) })
        .then(literal("pregen")
            .then(literal("cancel").executes { ctx -> reply(ctx, rt().setup.cancel()) })
            .then(literal("status").executes { ctx -> reply(ctx, Outcome.ok("미리 생성 상태 ${rt().settings.pregen} · ${"%.1f".format(rt().setup.progress() * 100)}% (${rt().settings.pregenIndex}청크)")) }))
        .then(literal("cutscene").then(argument("kind", StringArgumentType.word())
            .suggests { _, b -> SharedSuggestionProvider.suggest(CutsceneKind.entries.map { it.name.lowercase() }, b) }
            .then(argument("player", EntityArgument.player()).executes { ctx ->
                val r = rt()
                val target = EntityArgument.getPlayer(ctx, "player")
                val kind = runCatching { CutsceneKind.valueOf(StringArgumentType.getString(ctx, "kind").uppercase()) }.getOrNull()
                    ?: return@executes reply(ctx, Outcome.fail("종류: founding, capital_fall, destruction"))
                val nation = r.citizenNation(target) ?: r.state.alive().firstOrNull()
                    ?: return@executes reply(ctx, Outcome.fail("나라가 없다."))
                val other = r.state.alive().firstOrNull { it.id != nation.id }
                val chunk = if (kind == CutsceneKind.FOUNDING) target.chunkPosition().pack() else nation.capital
                r.cutscenes.play(target, r.cutscenes.spec(kind, nation, other, chunk, target.gameProfile.name()))
                reply(ctx, Outcome.ok("${target.gameProfile.name()}에게 ${kind.name} 컷신 재생"))
            })))
        .then(literal("agents").executes { ctx -> reply(ctx, Texts.prefix().append(Texts.plain(agentStatus(), ChatFormatting.WHITE))) }
            .then(literal("benchmark").then(argument("count", IntegerArgumentType.integer(0, 200_000)).executes { ctx ->
                val r = rt()
                val n = IntegerArgumentType.getInteger(ctx, "count")
                r.population.benchmarkTotal = n
                var guard = 0
                while (r.population.agents.count < n * 99 / 100 && guard++ < 1000) r.population.sync()
                r.population.sync()
                reply(ctx, Outcome.ok("벤치마크: 개체 ${r.population.agents.count}명 (인구와 무관하게 고정)"))
            }))
            .then(literal("off").executes { ctx ->
                val r = rt()
                r.population.benchmarkTotal = null
                r.population.sync()
                reply(ctx, Outcome.ok("벤치마크 해제: 인구 기준으로 돌아간다 (개체 ${r.population.agents.count}명)"))
            })
            .then(literal("vanilla").then(argument("count", IntegerArgumentType.integer(1, 20_000)).executes { ctx ->
                val player = ctx.source.playerOrException
                val level = player.level() as net.minecraft.server.level.ServerLevel
                val n = IntegerArgumentType.getInteger(ctx, "count")
                val rng = java.util.Random(1)
                var made = 0
                repeat(n) {
                    val x = player.x + rng.nextGaussian() * 40
                    val z = player.z + rng.nextGaussian() * 40
                    val bx = kotlin.math.floor(x).toInt()
                    val bz = kotlin.math.floor(z).toInt()
                    if (!level.chunkSource.hasChunk(bx shr 4, bz shr 4)) return@repeat
                    val y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz)
                    val v = net.minecraft.world.entity.npc.villager.Villager(net.minecraft.world.entity.EntityTypes.VILLAGER, level)
                    v.snapTo(x, y.toDouble(), z, rng.nextFloat() * 360f, 0f)
                    v.entityTags().add("gs_bench")
                    v.setPersistenceRequired()
                    if (level.addFreshEntity(v)) made++
                }
                reply(ctx, Outcome.ok("바닐라 주민 ${made}명 소환 (비교용, 태그 gs_bench)"))
            }))
            .then(literal("probe").then(argument("x", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer()).executes { ctx ->
                val r = rt()
                val level = r.server.overworld()
                val x = IntegerArgumentType.getInteger(ctx, "x")
                val z = IntegerArgumentType.getInteger(ctx, "z")
                val w = r.agentDisplay.walker
                val est = w.groundEstimate(level, x, z)
                val snap = w.snap(level, x + 0.5f, Float.NaN, z + 0.5f)
                val top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                reply(ctx, Outcome.ok("top=$top ground=$est snap=$snap"))
            })))
            .then(literal("clearvanilla").executes { ctx ->
                val level = rt().server.overworld()
                val list = level.getEntities(net.minecraft.world.entity.EntityTypes.VILLAGER) { it.entityTags().contains("gs_bench") }
                list.forEach { it.discard() }
                reply(ctx, Outcome.ok("비교용 주민 ${list.size}명 제거"))
            }))
        .then(literal("reload").executes { ctx ->
            val result = BalanceFile.reload()
            var text = Texts.outcome(Outcome(result.ok, result.message)).copy()
            for (line in result.changed.take(12)) text = text.append(Texts.plain("\n  $line"))
            if (result.changed.size > 12) text = text.append(Texts.plain("\n  … 외 ${result.changed.size - 12}개"))
            reply(ctx, text)
        })

    private var lastStatusAt = System.nanoTime()
    private var lastPackets = 0L
    private var lastBytes = 0L

    private fun agentStatus(): String {
        val r = rt()
        val mspt = r.server.averageTickTimeNanos / 1e6
        val tps = minOf(20.0, 1000.0 / mspt.coerceAtLeast(0.001))
        val now = System.nanoTime()
        val seconds = ((now - lastStatusAt) / 1e9).coerceAtLeast(0.001)
        val pps = (r.agentDisplay.packetsTotal - lastPackets) / seconds
        val kbps = (r.agentDisplay.bytesEstimate - lastBytes) / seconds / 1024
        lastStatusAt = now
        lastPackets = r.agentDisplay.packetsTotal
        lastBytes = r.agentDisplay.bytesEstimate
        return "개체 ${r.population.agents.count}명${r.population.benchmarkTotal?.let { " (벤치마크 $it)" } ?: ""} · 표시 중 ${r.agentDisplay.shownTotal} · 패킷 ${"%.0f".format(pps)}/초 (약 ${"%.1f".format(kbps)} KB/초)\n" +
            "개체 처리 ${"%.3f".format(r.agentNanos / 1e6)}ms/틱 (표시 ${"%.3f".format(r.agentDisplay.lastNanos / 1e6)}ms) · 서버 MSPT ${"%.2f".format(mspt)} · TPS ${"%.2f".format(tps)}\n" +
            "순간이동 ${r.agentDisplay.teleports}건 · 경로 탐색 ${r.agentDisplay.walker.searches}회 (평균 ${"%.3f".format(r.agentDisplay.walker.searchNanos / 1e6 / r.agentDisplay.walker.searches.coerceAtLeast(1))}ms) · 시간대 ${r.population.phase()}"
    }

    private fun step(ctx: CommandContext<CommandSourceStack>, turns: Int): Int {
        val r = rt()
        if (!r.state.seeded) r.seedWorld()
        repeat(turns) { r.runTurn() }
        return reply(ctx, Outcome.ok("${turns}턴 진행 (현재 ${r.state.turn}턴, 평균 ${"%.2f".format(r.avgTurnMs)}ms)"))
    }

    private fun nearestTown(player: ServerPlayer, nation: Nation): Int? {
        val here = player.chunkPosition().pack()
        return rt().state.settlements.values.filter { it.owner == null }.minByOrNull { Chunks.distance(it.center, here) }
            ?.takeIf { Chunks.distance(it.center, here) <= 8 }?.id
    }

    private fun list(ctx: CommandContext<CommandSourceStack>): Int {
        val r = rt()
        val nations = r.state.alive().sortedByDescending { it.claims.size }
        if (nations.isEmpty()) return reply(ctx, Outcome.fail("아직 나라가 없다."))
        var text = Texts.prefix().append(Texts.plain("${r.state.turn}턴 · 나라 ${nations.size}개", ChatFormatting.WHITE))
        for (n in nations) {
            text = text.append(Component.literal("\n "))
                .append(Texts.nation(n))
                .append(Texts.plain("  영토 ${n.claims.size} · 인구 ${n.population.toInt()} · 병력 ${n.military.toInt()} · 안정 ${n.stability.toInt()} · ${n.rulerName ?: "AI"}"))
        }
        return reply(ctx, text)
    }

    private fun info(ctx: CommandContext<CommandSourceStack>, target: Nation?): Int {
        val r = rt()
        val nation = target ?: ctx.source.player?.let { r.citizenNation(it) } ?: return reply(ctx, Outcome.fail("나라를 지정해야 한다."))
        val stock = Resource.entries.joinToString(" · ") { "${it.label} ${Texts.number(nation.has(it))}(${Texts.signed(nation.lastIncome[it] ?: 0.0)})" }
        val tech = nation.tech.entries.filter { it.value > 0 }.joinToString(", ") { "${it.key.label}${it.value}" }.ifEmpty { "없음" }
        val facilities = nation.facilities.entries.filter { it.value > 0 }.joinToString(", ") { "${it.key.label}${it.value}" }.ifEmpty { "없음" }
        val wars = r.state.warsOf(nation.id).joinToString(", ") { w ->
            val enemy = if (nation.id in w.attackers) w.defender else w.attacker
            "${r.state.nations[enemy]?.name}(점수 ${w.scoreFor(nation.id).toInt()})"
        }.ifEmpty { "없음" }
        val text = Texts.prefix().append(Texts.nation(nation))
            .append(Texts.plain("\n 수도 ${Chunks.format(nation.capital)} · 영토 ${nation.claims.size} · 인구 ${nation.population.toInt()} · 안정 ${nation.stability.toInt()} · 정책 ${nation.policy.label} · 자세 ${nation.posture.label}"))
            .append(Texts.plain("\n $stock"))
            .append(Texts.plain("\n 병력 ${nation.military.toInt()} · 전투력 ${Texts.number(r.sim.strength(nation))} · 전쟁: $wars"))
            .append(Texts.plain("\n 기술: $tech · 연구 중 ${nation.researchFocus.label} ${nation.researchProgress.toInt()}/${Balance.techCost(nation.level(nation.researchFocus)).toInt()}"))
            .append(Texts.plain("\n 시설: $facilities"))
        return reply(ctx, text)
    }

    private fun here(ctx: CommandContext<CommandSourceStack>): Int {
        val r = rt()
        val player = ctx.source.playerOrException
        val chunk = player.chunkPosition().pack()
        val owner = r.sim.ownerOf(chunk)
        val town = r.sim.settlementAt(chunk)
        val parts = mutableListOf("${Chunks.format(chunk)} · ${r.sim.terrain(chunk).label}")
        parts += owner?.let { "${it.name} 영토${if (it.capital == chunk) " (수도)" else ""}" } ?: "무주지"
        town?.let { parts += "마을 ${it.name} (인구 ${it.population.toInt()}, ${if (it.owner == null) "자유" else r.state.nations[it.owner]?.name + " 소속"})" }
        return reply(ctx, Texts.prefix().append(Texts.plain(parts.joinToString(" · "), ChatFormatting.WHITE)))
    }

    private fun observe(ctx: CommandContext<CommandSourceStack>, verbose: Boolean): Int {
        val player = ctx.source.playerOrException
        val obs = rt().observers
        val now = obs[player.uuid]
        return if (now != null && now == verbose) {
            obs.remove(player.uuid)
            reply(ctx, Outcome.ok("관찰 모드를 껐다."))
        } else {
            obs[player.uuid] = verbose
            reply(ctx, Outcome.ok(if (verbose) "관찰 모드: AI 판단까지 모두 본다." else "관찰 모드: 세계의 전쟁·외교 소식을 받는다."))
        }
    }

    private fun pick(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val r = rt()
        val nation = nationArg(ctx) ?: return reply(ctx, Outcome.fail("그런 나라가 없다."))
        val uuid = player.uuid.toString()
        if (r.state.rulerNation(uuid) != null) return reply(ctx, Outcome.fail("이미 나라를 다스리고 있다."))
        if (nation.ruler != null) return reply(ctx, Outcome.fail("${nation.rulerName} 님이 다스리는 나라다."))
        r.state.alive().forEach { it.citizens.remove(uuid) }
        nation.ruler = uuid
        nation.rulerName = player.gameProfile.name()
        nation.autoGovern = false
        nation.citizens += uuid
        r.sim.log(EventKind.MAJOR, "${player.gameProfile.name()} 님이 ${nation.name}의 통치자가 되었다", nation.id)
        return reply(ctx, Outcome.ok("${nation.name}을(를) 맡았다. /gs 로 운영한다."))
    }

    private fun join(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val r = rt()
        val nation = nationArg(ctx) ?: return reply(ctx, Outcome.fail("그런 나라가 없다."))
        val uuid = player.uuid.toString()
        if (r.state.rulerNation(uuid) != null) return reply(ctx, Outcome.fail("통치자는 다른 나라에 합류할 수 없다. 먼저 /gs leave"))
        r.state.alive().forEach { it.citizens.remove(uuid) }
        nation.citizens += uuid
        return reply(ctx, Outcome.ok("${nation.name}의 국민이 되었다. 전선 근처에 있으면 전투를 돕는다."))
    }

    private fun leave(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val r = rt()
        val uuid = player.uuid.toString()
        val ruled = r.state.rulerNation(uuid)
        if (ruled != null) {
            ruled.ruler = null
            ruled.rulerName = null
            ruled.autoGovern = false
            r.sim.log(EventKind.MAJOR, "${player.gameProfile.name()} 님이 ${ruled.name}의 통치에서 물러났다", ruled.id)
        }
        r.state.alive().forEach { it.citizens.remove(uuid) }
        return reply(ctx, Outcome.ok("소속 국가를 떠났다."))
    }

    private fun answer(ctx: CommandContext<CommandSourceStack>, accept: Boolean): Int =
        ruled(ctx) { _, n -> Actions.answer(rt().sim, IntegerArgumentType.getInteger(ctx, "id"), n, accept) }
}
