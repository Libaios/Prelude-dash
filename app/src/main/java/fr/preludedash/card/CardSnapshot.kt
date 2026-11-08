package fr.preludedash.card

import fr.preludedash.data.Calibration
import fr.preludedash.data.EngineData
import fr.preludedash.link.DataHub
import fr.preludedash.music.MusicRelay
import fr.preludedash.shared.CardSnapshot
import fr.preludedash.shared.CardSnapshot.Level
import java.util.Locale
import kotlin.math.roundToInt

/** Construit la carte à partir des données moteur : 3 valeurs et l'alerte la plus grave. */
object CardSnapshots {
    fun from(
        d: EngineData,
        state: DataHub.State,
        ageMs: Long,
        cal: Calibration,
        music: String? = MusicRelay.line(),
    ): CardSnapshot {
        val demo = state == DataHub.State.DEMO
        val live = demo || (state == DataHub.State.CONNECTED && ageMs < 1500)
        if (!live) return CardSnapshot.offline("Prelude Dash", music ?: "ECU non connecté")

        val afrV = d.afr
        val lean = afrV != null && afrV > 15.0f && d.tpsPct > 60
        val afrText = afrV?.let { String.format(Locale.US, "%.1f", it) } ?: "--.-"
        val ect = d.ectC.roundToInt()
        val eth = d.ethanolPct?.roundToInt()

        // Une seule alerte à la fois, la plus grave d'abord
        val alert = when {
            lean -> "PAUVRE"
            ect > 105 -> "EAU $ect°C"
            d.mil -> "VOYANT MOTEUR"
            d.batteryV < 12.0f || d.batteryV > 15.2f ->
                "BATT " + String.format(Locale.US, "%.1f", d.batteryV) + " V"
            d.dutyPct > 90f -> "INJ ${d.dutyPct.roundToInt()} %"
            else -> null
        }
        val ectLevel = when {
            ect > 105 -> Level.ALERT
            ect > 100 -> Level.WARN
            ect < 60 -> Level.COLD
            else -> Level.NORMAL
        }
        val ethText = eth?.toString() ?: "--"
        return CardSnapshot(
            live = true,
            demo = demo,
            afr = afrText,
            afrLevel = if (lean) Level.ALERT else Level.NORMAL,
            ect = ect.toString(),
            ectLevel = ectLevel,
            eth = ethText,
            alert = alert,
            // Une alerte passe devant tout, même dans le texte de secours
            title = if (alert != null) "⚠ $alert   AFR $afrText" else "AFR $afrText   $ect°C   E$ethText",
            subtitle = music ?: (alert ?: if (demo) "Mode démo" else "ECU connecté"),
        )
    }
}
