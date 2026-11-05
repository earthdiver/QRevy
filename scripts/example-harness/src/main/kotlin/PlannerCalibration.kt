package evaluation

import io.github.earthdiver.qrrepair.core.*
import kotlin.random.Random
import kotlin.system.measureNanoTime

/** Opt-in desktop timing study: always verify equivalent output before reporting timings. */
fun main() {
    val memory = 32L * 1024 * 1024
    val rs = ReedSolomonErasures()
    println("length,data,erasures,seed,gsWork,wuWork,gsMedianNs,wuMedianNs")
    for ((n, k) in listOf(26 to 19, 26 to 9, 44 to 16, 35 to 13, 32 to 19, 20 to 10)) {
        for (e in listOf(0, 1, 2)) for (seed in 0..2) {
            val random = Random(9173 + seed)
            val gs = GuruswamiSudanDecoder(n, k)
            val p = gs.parameters(e, memory) ?: continue
            val wu = WuDecoder(n, k)
            val word = rs.encode(IntArray(k) { random.nextInt(256) }, n - k)
            val positions = word.indices.shuffled(random)
            val erased = positions.take(e).toIntArray()
            val input = word.copyOf()
            positions.drop(e).take(p.radius).forEach { input[it] = input[it] xor random.nextInt(1, 256) }
            val gsTimes = mutableListOf<Long>()
            val wuTimes = mutableListOf<Long>()
            repeat(125) { iteration ->
                var gsWords = emptySet<List<Int>>()
                var wuWords = emptySet<List<Int>>()
                fun runGs() {
                    val elapsed = measureNanoTime { gsWords = gs.decode(input, erased, p, memory).map { it.toList() }.toSet() }
                    if (iteration >= 25) gsTimes += elapsed
                }
                fun runWu() {
                    val elapsed = measureNanoTime {
                        wuWords = buildSet { wu.decode(input, erased, p.radius, memory) { add(it.toList()) } }
                    }
                    if (iteration >= 25) wuTimes += elapsed
                }
                if (iteration % 2 == 0) { runGs(); runWu() } else { runWu(); runGs() }
                check(gsWords == wuWords && word.toList() in gsWords)
            }
            val width = p.listDegree + 1L
            val gsWork = width * width * (p.degreeBound + 1L) * (p.degreeBound + 1L) / 2
            println("$n,$k,$e,$seed,$gsWork,${wu.estimatedWork(e)},${gsTimes.sorted()[50]},${wuTimes.sorted()[50]}")
        }
    }
}
