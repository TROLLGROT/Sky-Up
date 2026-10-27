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
    data class BodyFacts(
        val radiusKm: String, val massKg: String, val gravity: String,
        val day: String, val year: String, val factEn: String, val factRu: String
    )
    val bodyFacts = mapOf(
        "Sun" to BodyFacts("695,700", "1.9885 × 10³⁰", "274 m/s²", "25–35 days (rotation varies by latitude)", "≈ 230 million years around the Milky Way", "The Sun is a G-type main-sequence star; fusion in its core powers its light.", "Солнце — звезда главной последовательности типа G; свет рождается при термоядерном синтезе в ядре."),
        "Mercury" to BodyFacts("2,439.7", "3.301 × 10²³", "3.70 m/s²", "58.6 Earth days", "88 Earth days", "A day-night cycle on Mercury lasts two of its years.", "Цикл от восхода до следующего восхода на Меркурии длится два его года."),
        "Venus" to BodyFacts("6,051.8", "4.867 × 10²⁴", "8.87 m/s²", "243 Earth days (retrograde)", "225 Earth days", "Its dense carbon-dioxide atmosphere creates surface pressure about 92 times Earth's.", "Плотная атмосфера из углекислого газа создаёт у поверхности давление примерно в 92 раза выше земного."),
        "Earth" to BodyFacts("6,371", "5.972 × 10²⁴", "9.81 m/s²", "23 h 56 min", "365.256 days", "The only world known to host life; liquid water covers most of its surface.", "Единственный известный мир с жизнью; большая часть поверхности покрыта жидкой водой."),
        "Moon" to BodyFacts("1,737.4", "7.342 × 10²²", "1.62 m/s²", "Synchronous rotation", "27.32 days around Earth", "The Moon keeps nearly the same hemisphere facing Earth and slowly recedes from us.", "Луна почти всегда обращена к Земле одной стороной и медленно удаляется от нас."),
        "Mars" to BodyFacts("3,389.5", "6.417 × 10²³", "3.71 m/s²", "24 h 37 min", "687 Earth days", "Olympus Mons is the largest known volcano in the Solar System.", "На Марсе находится Олимп — крупнейший известный вулкан Солнечной системы."),
        "Jupiter" to BodyFacts("69,911", "1.898 × 10²⁷", "24.79 m/s²", "9 h 56 min", "11.86 Earth years", "Europa, one of Jupiter's moons, is believed to hide a deep global saltwater ocean beneath its ice.", "Подо льдом Европы, спутника Юпитера, вероятно, скрывается глубокий глобальный океан солёной воды."),
        "Saturn" to BodyFacts("58,232", "5.683 × 10²⁶", "10.44 m/s²", "10 h 33 min", "29.45 Earth years", "Enceladus has a subsurface ocean; icy jets from its south pole let spacecraft sample material from it.", "Под Энцеладом есть океан; ледяные выбросы у южного полюса позволяют исследовать его вещество."),
        "Uranus" to BodyFacts("25,362", "8.681 × 10²⁵", "8.69 m/s²", "17 h 14 min (retrograde)", "84 Earth years", "Uranus rotates almost on its side, producing extreme seasons.", "Уран вращается почти лёжа на боку, поэтому его сезоны особенно необычны."),
        "Neptune" to BodyFacts("24,622", "1.024 × 10²⁶", "11.15 m/s²", "16 h 6 min", "164.8 Earth years", "Neptune's atmosphere hosts the fastest winds measured on any planet.", "В атмосфере Нептуна дуют самые быстрые ветры среди планет."),
    )
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
