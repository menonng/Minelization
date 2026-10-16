package com.grandstrategy.sim

import kotlin.random.Random

object Names {
    private val heads = listOf("아르", "벨", "카", "로", "드", "엘", "타", "몬", "세", "라", "브", "오", "리", "하", "에", "토", "발", "미", "노", "그")
    private val middles = listOf("덴", "라", "니", "로", "베", "스", "타", "린", "마", "가", "도", "레", "", "", "")
    private val tails = listOf("아", "온", "하임", "스", "란", "드", "에", "니아", "로스", "벨", "탄", "모르")
    private val forms = listOf("왕국", "공국", "제국", "연방", "공화국", "후국", "대공국")
    private val villageTails = listOf("마을", "촌", "골", "언덕 마을", "들 마을")

    private fun root(rng: Random): String = heads.random(rng) + middles.random(rng) + tails.random(rng)

    fun nation(rng: Random): String = "${root(rng)} ${forms.random(rng)}"

    fun rebel(rng: Random): String = "${root(rng)} 자유국"

    fun settlement(rng: Random): String = root(rng) + " " + villageTails.random(rng)
}
