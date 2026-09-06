package io.github.earthdiver.qrrepair.core

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

data class LabPoint(val l: Double, val a: Double, val b: Double) {
    fun distanceTo(other: LabPoint): Double {
        val dl = l - other.l
        val da = a - other.a
        val db = b - other.b
        return sqrt(dl * dl + da * da + db * db)
    }
}

data class UnknownColorCluster(
    val l100: Int,
    val a100: Int,
    val b100: Int,
    val maxDeltaE100: Int,
) {
    init {
        require(l100 in 0..10_000 && a100 in -20_000..20_000 && b100 in -20_000..20_000)
        require(maxDeltaE100 in 100..4_000)
    }

    val center: LabPoint get() = LabPoint(l100 / 100.0, a100 / 100.0, b100 / 100.0)
    fun contains(rgb: Int): Boolean = ColorLab.fromRgb(rgb).distanceTo(center) <= maxDeltaE100 / 100.0
}

object ColorLab {
    fun fromRgb(rgb: Int): LabPoint {
        fun linear(value: Int): Double {
            val channel = value / 255.0
            return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
        }
        val r = linear(rgb ushr 16 and 0xFF)
        val g = linear(rgb ushr 8 and 0xFF)
        val b = linear(rgb and 0xFF)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b)
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        fun f(value: Double): Double = if (value > 216.0 / 24389.0) cbrt(value) else (24389.0 / 27.0 * value + 16.0) / 116.0
        val fx = f(x); val fy = f(y); val fz = f(z)
        return LabPoint(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }
}
