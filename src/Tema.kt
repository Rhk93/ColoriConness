package com.ps5ftp.manager

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File

/** Tutte le impostazioni dell'aspetto dell'app (colori, sfondo, carattere). */
class Tema(private val ctx: Context) {

    companion object {
        const val CUSTOM = 9

        val FAMILIES = arrayOf(
            "sans-serif", "sans-serif-light", "sans-serif-medium", "sans-serif-condensed",
            "serif", "monospace", "casual", "cursive", "sans-serif-smallcaps"
        )
        val FONT_LABELS = arrayOf(
            "Standard", "Sottile", "Medio", "Stretto", "Con grazie (serif)",
            "Macchina da scrivere", "Informale", "Corsivo", "Maiuscoletto",
            "Personalizzato (file .ttf / .otf)"
        )
        val PRESETS = arrayOf("Scuro", "PlayStation", "Neon", "Tramonto", "Vetro", "Chiaro")
        val DIREZIONI = arrayOf("↓ Verticale", "→ Orizzontale", "↘ Diagonale", "↙ Diagonale")

        fun c(v: Long): Int = v.toInt()
    }

    private val prefs = ctx.getSharedPreferences("tema", Context.MODE_PRIVATE)

    // Sfondo: 0 tinta unita, 1 sfumato, 2 immagine
    var bgType = 1
    var bg1 = 0
    var bg2 = 0
    var bgDir = 0
    var overlay = 0

    // Riquadri (righe dei file, intestazione)
    var panel1 = 0
    var panel2 = 0
    var panelGrad = false

    // Pulsanti
    var btn1 = 0
    var btn2 = 0
    var btnGrad = false
    var btnText = 0

    // Testi
    var text = 0
    var folder = 0
    var file = 0
    var accent = 0

    // Carattere
    var font = 0
    var fontScale = 1f
    var bold = false
    var radius = 14

    private var tfCache: Typeface? = null

    init {
        applyPreset(1)
        load()
    }

    private fun load() {
        if (!prefs.contains("bg1")) return
        bgType = prefs.getInt("bgType", bgType)
        bg1 = prefs.getInt("bg1", bg1)
        bg2 = prefs.getInt("bg2", bg2)
        bgDir = prefs.getInt("bgDir", bgDir)
        overlay = prefs.getInt("overlay", overlay)
        panel1 = prefs.getInt("panel1", panel1)
        panel2 = prefs.getInt("panel2", panel2)
        panelGrad = prefs.getBoolean("panelGrad", panelGrad)
        btn1 = prefs.getInt("btn1", btn1)
        btn2 = prefs.getInt("btn2", btn2)
        btnGrad = prefs.getBoolean("btnGrad", btnGrad)
        btnText = prefs.getInt("btnText", btnText)
        text = prefs.getInt("text", text)
        folder = prefs.getInt("folder", folder)
        file = prefs.getInt("file", file)
        accent = prefs.getInt("accent", accent)
        font = prefs.getInt("font", font)
        fontScale = prefs.getFloat("fontScale", fontScale)
        bold = prefs.getBoolean("bold", bold)
        radius = prefs.getInt("radius", radius)
    }

    fun save() {
        prefs.edit()
            .putInt("bgType", bgType).putInt("bg1", bg1).putInt("bg2", bg2)
            .putInt("bgDir", bgDir).putInt("overlay", overlay)
            .putInt("panel1", panel1).putInt("panel2", panel2).putBoolean("panelGrad", panelGrad)
            .putInt("btn1", btn1).putInt("btn2", btn2).putBoolean("btnGrad", btnGrad)
            .putInt("btnText", btnText)
            .putInt("text", text).putInt("folder", folder).putInt("file", file).putInt("accent", accent)
            .putInt("font", font).putFloat("fontScale", fontScale).putBoolean("bold", bold)
            .putInt("radius", radius)
            .apply()
        tfCache = null
    }

    fun resetAll() {
        applyPreset(1)
        font = 0
        fontScale = 1f
        bold = false
        radius = 14
    }

