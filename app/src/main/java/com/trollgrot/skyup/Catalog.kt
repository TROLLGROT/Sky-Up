package com.trollgrot.skyup
import android.content.Context

object L {
    var ru = true
    fun t(en: String, r: String) = if (ru) r else en
    private val B = mapOf("Sun" to "Солнце", "Moon" to "Луна", "Mercury" to "Меркурий", "Venus" to "Венера", "Earth" to "Земля", "Mars" to "Марс",
        "Jupiter" to "Юпитер", "Saturn" to "Сатурн", "Uranus" to "Уран", "Neptune" to "Нептун")
    fun name(n: String) = if (!ru) n else B[n] ?: Catalog.ru[n] ?: n
    fun type(t: String) = if (!ru) t else when (t) { "Planet" -> "Планета"; "Star" -> "Звезда"; "Star (our Sun)" -> "Звезда (наше Солнце)"; "Natural satellite" -> "Естественный спутник"; else -> t }
}

class Con(val ra: Double, val dec: Double, val en: String, val ru: String)

/** Star catalog (Hipparcos, mag <= 5.5) and constellation figures from the d3-celestial data set (BSD-3). */
object Catalog {
    var stars: List<Body> = emptyList()
    var lines: List<DoubleArray> = emptyList()
    var cons: List<Con> = emptyList()
    var named: List<String> = emptyList()
    val ru = HashMap<String, String>()

    fun load(ctx: Context) {
        if (stars.isNotEmpty()) return
        try {
            val s = ArrayList<Body>(); val nm = ArrayList<Pair<Double, String>>()
            ctx.assets.open("stars.tsv").bufferedReader().forEachLine { l ->
                val p = l.split("\t"); if (p.size < 5) return@forEachLine
                val name = if (p.size > 5 && p[5].isNotEmpty()) p[5] else "HIP " + p[4]
                if (p.size > 6 && p[6].isNotEmpty()) ru[name] = p[6]
                if (!name.startsWith("HIP")) nm.add(p[2].toDouble() to name)
                s.add(Body(name, "Star", p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), "", p[3].toDouble()))
            }
            stars = s.sortedBy { it.mag }; named = nm.sortedBy { it.first }.map { it.second }.take(150)
            lines = ctx.assets.open("lines.txt").bufferedReader().readLines().filter { it.isNotBlank() }.map { l -> l.trim().split(" ").map { it.toDouble() }.toDoubleArray() }
            cons = ctx.assets.open("cons.tsv").bufferedReader().readLines().mapNotNull { l -> val p = l.split("\t"); if (p.size < 4) null else Con(p[0].toDouble(), p[1].toDouble(), p[2], p[3]) }
        } catch (e: Exception) { }
    }
}
