package com.ps5ftp.manager

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPClientConfig
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.io.CopyStreamEvent
import org.apache.commons.net.io.CopyStreamListener
import java.io.FileOutputStream
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class CancelledException : RuntimeException("Annullato")

class MainActivity : Activity() {

    data class Entry(
        val name: String,
        val isDir: Boolean,
        val isLink: Boolean,
        val size: Long,
        val path: String
    )

    private data class Doc(val id: String, val name: String, val mime: String, val size: Long)

    companion object {
        private const val REQ_FILES = 1
        private const val REQ_TREE = 2
        private const val REQ_DOWNLOAD = 3
        private const val REQ_BG = 4
        private const val REQ_FONT = 5
        private const val SORT_NAME = 0
        private const val SORT_SIZE_DESC = 1
        private const val SORT_SIZE_DESC_DIRS_FIRST = 2
        private const val SORT_SIZE_ASC = 3
        private val SORT_LABELS = arrayOf(
            "Nome (cartelle prima)",
            "Dimensione: dal più grande (tutto insieme)",
            "Dimensione: dal più grande (cartelle prima)",
            "Dimensione: dal più piccolo (tutto insieme)"
        )
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private val io = Executors.newSingleThreadExecutor()
    private var client: FTPClient? = null
    private var host = ""
    private var port = 1337
    @Volatile private var currentPath = "/"
    private val entries = ArrayList<Entry>()
    private val sizeCache = ConcurrentHashMap<String, Long>()
    private var clip: Entry? = null
    private var clipMove = false
    private var pendingDownload: Entry? = null
    private var inBrowser = false
    @Volatile private var cancel = false
    @Volatile private var busy = false
    @Volatile private var scanned = 0
    @Volatile private var lastPost = 0L
    private var sortMode = SORT_NAME

    private lateinit var tema: Tema
    private var screen = 0 // 0 connessione, 1 file, 2 aspetto
    private var themeFrom = 0
    private var themeScrollY = 0
    private var themeScroll: ScrollView? = null
    @Volatile private var bgCache: Bitmap? = null

    private lateinit var pathText: TextView
    private lateinit var statusText: TextView
    private lateinit var listView: ListView
    private lateinit var pasteBtn: Button
    private lateinit var stopBtn: Button
    private val adapter = EntryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tema = Tema(this)
        sortMode = getSharedPreferences("ps5ftp", MODE_PRIVATE).getInt("sort", SORT_NAME)
        showConnectScreen()
    }