    fun applyPreset(i: Int) {
        when (i) {
            0 -> set(0, c(0xFF121212), c(0xFF121212), 0, c(0x99000000),
                c(0xFF1E1E1E), c(0xFF1E1E1E), false,
                c(0xFF2E2E2E), c(0xFF2E2E2E), false, c(0xFFFFFFFF),
                c(0xFFFFFFFF), c(0xFFFFC107), c(0xFFE0E0E0), c(0xFF64B5F6))
            1 -> set(1, c(0xFF00439C), c(0xFF000814), 0, c(0x88000000),
                c(0x33FFFFFF), c(0x14FFFFFF), true,
                c(0xFF0070D1), c(0xFF00439C), true, c(0xFFFFFFFF),
                c(0xFFFFFFFF), c(0xFF8FD3FF), c(0xFFFFFFFF), c(0xFF00B4FF))
            2 -> set(1, c(0xFF12002B), c(0xFF000000), 2, c(0x88000000),
                c(0x2600FFC8), c(0x26FF00C8), true,
                c(0xFFFF00C8), c(0xFF7A00FF), true, c(0xFFFFFFFF),
                c(0xFFF5E6FF), c(0xFF00FFC8), c(0xFFFFFFFF), c(0xFFFF4FD8))
            3 -> set(1, c(0xFFFF7E5F), c(0xFF2C1A4D), 0, c(0x88000000),
                c(0x40000000), c(0x40000000), false,
                c(0xFFFFB347), c(0xFFFF5E62), true, c(0xFFFFFFFF),
                c(0xFFFFFFFF), c(0xFFFFE082), c(0xFFFFFFFF), c(0xFFFFD180))
            4 -> set(1, c(0xFF1D2B64), c(0xFF0F9B8E), 2, c(0x66000000),
                c(0x26FFFFFF), c(0x26FFFFFF), false,
                c(0x33FFFFFF), c(0x33FFFFFF), false, c(0xFFFFFFFF),
                c(0xFFFFFFFF), c(0xFFB2FFF4), c(0xFFFFFFFF), c(0xFFFFFFFF))
            else -> set(0, c(0xFFF2F3F7), c(0xFFF2F3F7), 0, c(0x55FFFFFF),
                c(0xFFFFFFFF), c(0xFFFFFFFF), false,
                c(0xFF1976D2), c(0xFF42A5F5), false, c(0xFFFFFFFF),
                c(0xFF202124), c(0xFF1565C0), c(0xFF202124), c(0xFF1976D2))
        }
    }

    private fun set(
        bgT: Int, b1: Int, b2: Int, dir: Int, ov: Int,
        p1: Int, p2: Int, pg: Boolean,
        bt1: Int, bt2: Int, bg: Boolean, btT: Int,
        t: Int, fo: Int, fi: Int, ac: Int
    ) {
        bgType = bgT; bg1 = b1; bg2 = b2; bgDir = dir; overlay = ov
        panel1 = p1; panel2 = p2; panelGrad = pg
        btn1 = bt1; btn2 = bt2; btnGrad = bg; btnText = btT
        text = t; folder = fo; file = fi; accent = ac
    }

    // ------------------------------------------------------------ carattere

    fun fontFile() = File(ctx.filesDir, "font_personalizzato")
    fun bgFile() = File(ctx.filesDir, "sfondo.jpg")

    fun resetFont() {
        tfCache = null
    }

    fun customTypeface(): Typeface? = try {
        val f = fontFile()
        if (f.exists()) Typeface.createFromFile(f) else null
    } catch (e: Exception) {
        null
    }

    private fun baseTypeface(): Typeface {
        tfCache?.let { return it }
        val t = if (font == CUSTOM) {
            customTypeface() ?: Typeface.DEFAULT
        } else {
            Typeface.create(FAMILIES[font.coerceIn(0, FAMILIES.size - 1)], Typeface.NORMAL)
        }
        tfCache = t
        return t
    }

    fun typeface(forceBold: Boolean = false): Typeface {
        val base = baseTypeface()
        return if (forceBold || bold) Typeface.create(base, Typeface.BOLD) else base
    }

    // ------------------------------------------------------------ disegni

    fun orient(d: Int): GradientDrawable.Orientation = when (d) {
        1 -> GradientDrawable.Orientation.LEFT_RIGHT
        2 -> GradientDrawable.Orientation.TL_BR
        3 -> GradientDrawable.Orientation.TR_BL
        else -> GradientDrawable.Orientation.TOP_BOTTOM
    }

    fun drawable(c1: Int, c2: Int, grad: Boolean, r: Float, dir: Int): GradientDrawable =
        if (grad) {
            GradientDrawable(orient(dir), intArrayOf(c1, c2)).apply { cornerRadius = r }
        } else {
            GradientDrawable().apply { setColor(c1); cornerRadius = r }
        }

    fun panelDrawable(density: Float, rounded: Boolean = true) =
        drawable(panel1, panel2, panelGrad, if (rounded) radius * density else 0f, 1)

    fun btnDrawable(density: Float) = drawable(btn1, btn2, btnGrad, radius * density, 1)
}

