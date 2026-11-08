package fr.preludedash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import fr.preludedash.data.CalStore
import fr.preludedash.data.Calibration
import fr.preludedash.data.IsrDecoder
import fr.preludedash.link.DataHub
import fr.preludedash.link.LinkManager
import fr.preludedash.ui.DashRenderer
import fr.preludedash.ui.DashView
import java.util.Locale

/**
 * Écran téléphone : choix du module Bluetooth, aperçu du tableau de bord,
 * réglages (AFR, éthanol...) et trame brute pour trouver les bons octets.
 */
class MainActivity : Activity() {

    private lateinit var dash: DashView
    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var demoButton: Button
    private lateinit var rawText: TextView
    private lateinit var candidatesBox: LinearLayout
    private lateinit var ethScaleGroup: RadioGroup
    private val fields = mutableMapOf<String, EditText>()

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateTexts()
            ui.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        askPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        LinkManager.acquire(this, "phone")
        ui.post(refresh)
    }

    override fun onStop() {
        ui.removeCallbacks(refresh)
        LinkManager.release("phone")
        super.onStop()
    }

    // ------------------------------------------------------------ permissions

    private fun hasBtPermission() = LinkManager.hasBtPermission(this)

    private fun askPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31 && !hasBtPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        LinkManager.restart(this)
    }

    // ----------------------------------------------------------------- écran

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(DashRenderer.BG)
            clipToPadding = false
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        val pad = dp(16)
        col.setPadding(pad, pad, pad, pad)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }

        col.addView(text("Prelude Dash", 26f, DashRenderer.TEXT, bold = true))
        statusText = text("", 14f, DashRenderer.MUTED)
        col.addView(statusText)

        dash = DashView(this)
        col.addView(dash, lp(top = 12))

        demoButton = button("") { toggleDemo() }
        col.addView(row(button("Page suivante") { dash.page = (dash.page + 1) % DashRenderer.PAGES }, demoButton))

        // Module Bluetooth
        col.addView(section("Module Bluetooth de l'ECU"))
        deviceText = text("", 15f, DashRenderer.TEXT)
        col.addView(deviceText)
        col.addView(row(button("Choisir le module") { pickDevice() }, button("Reconnecter") { LinkManager.restart(this) }))

        // Réglages
        val cal = CalStore.get(this)
        col.addView(section("Réglages"))
        col.addView(hint("Les valeurs par défaut collent à ta config : wideband AEM sur D14, capteur MAP d'origine."))
        col.addView(field("afrByte", "Octet de la wideband (D14 = 2)", cal.afrByte.toString(), decimal = false))
        col.addView(row(
            field("afrV1", "Tension basse (V)", fmt(cal.afrV1), decimal = true),
            field("afrA1", "AFR à cette tension", fmt(cal.afrA1), decimal = true),
        ))
        col.addView(row(
            field("afrV2", "Tension haute (V)", fmt(cal.afrV2), decimal = true),
            field("afrA2", "AFR à cette tension", fmt(cal.afrA2), decimal = true),
        ))
        col.addView(field("ethByte", "Octet éthanol (-1 = pas réglé)", cal.ethByte.toString(), decimal = false, signed = true))
        ethScaleGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf(Calibration.ETH_PERCENT, Calibration.ETH_255, Calibration.ETH_HALF).forEach { scale ->
            ethScaleGroup.addView(RadioButton(this).apply {
                id = 1000 + scale
                text = Calibration.scaleName(scale)
                setTextColor(DashRenderer.TEXT)
            })
        }
        ethScaleGroup.check(1000 + cal.ethScale)
        col.addView(ethScaleGroup)
        col.addView(row(
            field("redline", "Zone rouge (tr/min)", cal.redline.toString(), decimal = false),
            field("frameLen", "Taille de trame", cal.frameLen.toString(), decimal = false),
        ))
        col.addView(row(
            field("mapMin", "MAP à 0 V (mbar)", cal.mapMin.toString(), decimal = false, signed = true),
            field("mapMax", "MAP à 5 V (mbar)", cal.mapMax.toString(), decimal = false),
        ))
        col.addView(button("Enregistrer les réglages") { saveSettings() }, lp(top = 8))

        // Trouver l'éthanol
        col.addView(section("Trouver l'octet éthanol"))
        col.addView(hint(
            "Moteur tournant et ECU connecté, tape le % d'éthanol affiché dans HTS, puis Chercher. " +
                "Si plusieurs octets sortent, garde celui qui ne bouge pas quand tu donnes un coup de gaz.",
        ))
        val ethInput = EditText(this).apply {
            hint = "ex : 78"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(DashRenderer.TEXT)
            setHintTextColor(DashRenderer.MUTED)
        }
        col.addView(row(ethInput, button("Chercher") { findEthanol(ethInput.text.toString()) }))
        candidatesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(candidatesBox)

        // Trame brute
        col.addView(section("Trame brute (octet : valeur)"))
        rawText = text("", 13f, DashRenderer.TEXT).apply { typeface = Typeface.MONOSPACE }
        col.addView(rawText)

        col.addView(section("Android Auto"))
        col.addView(hint(
            "Dans la voiture, ouvre Prelude Dash depuis le lanceur Android Auto. " +
                "Le bouton Détails en haut à droite change de page.",
        ))
        return scroll
    }

    private fun updateTexts() {
        val cal = CalStore.get(this)
        val st = when (DataHub.state) {
            DataHub.State.CONNECTED -> "${DataHub.message}   ${String.format(Locale.US, "%.0f", DataHub.hz)} trames/s"
            else -> DataHub.message
        }
        statusText.text = st
        deviceText.text = when {
            cal.demo -> "Mode démo actif (aucune liaison avec l'ECU)"
            cal.deviceAddress == null -> "Aucun module choisi"
            else -> "${cal.deviceName ?: "Module"}  (${cal.deviceAddress})"
        }
        demoButton.text = if (cal.demo) "Arrêter la démo" else "Mode démo"

        val raw = DataHub.data.raw
        rawText.text = if (raw.isEmpty()) {
            "Pas encore de trame reçue." + if (DataHub.lastFrameLen > 0) " Dernière réponse : ${DataHub.lastFrameLen} octets." else ""
        } else {
            val sb = StringBuilder()
            for (i in raw.indices) {
                sb.append(String.format(Locale.US, "%2d:%4d   ", i, IsrDecoder.u8(raw, i)))
                if (i % 4 == 3) sb.append('\n')
            }
            sb.append("\n${raw.size} octets reçus")
            sb.toString()
        }
    }

    // --------------------------------------------------------------- actions

    private fun toggleDemo() {
        val cal = CalStore.get(this)
        CalStore.save(this, cal.copy(demo = !cal.demo))
        LinkManager.restart(this)
        updateTexts()
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        if (!hasBtPermission()) {
            askPermissionIfNeeded()
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            toast("Pas de Bluetooth sur ce téléphone")
            return
        }
        if (!adapter.isEnabled) {
            toast("Active le Bluetooth du téléphone")
            return
        }
        val devices = adapter.bondedDevices.toList().sortedBy { it.name ?: "" }
        if (devices.isEmpty()) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Aucun appareil appairé")
                .setMessage(
                    "Contact mis, appaire d'abord le module dans les réglages Bluetooth du téléphone " +
                        "(code 1234), puis reviens ici.",
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val names = devices.map { "${it.name ?: "Sans nom"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Module Bluetooth de l'ECU")
            .setItems(names) { _, i ->
                val d = devices[i]
                CalStore.save(this, CalStore.get(this).copy(deviceAddress = d.address, deviceName = d.name, demo = false))
                LinkManager.restart(this)
                updateTexts()
            }
            .show()
    }

    private fun saveSettings() {
        val c = CalStore.get(this)
        val scaleId = ethScaleGroup.checkedRadioButtonId
        val updated = c.copy(
            afrByte = int("afrByte", c.afrByte).coerceIn(-1, 255),
            afrV1 = float("afrV1", c.afrV1),
            afrA1 = float("afrA1", c.afrA1),
            afrV2 = float("afrV2", c.afrV2),
            afrA2 = float("afrA2", c.afrA2),
            ethByte = int("ethByte", c.ethByte).coerceIn(-1, 255),
            ethScale = if (scaleId >= 1000) scaleId - 1000 else c.ethScale,
            redline = int("redline", c.redline).coerceIn(3000, 11000),
            frameLen = int("frameLen", c.frameLen).coerceIn(8, 255),
            mapMin = int("mapMin", c.mapMin),
            mapMax = int("mapMax", c.mapMax),
        )
        CalStore.save(this, updated)
        toast("Réglages enregistrés")
    }

    private fun findEthanol(input: String) {
        val target = input.replace(',', '.').trim().toFloatOrNull()
        if (target == null) {
            toast("Tape le % d'éthanol affiché dans HTS")
            return
        }
        val raw = DataHub.data.raw
        candidatesBox.removeAllViews()
        if (raw.isEmpty()) {
            candidatesBox.addView(hint("Pas de trame reçue : connecte d'abord l'ECU (contact mis)."))
            return
        }
        val found = IsrDecoder.findEthanolCandidates(raw, target)
        if (found.isEmpty()) {
            candidatesBox.addView(hint("Aucun octet ne correspond. Vérifie que la sortie éthanol vers le datalog est activée dans CobraRTP Utility."))
            return
        }
        found.take(15).forEach { (index, scale) ->
            val v = IsrDecoder.u8(raw, index)
            candidatesBox.addView(button("Octet $index = $v  (${Calibration.scaleName(scale)})") {
                CalStore.save(this, CalStore.get(this).copy(ethByte = index, ethScale = scale))
                fields["ethByte"]?.setText(index.toString())
                ethScaleGroup.check(1000 + scale)
                toast("Éthanol réglé sur l'octet $index")
            }, lp(top = 4))
        }
    }

    // ---------------------------------------------------------------- outils

    private fun int(key: String, def: Int): Int = fields[key]?.text?.toString()?.trim()?.toIntOrNull() ?: def

    private fun float(key: String, def: Float): Float =
        fields[key]?.text?.toString()?.replace(',', '.')?.trim()?.toFloatOrNull() ?: def

    private fun fmt(v: Float) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private fun hint(s: String) = text(s, 13f, DashRenderer.MUTED).apply { setPadding(0, dp(4), 0, dp(4)) }

    private fun section(title: String) = text(title.uppercase(Locale.FRENCH), 13f, DashRenderer.TEAL, bold = true).apply {
        letterSpacing = 0.1f
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(DashRenderer.TEXT)
        background = GradientDrawable().apply {
            setColor(DashRenderer.PANEL)
            cornerRadius = dp(10).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            p.topMargin = dp(8)
            if (i > 0) p.leftMargin = dp(8)
            addView(v, p)
        }
    }

    private fun field(key: String, label: String, value: String, decimal: Boolean, signed: Boolean = false): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(label, 12f, DashRenderer.MUTED))
        val edit = EditText(this).apply {
            setText(value)
            setTextColor(DashRenderer.TEXT)
            var type = InputType.TYPE_CLASS_NUMBER
            if (decimal) type = type or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (signed) type = type or InputType.TYPE_NUMBER_FLAG_SIGNED
            inputType = type
        }
        fields[key] = edit
        box.addView(edit)
        return box
    }
}
