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

    // Rounded reference values for the brightest familiar stars. Unknown figures are
    // intentionally left blank rather than guessed from colour alone.
    data class StarFacts(val kind: String, val radius: String, val mass: String, val distanceLy: String, val note: String)
    val starFacts = mapOf(
        "Sirius" to StarFacts("A1V · белая звезда главной последовательности", "1.71 R☉", "2.06 M☉", "8.6 св. лет", "Самая яркая звезда ночного неба. Её спутник Сириус B — белый карлик с массой около 0.98 M☉ и диаметром меньше земного."),
        "Canopus" to StarFacts("A9 II · яркий гигант", "71 R☉", "8 M☉", "310 св. лет", "Вторая по яркости звезда ночного неба."),
        "Arcturus" to StarFacts("K1.5 III · оранжевый гигант", "25 R☉", "1.1 M☉", "37 св. лет", "Один из ближайших к Солнцу ярких гигантов."),
        "Vega" to StarFacts("A0V · бело-голубая звезда", "≈ 2.7 R☉", "≈ 2.1 M☉", "25 св. лет", "Одна из ярчайших звёзд северного неба; быстро вращается и сплюснута."),
        "Capella" to StarFacts("G8 III + G0 III · двойной гигант", "12 R☉ и 9 R☉", "2.6 M☉ и 2.5 M☉", "43 св. года", "Видимая Капелла — тесная двойная система гигантов."),
        "Rigel" to StarFacts("B8 Ia · голубой сверхгигант", "78 R☉", "21 M☉", "860 св. лет", "Одна из самых ярких звёзд Ориона."),
        "Procyon" to StarFacts("F5 IV–V · субгигант", "2.05 R☉", "1.5 M☉", "11.5 св. лет", "Двойная система; спутник — белый карлик."),
        "Betelgeuse" to StarFacts("M1–2 Ia · красный сверхгигант", "≈ 1000 R☉", "≈ 16.5 M☉", "≈ 700 св. лет", "Полуправильная переменная звезда; её размер и расстояние уточняются."),
        "Altair" to StarFacts("A7V · белая звезда главной последовательности", "1.8 R☉", "1.8 M☉", "17 св. лет", "Быстро вращается и заметно сплюснута у полюсов."),
        "Aldebaran" to StarFacts("K5 III · оранжевый гигант", "44 R☉", "1.16 M☉", "65 св. лет", "Ярчайшая звезда созвездия Тельца."),
        "Antares" to StarFacts("M1.5 Iab · красный сверхгигант", "≈ 680 R☉", "≈ 12 M☉", "≈ 550 св. лет", "Массивная переменная звезда в созвездии Скорпиона."),
        "Spica" to StarFacts("B1 III–IV · двойная голубая звезда", "7.5 R☉ и 4 R☉", "10.3 M☉ и 7 M☉", "250 св. лет", "Тесная двойная система; компоненты обращаются друг вокруг друга."),
        "Pollux" to StarFacts("K0 III · оранжевый гигант", "9 R☉", "1.9 M☉", "34 св. года", "Самая яркая звезда Близнецов."),
        "Deneb" to StarFacts("A2 Ia · белый сверхгигант", "≈ 200 R☉", "≈ 19 M☉", "≈ 2600 св. лет", "Дистанция и физические параметры имеют заметную неопределённость."),
        "Regulus" to StarFacts("B7 V · голубовато-белая звезда", "3.1 R☉", "3.8 M☉", "79 св. лет", "Быстро вращается; входит в многократную звёздную систему."),
        "Polaris" to StarFacts("F7 Ib · жёлто-белый сверхгигант", "≈ 46 R☉", "≈ 5.4 M☉", "≈ 430 св. лет", "Пульсирующая цефеида и часть тройной системы."),
        "Fomalhaut" to StarFacts("A3 V · белая звезда", "1.8 R☉", "1.9 M☉", "25 св. лет", "Окружена заметным диском пыли и обломков."),
        "Achernar" to StarFacts("B6 V · голубая звезда", "≈ 9 R☉", "≈ 6.7 M☉", "139 св. лет", "Очень быстро вращается и сильно сплюснута."),
        "Mimosa" to StarFacts("B0.5 III · голубой гигант", "8.4 R☉", "16 M☉", "280 св. лет", "Также известна как Бета Южного Креста."),
        "Castor" to StarFacts("A1 V · кратная система", "2.4 R☉ (основной компонент)", "2.4 M☉ (основной компонент)", "51 св. год", "Система из шести звёзд в трёх визуальных парах.")
    )

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