/** Finestra per scegliere un colore: tonalità, saturazione, luminosità, opacità, colori rapidi e codice. */
fun scegliColore(a: Activity, titolo: String, iniziale: Int, onOk: (Int) -> Unit) {
    val dens = a.resources.displayMetrics.density
    fun dp(v: Int) = (v * dens).toInt()

    val hsv = FloatArray(3)
    Color.colorToHSV(iniziale, hsv)
    var alpha = Color.alpha(iniziale)
    var updating = false

    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }

    // Anteprima su nero e su bianco, così si vede quanto è trasparente
    val onBlack = View(a)
    val onWhite = View(a)
    val prevRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    prevRow.addView(FrameLayout(a).apply {
        setBackgroundColor(Color.BLACK)
        addView(onBlack, FrameLayout.LayoutParams(-1, -1))
    }, LinearLayout.LayoutParams(0, dp(56), 1f))
    prevRow.addView(FrameLayout(a).apply {
        setBackgroundColor(Color.WHITE)
        addView(onWhite, FrameLayout.LayoutParams(-1, -1))
    }, LinearLayout.LayoutParams(0, dp(56), 1f))
    root.addView(prevRow)

    val hex = EditText(a).apply {
        setSingleLine()
        typeface = Typeface.MONOSPACE
        textSize = 15f
    }
    val seeks = ArrayList<SeekBar>()

    fun current() = Color.HSVToColor(alpha, hsv)

    fun refresh(updateHex: Boolean, updateSeeks: Boolean) {
        val col = current()
        onBlack.setBackgroundColor(col)
        onWhite.setBackgroundColor(col)
        updating = true
        if (updateHex) hex.setText(String.format("#%08X", col))
        if (updateSeeks && seeks.size == 4) {
            seeks[0].progress = hsv[0].toInt()
            seeks[1].progress = (hsv[1] * 100).toInt()
            seeks[2].progress = (hsv[2] * 100).toInt()
            seeks[3].progress = alpha * 100 / 255
        }
        updating = false
    }

    val names = arrayOf("Tonalità", "Saturazione", "Luminosità", "Opacità (a sinistra = trasparente)")
    val maxes = intArrayOf(359, 100, 100, 100)
    for (i in 0..3) {
        root.addView(TextView(a).apply {
            text = names[i]
            setPadding(0, dp(10), 0, 0)
        })
        val sb = SeekBar(a)
        sb.max = maxes[i]
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (updating) return
                when (i) {
                    0 -> hsv[0] = p.toFloat()
                    1 -> hsv[1] = p / 100f
                    2 -> hsv[2] = p / 100f
                    else -> alpha = p * 255 / 100
                }
                refresh(true, false)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        seeks.add(sb)
        root.addView(sb)
    }

    root.addView(TextView(a).apply {
        text = "Colori rapidi"
        setPadding(0, dp(14), 0, dp(4))
    })
    val pal = longArrayOf(
        0xFFFFFFFF, 0xFF000000, 0xFF9E9E9E, 0xFF00439C, 0xFF00B4FF,
        0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5,
        0xFF2196F3, 0xFF00BCD4, 0xFF009688, 0xFF4CAF50, 0xFF8BC34A,
        0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFFFF5722, 0xFF795548
    )
    var rowL: LinearLayout? = null
    pal.forEachIndexed { idx, v ->
        if (idx % 5 == 0) {
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            root.addView(r)
            rowL = r
        }
        val col = v.toInt()
        val sw = View(a).apply {
            background = GradientDrawable().apply {
                setColor(col)
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.GRAY)
            }
            setOnClickListener {
                Color.colorToHSV(col, hsv)
                refresh(true, true)
            }
        }
        rowL?.addView(sw, LinearLayout.LayoutParams(0, dp(34), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
    }

    root.addView(TextView(a).apply {
        text = "Codice colore #AARRGGBB (AA = opacità: FF piena, 80 metà, 00 invisibile)"
        setPadding(0, dp(14), 0, 0)
    })
    root.addView(hex)
    hex.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (updating) return
            try {
                val col = Color.parseColor(s.toString().trim())
                Color.colorToHSV(col, hsv)
                alpha = Color.alpha(col)
                refresh(false, true)
            } catch (e: Exception) {
                // codice non ancora completo: aspetto
            }
        }
    })

    refresh(true, true)

    AlertDialog.Builder(a)
        .setTitle(titolo)
        .setView(ScrollView(a).apply { addView(root) })
        .setPositiveButton("OK") { _, _ -> onOk(current()) }
        .setNegativeButton("Annulla", null)
        .show()
}
