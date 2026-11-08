package fr.preludedash.link

import android.os.SystemClock
import fr.preludedash.data.Calibration
import fr.preludedash.data.EngineData
import kotlin.math.sin

/** Fausses données pour voir l'interface sans la voiture : ralenti, montée en régime, décélération. */
class DemoSource {
    private val t0 = SystemClock.elapsedRealtime()

    fun next(cal: Calibration): EngineData {
        val t = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val cycle = (t % 14.0) / 14.0
        val top = cal.redline + 150
        val rpm = when {
            cycle < 0.15 -> 850 + (25 * sin(t * 6)).toInt()
            cycle < 0.55 -> (900 + (top - 900) * ((cycle - 0.15) / 0.40)).toInt()
            cycle < 0.62 -> 3300
            else -> (3300 - 2400 * ((cycle - 0.62) / 0.38)).toInt()
        }
        val wot = cycle in 0.15..0.55
        val decel = cycle >= 0.62
        val tps = when {
            wot -> 100
            decel -> 0
            cycle < 0.15 -> 0
            else -> 14
        }
        val afr = when {
            wot -> 11.9f + 0.25f * sin(t * 4).toFloat()
            decel -> 16.8f
            else -> 14.6f + 0.35f * sin(t * 2).toFloat()
        }
        val map = when {
            wot -> 990
            decel -> 260
            cycle < 0.15 -> 330
            else -> 520
        }
        val inj = when {
            wot -> 2.6f + rpm / 2500f
            decel -> 0f
            else -> 2.4f
        }
        val ect = (45.0 + t * 1.5).coerceAtMost(88.0).toFloat()
        val o2v = cal.afrV1 + (afr - cal.afrA1) * (cal.afrV2 - cal.afrV1) / (cal.afrA2 - cal.afrA1)
        return EngineData(
            rpm = rpm,
            ectC = ect,
            iatC = 31f + (if (wot) 2f else 0f),
            mapMbar = map,
            baroMbar = 1013,
            tpsPct = tps,
            o2Volt = o2v,
            afr = afr,
            ethanolPct = 78f,
            batteryV = 14.1f + 0.1f * sin(t).toFloat(),
            ignDeg = if (wot) 22f + rpm / 1000f else 30f,
            injMs = inj,
            dutyPct = (rpm * inj / 1200f).coerceIn(0f, 100f),
            vssKmh = (rpm / 60).coerceAtMost(140),
            vtec = false,
            fan = ect > 87f,
            mil = false,
            stftPct = 1.5f * sin(t * 1.3).toFloat(),
            ltftPct = -2.0f,
            raw = ByteArray(0),
        )
    }
}
