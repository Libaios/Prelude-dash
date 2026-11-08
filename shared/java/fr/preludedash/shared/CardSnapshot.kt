package fr.preludedash.shared

import android.os.Bundle

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
    /** Ligne de titre Android Auto : alerte, sinon le morceau en cours. */
    val title: String,
    /** Ligne de sous-titre : l'artiste, sinon l'état de la liaison. */
    val subtitle: String,
    // Valeurs des bandes latérales (visibles seulement en plein écran)
    val rpm: String = "--",
    val map: String = "--",
    val iat: String = "--",
    val batt: String = "--",
) {
    enum class Level { NORMAL, COLD, WARN, ALERT }

    fun toBundle(): Bundle = Bundle().apply {
        putBoolean("live", live)
        putBoolean("demo", demo)
        putString("afr", afr)
        putString("afrLevel", afrLevel.name)
        putString("ect", ect)
        putString("ectLevel", ectLevel.name)
        putString("eth", eth)
        putString("alert", alert)
        putString("title", title)
        putString("subtitle", subtitle)
        putString("rpm", rpm)
        putString("map", map)
        putString("iat", iat)
        putString("batt", batt)
    }

    companion object {
        /** Carte vide avec un message, quand il n'y a pas de données. */
        fun offline(title: String, subtitle: String) = CardSnapshot(
            live = false, demo = false,
            afr = "--.-", afrLevel = Level.NORMAL,
            ect = "--", ectLevel = Level.NORMAL,
            eth = "--", alert = null,
            title = title, subtitle = subtitle,
        )

        fun fromBundle(b: Bundle): CardSnapshot = CardSnapshot(
            live = b.getBoolean("live"),
            demo = b.getBoolean("demo"),
            afr = b.getString("afr") ?: "--.-",
            afrLevel = level(b.getString("afrLevel")),
            ect = b.getString("ect") ?: "--",
            ectLevel = level(b.getString("ectLevel")),
            eth = b.getString("eth") ?: "--",
            alert = b.getString("alert"),
            title = b.getString("title") ?: "Prelude Dash",
            subtitle = b.getString("subtitle") ?: "",
            rpm = b.getString("rpm") ?: "--",
            map = b.getString("map") ?: "--",
            iat = b.getString("iat") ?: "--",
            batt = b.getString("batt") ?: "--",
        )

        private fun level(s: String?): Level = Level.values().firstOrNull { it.name == s } ?: Level.NORMAL
    }
}
