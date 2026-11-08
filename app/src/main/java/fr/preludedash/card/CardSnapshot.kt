package fr.preludedash.card

import fr.preludedash.data.Calibration
import fr.preludedash.data.EngineData
import fr.preludedash.link.DataHub
import fr.preludedash.music.MusicRelay
import java.util.Locale
import kotlin.math.roundToInt

/** Ce que montre la petite carte : 3 valeurs et une alerte, déjà arrondies. */
data class CardSnapshot(
    val live: Boolean,
    val demo: Boolean,
    val afr: String,
    val afrLevel: Level,
    val ect: String,
    val ectLevel: Level,
    val eth: String,
    /** Alerte prioritaire (null si tout va bien). */
    val alert: String?,
    /** Ligne de titre Android Auto : les valeurs, lisibles même sans l'image. */
    val title: String,
    /** Ligne de sous-titre : la musique en cours, sinon l'état de la liaison. */
    val subtitle: String,
) {
    enum class Level { NORMAL, COLD, WARN, ALERT }

    companion object {
        fun from(
            d: EngineData,
            state: DataHub.State,
            ageMs: Long,
            cal: Calibration,
            music: String? = MusicRelay.line(),
        ): CardSnapshot {
            val demo = state == DataHub.State.DEMO
            val live = demo || (state == DataHub.State.CONNECTED && ageMs < 1500)
            if (!live) {
                return CardSnapshot(
                    live = false, demo = false,
                    afr = "--.-", afrLevel = Level.NORMAL,
                    ect = "--", ectLevel = Level.NORMAL,
                    eth = "--", alert = null,
                    title = "Prelude Dash",
                    subtitle = music ?: "ECU non connecté",
                )
            }
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
}

/** Les dernières images publiées, retrouvées par numéro quand Android Auto vient les chercher. */
object CardFrames {
    private const val KEEP = 32
    private val frames = LinkedHashMap<Int, CardSnapshot>()

    @Synchronized
    fun publish(id: Int, s: CardSnapshot) {
        frames[id] = s
        while (frames.size > KEEP) frames.remove(frames.keys.first())
    }

    @Synchronized
    fun get(id: Int): CardSnapshot? = frames[id]

    @Synchronized
    fun clear() = frames.clear()
}