    override fun onDestroy() {
        cancel = true
        val c = client
        io.execute { c?.let { safeDisconnect(it) } }
        io.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- schermata connessione

    private fun showConnectScreen() {
        screen = 0
        inBrowser = false
        val prefs = getSharedPreferences("ps5ftp", MODE_PRIVATE)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(24))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(tv("PS5 FTP Manager", 24f, tema.accent, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(btn("🎨 Aspetto") { openTheme() })
        col.addView(top)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = tema.panelDrawable(density())
        }
        card.addView(tv("Connetti alla PS5", 19f, tema.text, true))
        card.addView(tv(
            "Avvia prima il server FTP sulla PS5 (es. etaHEN), poi inserisci l'IP e la porta che ti mostra.",
            13f, tema.text
        ).apply {
            alpha = 0.75f
            setPadding(0, dp(6), 0, dp(4))
        })
        val ipEdit = EditText(this).apply {
            hint = "es. 192.168.1.50"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(prefs.getString("ip", ""))
        }
        styleEdit(ipEdit)
        val portEdit = EditText(this).apply {
            hint = "es. 1337"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText(prefs.getString("port", "1337"))
        }
        styleEdit(portEdit)
        card.addView(tv("Indirizzo IP", 13f, tema.text).apply { setPadding(0, dp(12), 0, 0) })
        card.addView(ipEdit)
        card.addView(tv("Porta", 13f, tema.text).apply { setPadding(0, dp(12), 0, 0) })
        card.addView(portEdit)
        val connectBtn = btn("Connetti") {}
        card.addView(connectBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })
        val st = tv("", 13f, tema.accent).apply { setPadding(0, dp(14), 0, 0) }
        card.addView(st)
        col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) })

        connectBtn.setOnClickListener {
            val ip = ipEdit.text.toString().trim()
            val p = portEdit.text.toString().trim().toIntOrNull()
            if (ip.isEmpty() || p == null || p !in 1..65535) {
                st.text = "Inserisci un IP e una porta validi"
                return@setOnClickListener
            }
            prefs.edit().putString("ip", ip).putString("port", p.toString()).apply()
            host = ip
            port = p
            connect(connectBtn, st)
        }
        setContentView(themed(ScrollView(this).apply { addView(col) }))
    }

    private fun connect(connectBtn: Button, st: TextView) {
        connectBtn.isEnabled = false
        st.text = "Connessione a $host:$port..."
        io.execute {
            try {
                client?.let { safeDisconnect(it) }
                client = newClient()
                runOnUiThread {
                    sizeCache.clear()
                    clip = null
                    currentPath = "/"
                    showBrowser()
                    navigate("/")
                }
            } catch (e: Exception) {
                client = null
                runOnUiThread {
                    connectBtn.isEnabled = true
                    st.text = "Connessione fallita: ${e.message ?: e.javaClass.simpleName}\n\n" +
                        "Controlla che PS5 e telefono siano sulla stessa rete Wi-Fi e che il server FTP sia attivo."
                }
            }
        }
    }

    // ---------------------------------------------------------------- schermata file

    private fun showBrowser() {
        screen = 1
        inBrowser = true
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = tema.panelDrawable(density(), rounded = false)
            setPadding(0, dp(6), 0, dp(4))
        }
        header.addView(tv("PS5  $host:$port", 12f, tema.text).apply {
            alpha = 0.7f
            setPadding(dp(12), 0, dp(12), 0)
        })
        pathText = tv("", 16f, tema.accent, true).apply { setPadding(dp(12), dp(2), dp(12), dp(4)) }
        header.addView(pathText)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        bar.addView(btn("⬆ Su") { goUp() })
        bar.addView(btn("⟳ Aggiorna") { navigate(currentPath) })
        bar.addView(btn("📏 Dimensioni") { calcAllSizes() })
        bar.addView(btn("↕ Ordina") { sortDialog() })
        bar.addView(btn("📤 Carica file") { pickFiles() })
        bar.addView(btn("📂 Carica cartella") { pickTree() })
        bar.addView(btn("➕ Nuova cartella") { newFolderDialog() })
        pasteBtn = btn("📋 Incolla") { paste() }
        pasteBtn.setOnLongClickListener {
            clip = null
            updatePaste()
            toast("Appunti svuotati")
            true
        }
        bar.addView(pasteBtn)
        bar.addView(btn("🎨 Aspetto") { openTheme() })
        bar.addView(btn("🔌 Disconnetti") { disconnect() })
        header.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        })

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(6), 0)
        }
        statusText = tv("", 12f, tema.text)
        statusRow.addView(statusText, LinearLayout.LayoutParams(0, WRAP, 1f))
        stopBtn = btn("■ Stop") {
            cancel = true
            statusText.text = "Annullamento..."
        }
        stopBtn.visibility = View.GONE
        statusRow.addView(stopBtn)
        header.addView(statusRow)
        root.addView(header)

        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null
            dividerHeight = 0
            cacheColorHint = Color.TRANSPARENT
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, dp(4), 0, dp(12))
            clipToPadding = false
        }
        listView.setOnItemClickListener { _, _, pos, _ ->
            val e = entries[pos]
            if (e.isDir) navigate(e.path) else showOptions(e)
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            showOptions(entries[pos])
            true
        }
        root.addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(themed(root))
        updatePaste()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen == 2) {
            closeTheme()
            return
        }
        if (inBrowser) {
            if (busy) {
                toast("Operazione in corso: premi Stop per annullarla")
                return
            }
            if (currentPath != "/") {
                navigate(parent(currentPath))
                return
            }
            disconnect()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun goUp() {
        if (currentPath != "/") navigate(parent(currentPath))
    }

    private fun disconnect() {
        if (busy) cancel = true
        io.execute {
            client?.let { safeDisconnect(it) }
            client = null
        }
        showConnectScreen()
    }

    private fun updatePaste() {
        if (!::pasteBtn.isInitialized) return
        val c = clip
        pasteBtn.visibility = if (c == null) View.GONE else View.VISIBLE
        if (c != null) pasteBtn.text = (if (clipMove) "📋 Sposta qui: " else "📋 Incolla qui: ") + c.name
    }

    private fun navigate(path: String) {
        runTask("Apro $path") { c ->
            val list = list(c, path)
            runOnUiThread { showList(path, list) }
            "${list.size} elementi" + (sizeCache[path]?.let { " · totale ${fmt(it)}" } ?: "")
        }
    }

    private fun showList(path: String, list: List<Entry>) {
        val changed = path != currentPath
        currentPath = path
        entries.clear()
        entries.addAll(list)
        applySort()
        pathText.text = path
        if (changed) listView.setSelection(0)
    }

    // ---------------------------------------------------------------- ordinamento

    private fun sortDialog() {
        AlertDialog.Builder(this)
            .setTitle("Ordina per")
            .setSingleChoiceItems(SORT_LABELS, sortMode) { d, w ->
                d.dismiss()
                sortMode = w
                getSharedPreferences("ps5ftp", MODE_PRIVATE).edit().putInt("sort", w).apply()
                applySort()
                listView.setSelection(0)
                // Per ordinare le cartelle per dimensione bisogna prima calcolarle
                val missing = entries.any { it.isDir && !it.isLink && sizeCache[it.path] == null }
                if (w != SORT_NAME && missing) {
                    if (busy) toast("Le cartelle senza dimensione restano in fondo: calcolale con 📏 Dimensioni")
                    else calcAllSizes()
                }
            }
            .show()
    }

    /** Dimensione usata per l'ordinamento: -1 se non ancora calcolata. */
    private fun sortSize(e: Entry): Long = when {
        e.isLink -> -1L
        e.isDir -> sizeCache[e.path] ?: -1L
        else -> e.size
    }

    /** Riordina la lista visualizzata (da chiamare nel thread dell'interfaccia). */
    private fun applySort() {
        val byName = compareBy<Entry>({ !it.isDir }, { it.name.lowercase(Locale.ROOT) })
        val nameOnly = compareBy<Entry> { it.name.lowercase(Locale.ROOT) }
        val cmp: Comparator<Entry> = when (sortMode) {
            SORT_SIZE_DESC ->
                compareByDescending<Entry> { sortSize(it) }.then(nameOnly)
            SORT_SIZE_DESC_DIRS_FIRST ->
                compareBy<Entry> { !it.isDir }.thenByDescending { sortSize(it) }.then(nameOnly)
            SORT_SIZE_ASC ->
                // le cartelle non calcolate (-1) vanno in fondo anche qui
                compareBy<Entry> { if (sortSize(it) < 0) 1 else 0 }.thenBy { sortSize(it) }.then(nameOnly)
            else -> byName
        }
        entries.sortWith(cmp)
        adapter.notifyDataSetChanged()
    }

    /** Ricarica la cartella corrente (da chiamare nel thread di rete). */
    private fun reload(c: FTPClient) {
        val path = currentPath
        val list = list(c, path)
        runOnUiThread { showList(path, list) }
    }

    // ---------------------------------------------------------------- menu di un elemento

    private fun showOptions(e: Entry) {
        if (busy) {
            toast("Attendi, c'è un'operazione in corso")
            return
        }
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (e.isDir) {
            opts.add(Pair("Apri") { navigate(e.path) })
            if (!e.isLink) opts.add(Pair("Calcola dimensione") { calcSize(e) })
        } else {
            opts.add(Pair("Scarica sul telefono") { startDownload(e) })
        }
        opts.add(Pair("Rinomina") { renameDialog(e) })
        opts.add(Pair("Copia") {
            clip = e
            clipMove = false
            updatePaste()
            toast("Vai nella cartella di destinazione e premi \"Incolla qui\"")
        })
        opts.add(Pair("Sposta") {
            clip = e
            clipMove = true
            updatePaste()
            toast("Vai nella cartella di destinazione e premi \"Sposta qui\"")
        })
        opts.add(Pair("Elimina") { confirmDelete(e) })
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second.invoke() }
            .show()
    }

    private fun renameDialog(e: Entry) {
        val input = EditText(this).apply {
            setSingleLine()
            setText(e.name)
            val dot = e.name.lastIndexOf('.')
            setSelection(0, if (!e.isDir && dot > 0) dot else e.name.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rinomina")
            .setView(padded(input))
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty() || n == e.name || n.contains('/')) return@setPositiveButton
                runTask("Rinomino ${e.name}") { c ->
                    if (!c.rename(e.path, join(parent(e.path), n))) throw Exception("rinomina fallita (${reply(c)})")
                    invalidate(e.path)
                    reload(c)
                    "Rinominato in $n"
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun newFolderDialog() {
        val input = EditText(this).apply {
            hint = "Nome cartella"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Nuova cartella")
            .setView(padded(input))
            .setPositiveButton("Crea") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty() || n.contains('/')) return@setPositiveButton
                val dest = currentPath
                runTask("Creo $n") { c ->
                    if (!c.makeDirectory(join(dest, n))) throw Exception("creazione fallita (${reply(c)})")
                    reload(c)
                    "Cartella $n creata"
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun confirmDelete(e: Entry) {
        val extra = if (e.isDir && !e.isLink) "\n\nVerrà eliminata la cartella con tutto il suo contenuto." else ""
        AlertDialog.Builder(this)
            .setTitle("Eliminare?")
            .setMessage("${e.name}$extra\n\nL'operazione non si può annullare.")
            .setPositiveButton("Elimina") { _, _ ->
                runTask("Elimino ${e.name}") { c ->
                    val n = IntArray(1)
                    deleteRec(c, e, n)
                    invalidate(e.path)
                    runOnUiThread {
                        if (clip?.path == e.path) {
                            clip = null
                            updatePaste()
                        }
                    }
                    reload(c)
                    "Eliminati ${n[0]} elementi"
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun deleteRec(c: FTPClient, e: Entry, n: IntArray) {
        if (cancel) throw CancelledException()
        if (e.isDir && !e.isLink) {
            for (k in list(c, e.path)) deleteRec(c, k, n)
            c.changeWorkingDirectory(parent(e.path))
            if (!c.removeDirectory(e.path)) throw Exception("impossibile eliminare la cartella ${e.name} (${reply(c)})")
        } else {
            if (!c.deleteFile(e.path)) throw Exception("impossibile eliminare ${e.name} (${reply(c)})")
        }
        n[0]++
        throttledStatus("Eliminati ${n[0]} elementi...")
    }

    // ---------------------------------------------------------------- copia / sposta

    private fun paste() {
        val src = clip ?: return
        val dest = currentPath
        if (src.isDir && (dest == src.path || dest.startsWith(src.path.trimEnd('/') + "/"))) {
            toast("Non puoi mettere una cartella dentro se stessa")
            return
        }
        val exists = entries.any { it.name == src.name }
        if (clipMove) {
            if (parent(src.path) == dest) {
                toast("È già in questa cartella")
                return
            }
            if (exists) {
                toast("Qui c'è già un elemento chiamato ${src.name}")
                return
            }
            runTask("Sposto ${src.name}") { c ->
                if (!c.rename(src.path, join(dest, src.name))) throw Exception("spostamento fallito (${reply(c)})")
                invalidate(src.path)
                invalidate(dest)
                runOnUiThread {
                    clip = null
                    updatePaste()
                }
                reload(c)
                "Spostato ${src.name}"
            }
        } else {
            val name = if (exists) freeName(src.name, src.isDir) else src.name
            val target = join(dest, name)
            runTask("Copio ${src.name}") { c ->
                val aux = newClient()
                val n = IntArray(1)
                try {
                    copyRec(c, aux, src, target, n)
                } finally {
                    safeDisconnect(aux)
                }
                invalidate(dest)
                reload(c)
                "Copiato ${src.name} (${n[0]} file)"
            }
        }
    }

    private fun freeName(name: String, isDir: Boolean): String {
        val dot = if (isDir) -1 else name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val cand = "$base (copia${if (i > 1) " $i" else ""})$ext"
            if (entries.none { it.name == cand }) return cand
            i++
        }
    }

    /** L'FTP non sa copiare sul server: si legge con una connessione e si scrive con un'altra. */
    private fun copyRec(c: FTPClient, aux: FTPClient, e: Entry, dst: String, n: IntArray) {
        if (cancel) throw CancelledException()
        if (e.isLink) return
        if (e.isDir) {
            aux.makeDirectory(dst)
            for (k in list(c, e.path)) copyRec(c, aux, k, join(dst, k.name), n)
        } else {
            val input = c.retrieveFileStream(e.path)
                ?: throw Exception("impossibile leggere ${e.name} (${reply(c)})")
            aux.setCopyStreamListener(progress("Copio ${e.name}", e.size))
            try {
                input.use {
                    if (!aux.storeFile(dst, it)) throw Exception("impossibile scrivere ${e.name} (${reply(aux)})")
                }
            } finally {
                aux.setCopyStreamListener(null)
            }
            if (!c.completePendingCommand()) throw Exception("copia incompleta di ${e.name} (${reply(c)})")
            n[0]++
        }
    }

    // ---------------------------------------------------------------- dimensioni

    private fun calcAllSizes() {
        val snapshot = entries.toList()
        val dirs = snapshot.filter { it.isDir && !it.isLink }
        val path = currentPath
        runTask("Calcolo dimensioni") { c ->
            scanned = 0
            var total = snapshot.filter { !it.isDir }.sumOf { it.size }
            for (d in dirs) {
                val s = dirSize(c, d.path)
                sizeCache[d.path] = s
                total += s
                runOnUiThread { applySort() }
            }
            sizeCache[path] = total
            "Totale in $path: ${fmt(total)}"
        }
    }

    private fun calcSize(e: Entry) {
        runTask("Calcolo ${e.name}") { c ->
            scanned = 0
            val s = dirSize(c, e.path)
            sizeCache[e.path] = s
            runOnUiThread { applySort() }
            "${e.name}: ${fmt(s)}"
        }
    }

    private fun dirSize(c: FTPClient, path: String): Long {
        if (cancel) throw CancelledException()
        scanned++
        throttledStatus("Calcolo... $scanned cartelle lette\n$path")
        val list = try {
            list(c, path)
        } catch (e: CancelledException) {
            throw e
        } catch (e: Exception) {
            if (!c.isConnected) throw e
            return 0L // cartella non leggibile: la salto
        }
        var total = 0L
        for (e in list) {
            if (e.isLink) continue
            total += if (e.isDir) dirSize(c, e.path) else e.size
        }
        return total
    }

    private fun invalidate(path: String) {
        val p = path.trimEnd('/')
        sizeCache.keys.removeAll { k ->
            val kk = k.trimEnd('/')
            k == path || k == "/" || p.startsWith("$kk/") || kk.startsWith("$p/")
        }
    }

    // ---------------------------------------------------------------- carica / scarica

    private fun pickFiles() {
        if (busy) { toast("Attendi, c'è un'operazione in corso"); return }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, REQ_FILES)
    }

    private fun pickTree() {
        if (busy) { toast("Attendi, c'è un'operazione in corso"); return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
    }

    private fun startDownload(e: Entry) {
        pendingDownload = e
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, e.name)
        startActivityForResult(i, REQ_DOWNLOAD)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_FILES -> {
                val uris = ArrayList<Uri>()
                val cd = data.clipData
                if (cd != null) {
                    for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
                } else {
                    data.data?.let { uris.add(it) }
                }
                if (uris.isNotEmpty()) uploadUris(uris)
            }
            REQ_TREE -> data.data?.let { uploadTree(it) }
            REQ_DOWNLOAD -> data.data?.let { downloadTo(it) }
            REQ_BG -> data.data?.let { saveBgImage(it) }
            REQ_FONT -> data.data?.let { saveFont(it) }
        }
    }

    private fun uploadUris(uris: List<Uri>) {
        val dest = currentPath
        runTask("Caricamento") { c ->
            uris.forEachIndexed { i, uri ->
                if (cancel) throw CancelledException()
                val (name, size) = queryNameSize(uri)
                uploadStream(c, uri, join(dest, name), "[${i + 1}/${uris.size}] $name", size)
            }
            invalidate(dest)
            reload(c)
            "Caricati ${uris.size} file in $dest"
        }
    }

    private fun uploadTree(treeUri: Uri) {
        val dest = currentPath
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryNameSize(DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)).first
        runTask("Caricamento cartella $rootName") { c ->
            val n = IntArray(1)
            uploadDocDir(c, treeUri, treeId, join(dest, rootName), n)
            invalidate(dest)
            reload(c)
            "Cartella $rootName caricata (${n[0]} file)"
        }
    }

    private fun uploadDocDir(c: FTPClient, treeUri: Uri, docId: String, remoteDir: String, n: IntArray) {
        if (cancel) throw CancelledException()
        c.makeDirectory(remoteDir) // se esiste già va bene lo stesso
        val kids = ArrayList<Doc>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                kids.add(
                    Doc(
                        cur.getString(0),
                        cur.getString(1) ?: "file",
                        cur.getString(2) ?: "",
                        if (cur.isNull(3)) -1L else cur.getLong(3)
                    )
                )
            }
        }
        for (k in kids) {
            if (cancel) throw CancelledException()
            if (k.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                uploadDocDir(c, treeUri, k.id, join(remoteDir, k.name), n)
            } else {
                val u = DocumentsContract.buildDocumentUriUsingTree(treeUri, k.id)
                uploadStream(c, u, join(remoteDir, k.name), "[${n[0] + 1}] ${k.name}", k.size)
                n[0]++
            }
        }
    }

    private fun uploadStream(c: FTPClient, uri: Uri, remote: String, label: String, size: Long) {
        val input = contentResolver.openInputStream(uri) ?: throw Exception("impossibile aprire $label")
        c.setCopyStreamListener(progress(label, size))
        try {
            input.use {
                if (!c.storeFile(remote, it)) throw Exception("caricamento di $label fallito (${reply(c)})")
            }
        } finally {
            c.setCopyStreamListener(null)
        }
    }

    private fun downloadTo(uri: Uri) {
        val e = pendingDownload ?: return
        pendingDownload = null
        runTask("Scarico ${e.name}") { c ->
            val out = contentResolver.openOutputStream(uri) ?: throw Exception("impossibile creare il file sul telefono")
            c.setCopyStreamListener(progress("Scarico ${e.name}", e.size))
            try {
                out.use {
                    if (!c.retrieveFile(e.path, it)) throw Exception("download fallito (${reply(c)})")
                }
            } finally {
                c.setCopyStreamListener(null)
            }
            "Scaricato ${e.name}"
        }
    }

    private fun queryNameSize(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cur ->
                    if (cur.moveToFirst()) {
                        if (!cur.isNull(0)) name = cur.getString(0)
                        if (!cur.isNull(1)) size = cur.getLong(1)
                    }
                }
        } catch (ignored: Exception) {
        }
        return Pair(name, size)
    }

    private fun progress(label: String, total: Long): CopyStreamListener {
        val start = System.currentTimeMillis()
        return object : CopyStreamListener {
            override fun bytesTransferred(event: CopyStreamEvent) {
                bytesTransferred(event.totalBytesTransferred, event.bytesTransferred, event.streamSize)
            }

            override fun bytesTransferred(totalBytesTransferred: Long, bytesTransferred: Int, streamSize: Long) {
                if (cancel) throw CancelledException()
                val pct = if (total > 0) " ${totalBytesTransferred * 100 / total}%" else ""
                val secs = (System.currentTimeMillis() - start) / 1000.0
                val speed = if (secs > 0.5) " · ${fmt((totalBytesTransferred / secs).toLong())}/s" else ""
                val of = if (total > 0) " / ${fmt(total)}" else ""
                throttledStatus("$label$pct\n${fmt(totalBytesTransferred)}$of$speed")
            }
        }
    }

    // ---------------------------------------------------------------- FTP

    private fun newClient(): FTPClient {
        val c = FTPClient()
        c.configure(FTPClientConfig(FTPClientConfig.SYST_UNIX))
        c.setControlEncoding("UTF-8")
        c.setConnectTimeout(8000)
        c.setDefaultTimeout(20000)
        c.setDataTimeout(Duration.ofSeconds(60))
        c.connect(host, port)
        if (!FTPReply.isPositiveCompletion(c.replyCode)) {
            val r = reply(c)
            safeDisconnect(c)
            throw Exception("il server ha rifiutato la connessione ($r)")
        }
        c.setSoTimeout(30000)
        if (!c.login("anonymous", "anonymous")) {
            val r = reply(c)
            safeDisconnect(c)
            throw Exception("accesso rifiutato ($r)")
        }
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        c.setBufferSize(1024 * 1024)
        c.setListHiddenFiles(true)
        return c
    }

    /** Restituisce una connessione funzionante, riconnettendosi se serve. */
    private fun ftp(): FTPClient {
        val c = client
        if (c != null && c.isConnected) {
            try {
                if (c.sendNoOp()) return c
            } catch (ignored: Exception) {
            }
            safeDisconnect(c)
            postStatus("Riconnessione...")
        }
        return newClient().also { client = it }
    }

    /** Elenca una cartella, file nascosti compresi (LIST -a); se il server non lo supporta riprova senza. */
    private fun list(c: FTPClient, path: String): List<Entry> {
        if (!c.changeWorkingDirectory(path)) throw Exception("impossibile aprire $path (${reply(c)})")
        var files: Array<FTPFile>? = null
        c.setListHiddenFiles(true)
        try {
            files = c.listFiles()
        } catch (e: Exception) {
            if (!c.isConnected) throw e
        }
        if (files == null || (files.isEmpty() && !FTPReply.isPositiveCompletion(c.replyCode))) {
            c.setListHiddenFiles(false)
            try {
                files = c.listFiles()
            } finally {
                c.setListHiddenFiles(true)
            }
        }
        return (files ?: emptyArray())
            .filterNotNull()
            .filter { it.name != null && it.name != "." && it.name != ".." }
            .map {
                Entry(
                    name = it.name,
                    isDir = it.isDirectory || it.isSymbolicLink,
                    isLink = it.isSymbolicLink,
                    size = it.size,
                    path = join(path, it.name)
                )
            }
            .sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase(Locale.ROOT) }))
    }

    private fun runTask(label: String, block: (FTPClient) -> String?) {
        if (busy) {
            toast("Attendi, c'è un'operazione in corso")
            return
        }
        busy = true
        cancel = false
        statusText.text = "$label..."
        stopBtn.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        io.execute {
            var error = false
            val msg: String? = try {
                block(ftp())
            } catch (e: CancelledException) {
                client?.let { safeDisconnect(it) }
                client = null
                "Operazione annullata"
            } catch (e: Exception) {
                error = true
                "Errore: ${e.message ?: e.javaClass.simpleName}"
            }
            busy = false
            runOnUiThread {
                stopBtn.visibility = View.GONE
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (msg != null) statusText.text = msg
                if (error) toast(msg ?: "Errore")
            }
        }
    }

    private fun safeDisconnect(c: FTPClient) {
        try {
            if (c.isConnected) c.disconnect()
        } catch (ignored: Exception) {
        }
    }

    private fun reply(c: FTPClient): String = c.replyString?.trim() ?: ""

    // ---------------------------------------------------------------- utilità

    private fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

    private fun parent(path: String): String {
        val t = path.trimEnd('/')
        val i = t.lastIndexOf('/')
        return if (i <= 0) "/" else t.substring(0, i)
    }

    private fun fmt(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return String.format(Locale.ITALY, "%.2f %s", v, units[i])
    }

    private fun detail(e: Entry): String = when {
        e.isLink -> "collegamento"
        e.isDir -> sizeCache[e.path]?.let { "cartella · ${fmt(it)}" } ?: "cartella · premi 📏 Dimensioni per calcolare"
        else -> fmt(e.size)
    }

    private fun postStatus(s: String) {
        runOnUiThread { if (::statusText.isInitialized) statusText.text = s }
    }

    private fun throttledStatus(s: String) {
        val now = System.currentTimeMillis()
        if (now - lastPost > 300) {
            lastPost = now
            postStatus(s)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun density() = resources.displayMetrics.density

    /** Testo con colore, dimensione e carattere del tema. */
    private fun tv(s: String, size: Float, color: Int, forceBold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size * tema.fontScale
        setTextColor(color)
        typeface = tema.typeface(forceBold)
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f * tema.fontScale
        typeface = tema.typeface()
        setTextColor(tema.btnText)
        background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), tema.btnDrawable(density()), null)
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        setOnClickListener { onClick() }
    }

    private fun styleEdit(e: EditText) {
        e.setTextColor(tema.text)
        e.setHintTextColor((tema.text and 0x00FFFFFF) or 0x80000000.toInt())
        e.typeface = tema.typeface()
        e.textSize = 16f * tema.fontScale
        e.backgroundTintList = ColorStateList.valueOf(tema.accent)
    }

    private fun padded(v: View) = FrameLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(v)
    }

    /** Mette lo sfondo scelto (tinta unita, sfumato o immagine) dietro al contenuto. */
    private fun themed(content: View): View {
        val fl = FrameLayout(this)
        var bar = tema.bg1
        var nav = tema.bg1
        when (tema.bgType) {
            2 -> {
                val bmp = bgBitmap()
                if (bmp != null) {
                    fl.setBackgroundColor(Color.BLACK)
                    fl.addView(ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(bmp)
                    }, FrameLayout.LayoutParams(MATCH, MATCH))
                    fl.addView(View(this).apply { setBackgroundColor(tema.overlay) }, FrameLayout.LayoutParams(MATCH, MATCH))
                    bar = Color.BLACK
                    nav = Color.BLACK
                } else {
                    fl.setBackgroundColor(tema.bg1)
                }
            }
            1 -> {
                fl.background = GradientDrawable(tema.orient(tema.bgDir), intArrayOf(tema.bg1, tema.bg2))
                nav = tema.bg2
            }
            else -> fl.setBackgroundColor(tema.bg1)
        }
        fl.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        val opaque = 0xFF000000.toInt()
        window.statusBarColor = bar or opaque
        window.navigationBarColor = nav or opaque
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            if (Color.luminance(bar or opaque) > 0.6f) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        return fl
    }

    private fun bgBitmap(): Bitmap? {
        bgCache?.let { return it }
        val f = tema.bgFile()
        if (!f.exists()) return null
        bgCache = try { BitmapFactory.decodeFile(f.absolutePath) } catch (e: Exception) { null }
        return bgCache
    }

    // ---------------------------------------------------------------- schermata Aspetto

    private fun openTheme() {
        if (busy) {
            toast("Attendi la fine dell'operazione in corso")
            return
        }
        themeFrom = screen
        themeScrollY = 0
        showThemeScreen()
    }

    private fun closeTheme() {
        if (themeFrom == 1) {
            val copy = entries.toList()
            showBrowser()
            showList(currentPath, copy)
            statusText.text = "Aspetto aggiornato"
        } else {
            showConnectScreen()
        }
    }

    /** Salva e ridisegna la schermata Aspetto, così si vede subito il risultato. */
    private fun changed() {
        tema.save()
        if (screen != 2) return
        themeScrollY = themeScroll?.scrollY ?: 0
        showThemeScreen()
    }

    private fun showThemeScreen() {
        screen = 2
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(40))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(btn("← Fatto") { closeTheme() })
        head.addView(tv("Aspetto", 24f, tema.accent, true).apply { setPadding(dp(12), 0, 0, 0) })
        col.addView(head)

        section(col, "Temi pronti")
        col.addView(tv("Tocca un tema per partire da lì, poi personalizzalo come vuoi.", 13f, tema.text).apply { alpha = 0.75f })
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Tema.PRESETS.forEachIndexed { i, name ->
            presets.addView(btn(name) {
                tema.applyPreset(i)
                changed()
            })
        }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(presets)
        })

        section(col, "Sfondo")
        choiceRow(col, listOf("Tinta unita", "Sfumato", "Immagine"), tema.bgType) {
            tema.bgType = it
            changed()
        }
        when (tema.bgType) {
            1 -> {
                colorRow(col, "Colore 1", { tema.bg1 }) { tema.bg1 = it }
                colorRow(col, "Colore 2", { tema.bg2 }) { tema.bg2 = it }
                choiceRow(col, Tema.DIREZIONI.toList(), tema.bgDir) {
                    tema.bgDir = it
                    changed()
                }
            }
            2 -> {
                actionRow(col, if (tema.bgFile().exists()) "🖼 Cambia immagine" else "🖼 Scegli un'immagine") { pickBgImage() }
                colorRow(col, "Velo sopra l'immagine (con l'opacità la scurisci o schiarisci)", { tema.overlay }) { tema.overlay = it }
            }
            else -> colorRow(col, "Colore", { tema.bg1 }) { tema.bg1 = it }
        }

        section(col, "Riquadri dei file e barra in alto")
        colorRow(col, if (tema.panelGrad) "Colore 1" else "Colore", { tema.panel1 }) { tema.panel1 = it }
        toggleRow(col, "Sfumato", tema.panelGrad) {
            tema.panelGrad = it
            changed()
        }
        if (tema.panelGrad) colorRow(col, "Colore 2", { tema.panel2 }) { tema.panel2 = it }

        section(col, "Pulsanti")
        colorRow(col, if (tema.btnGrad) "Colore 1" else "Colore", { tema.btn1 }) { tema.btn1 = it }
        toggleRow(col, "Sfumato", tema.btnGrad) {
            tema.btnGrad = it
            changed()
        }
        if (tema.btnGrad) colorRow(col, "Colore 2", { tema.btn2 }) { tema.btn2 = it }
        colorRow(col, "Colore della scritta", { tema.btnText }) { tema.btnText = it }

        section(col, "Testi")
        colorRow(col, "Testo principale", { tema.text }) { tema.text = it }
        colorRow(col, "Nomi delle cartelle", { tema.folder }) { tema.folder = it }
        colorRow(col, "Nomi dei file", { tema.file }) { tema.file = it }
        colorRow(col, "Percorso, titoli e messaggi", { tema.accent }) { tema.accent = it }

        section(col, "Carattere")
        actionRow(col, "Tipo di carattere: ${Tema.FONT_LABELS[tema.font.coerceIn(0, Tema.FONT_LABELS.size - 1)]}") { fontDialog() }
        sliderRow(col, "Dimensione", 70, 170, (tema.fontScale * 100).toInt(), { "$it%" }) { tema.fontScale = it / 100f }
        toggleRow(col, "Tutto in grassetto", tema.bold) {
            tema.bold = it
            changed()
        }
        sliderRow(col, "Arrotondamento degli angoli", 0, 30, tema.radius, { "$it" }) { tema.radius = it }

        section(col, "Anteprima")
        val r1 = newRow()
        bindRow(r1, "📁 Giochi", "cartella · 128,40 GB", true)
        col.addView(r1)
        val r2 = newRow()
        bindRow(r2, "📄 eboot.bin", "35,12 MB", false)
        col.addView(r2)
        val sample = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sample.addView(btn("Pulsante") {})
        sample.addView(btn("📋 Incolla") {})
        col.addView(sample)

        section(col, "")
        actionRow(col, "↺ Ripristina l'aspetto iniziale") {
            AlertDialog.Builder(this)
                .setTitle("Ripristinare l'aspetto iniziale?")
                .setPositiveButton("Ripristina") { _, _ ->
                    tema.resetAll()
                    changed()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }

        val sv = ScrollView(this).apply { addView(col) }
        themeScroll = sv
        setContentView(themed(sv))
        val y = themeScrollY
        sv.post { sv.scrollTo(0, y) }
    }

    private fun section(col: LinearLayout, title: String) {
        col.addView(tv(title.uppercase(Locale.ITALY), 13f, tema.accent, true).apply {
            setPadding(dp(4), dp(22), 0, dp(6))
            letterSpacing = 0.08f
        })
    }

    private fun settingBox(col: LinearLayout, horizontal: Boolean): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = tema.panelDrawable(density())
        }
        col.addView(box, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        return box
    }

    private fun colorRow(col: LinearLayout, label: String, get: () -> Int, set: (Int) -> Unit) {
        val row = settingBox(col, true)
        row.addView(tv(label, 15f, tema.text), LinearLayout.LayoutParams(0, WRAP, 1f))
        val swatch = View(this).apply {
            background = GradientDrawable().apply {
                setColor(get())
                cornerRadius = dp(8).toFloat()
                setStroke(dp(2), Color.GRAY)
            }
        }
        row.addView(swatch, LinearLayout.LayoutParams(dp(52), dp(32)).apply { leftMargin = dp(10) })
        row.setOnClickListener {
            scegliColore(this, label, get()) {
                set(it)
                changed()
            }
        }
    }

    private fun toggleRow(col: LinearLayout, label: String, value: Boolean, set: (Boolean) -> Unit) {
        val row = settingBox(col, true)
        row.addView(tv(label, 15f, tema.text), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(tv(if (value) "✅ Sì" else "⬜ No", 15f, tema.accent, true))
        row.setOnClickListener { set(!value) }
    }

    private fun choiceRow(col: LinearLayout, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { i, o ->
            val b = btn(if (i == selected) "✓ $o" else o) { onPick(i) }
            if (i != selected) b.alpha = 0.6f
            line.addView(b)
        }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(line)
        })
    }

    private fun actionRow(col: LinearLayout, label: String, action: () -> Unit) {
        col.addView(btn(label, action), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
    }

    private fun sliderRow(
        col: LinearLayout, label: String, lo: Int, hi: Int, value: Int,
        fmtValue: (Int) -> String, set: (Int) -> Unit
    ) {
        val box = settingBox(col, false)
        val lbl = tv("$label: ${fmtValue(value)}", 15f, tema.text)
        box.addView(lbl)
        val sb = SeekBar(this)
        sb.max = hi - lo
        sb.progress = (value - lo).coerceIn(0, hi - lo)
        sb.progressTintList = ColorStateList.valueOf(tema.accent)
        sb.thumbTintList = ColorStateList.valueOf(tema.accent)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                lbl.text = "$label: ${fmtValue(p + lo)}"
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {
                set(s.progress + lo)
                changed()
            }
        })
        box.addView(sb, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun fontDialog() {
        val ad = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, Tema.FONT_LABELS) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.typeface = if (position < Tema.FAMILIES.size) {
                    Typeface.create(Tema.FAMILIES[position], Typeface.NORMAL)
                } else {
                    tema.customTypeface() ?: Typeface.DEFAULT
                }
                return v
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Tipo di carattere")
            .setAdapter(ad) { _, i ->
                if (i == Tema.CUSTOM) {
                    pickFont()
                } else {
                    tema.font = i
                    tema.resetFont()
                    changed()
                }
            }
            .show()
    }

    private fun pickBgImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
        startActivityForResult(i, REQ_BG)
    }

    private fun pickFont() {
        toast("Scegli un file di carattere .ttf o .otf")
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(i, REQ_FONT)
    }

    private fun saveBgImage(uri: Uri) {
        toast("Carico l'immagine...")
        Thread {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 2000 || bounds.outHeight / sample > 2000) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: throw Exception("immagine non leggibile")
                FileOutputStream(tema.bgFile()).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bgCache = null
                runOnUiThread {
                    tema.bgType = 2
                    changed()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Impossibile usare l'immagine: ${e.message}") }
            }
        }.start()
    }

    private fun saveFont(uri: Uri) {
        Thread {
            try {
                val input = contentResolver.openInputStream(uri) ?: throw Exception("file non leggibile")
                input.use { inp -> FileOutputStream(tema.fontFile()).use { inp.copyTo(it) } }
                Typeface.createFromFile(tema.fontFile())
                runOnUiThread {
                    tema.font = Tema.CUSTOM
                    tema.resetFont()
                    changed()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Il file non è un carattere valido (serve un .ttf o .otf)") }
            }
        }.start()
    }

    // ---------------------------------------------------------------- righe della lista

    private fun newRow(): FrameLayout {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = tema.panelDrawable(density())
        }
        inner.addView(tv("", 16f, tema.file, true))
        inner.addView(tv("", 12f, tema.text).apply { alpha = 0.75f })
        return FrameLayout(this).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3))
            addView(inner, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun bindRow(row: FrameLayout, title: String, detailText: String, isDir: Boolean) {
        val inner = row.getChildAt(0) as LinearLayout
        val t = inner.getChildAt(0) as TextView
        t.text = title
        t.setTextColor(if (isDir) tema.folder else tema.file)
        (inner.getChildAt(1) as TextView).text = detailText
    }

    inner class EntryAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? FrameLayout) ?: newRow()
            val e = entries[position]
            val icon = when {
                e.isLink -> "🔗 "
                e.isDir -> "📁 "
                else -> "📄 "
            }
            bindRow(row, icon + e.name, detail(e), e.isDir)
            return row
        }
    }
}
