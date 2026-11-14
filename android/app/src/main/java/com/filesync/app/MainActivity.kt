package com.filesync.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import com.filesync.app.api.HttpServerApi
import com.filesync.app.api.PutStatus
import com.filesync.app.api.RemoteFile
import com.filesync.app.api.ServerApi
import com.filesync.app.files.DirEntry
import com.filesync.app.files.PrefsStateStore
import com.filesync.app.files.SafFiles
import com.filesync.app.sync.ConflictInfo
import com.filesync.app.sync.SyncEngine
import com.filesync.app.util.sha256Hex
import io.noties.markwon.Markwon
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed class Screen {
    data object Main : Screen()
    data object Settings : Screen()
    data class Preview(val rel: String) : Screen()
}

class ConflictPrompt(val info: ConflictInfo, val onChoice: (String) -> Unit)

class AppModel(val context: Context) {
    private val prefs = context.getSharedPreferences("filesync", Context.MODE_PRIVATE)

    var screen by mutableStateOf<Screen>(Screen.Main)
    var host by mutableStateOf(prefs.getString("host", "127.0.0.1") ?: "127.0.0.1")
    var port by mutableStateOf(prefs.getString("port", "8080") ?: "8080")
    var token by mutableStateOf(prefs.getString("token", "") ?: "")
    var subDir by mutableStateOf(prefs.getString("subdir", "") ?: "")
    var treeUri by mutableStateOf<String?>(prefs.getString("treeUri", null))
    var busy by mutableStateOf(false)
    var lastStats by mutableStateOf<String?>(null)
    var refreshTick by mutableStateOf(0)
    var conflict by mutableStateOf<ConflictPrompt?>(null)
    val logLines = mutableStateListOf<String>()

    /** host 可直接填完整 URL（https://IP:8443）；不带协议头时自动按 HTTPS 处理。 */
    val serverUrl: String
        get() {
            val h = host.trim()
            if (h.startsWith("http://") || h.startsWith("https://")) return h.trimEnd('/')
            return "https://$h:${port.trim()}"
        }

    fun log(s: String) {
        logLines.add(s)
        while (logLines.size > 200) logLines.removeAt(0)
    }

    fun saveSettings() {
        prefs.edit()
            .putString("host", host.trim())
            .putString("port", port.trim())
            .putString("token", token.trim())
            .putString("subdir", subDir.trim())
            .apply()
    }

    fun onTreePicked(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
        treeUri = uri.toString()
        // 换同步目录时重置基线与子目录绑定（新目录是新同步组）
        prefs.edit()
            .putString("treeUri", treeUri)
            .putString("subdir", "")
            .putString("state:${treeUri ?: ""}", "")
            .commit()
        screen = Screen.Main
    }

    fun localFiles(): SafFiles = SafFiles(context, Uri.parse(treeUri))

    fun api(): ServerApi = HttpServerApi(serverUrl, token.trim(), subDir.trim())

    fun stateStore(): PrefsStateStore = PrefsStateStore(prefs, "state:" + (treeUri ?: ""))

    fun resetBaseline() {
        stateStore().save(emptyMap())
        log("基线已重置")
    }

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val (ok, detail) = api().probe()
        if (!ok) return@withContext "连接失败: $detail"
        try {
            "已连接，服务器文件 ${api().ls().size} 个"
        } catch (e: Exception) {
            "连接失败: ${e.message}"
        }
    }

    private fun buildEngine(): SyncEngine {
        val l = localFiles()
        val s = api()
        val st = stateStore()
        return SyncEngine(l, s, st, { log(it) }) { info ->
            suspendCancellableCoroutine { cont ->
                conflict = ConflictPrompt(info) { choice ->
                    conflict = null
                    if (cont.isActive) cont.resume(choice, null)
                }
            }
        }
    }

    suspend fun runSync(filter: ((String) -> Boolean)?): SyncEngine.Stats? {
        android.util.Log.d(
            "FileSync",
            "runSync: busy=$busy treeUriSet=${treeUri != null} treeUri=$treeUri",
        )
        if (busy || treeUri == null) return null
        busy = true
        try {
            withContext(Dispatchers.IO) {
                try {
                    localFiles().cleanupTemps()
                } catch (_: Exception) {
                }
            }
            val stats = withContext(Dispatchers.IO) { buildEngine().sync(filter) }
            lastStats =
                "上传 ${stats.uploaded} · 下载 ${stats.downloaded} · 无变化 ${stats.unchanged} · 冲突解决 ${stats.resolved} · 跳过 ${stats.skipped} · 需注意 ${stats.warnings}" +
                    if (stats.failed > 0) " · 失败 ${stats.failed}" else ""
            return stats
        } catch (e: Exception) {
            android.util.Log.e("FileSync", "runSync failed", e)
            log("同步失败: ${e::class.java.simpleName}: ${e.message}")
            return null
        } finally {
            busy = false
            refreshTick++
        }
    }

    suspend fun fetchRemote(rel: String): String = withContext(Dispatchers.IO) {
        try {
            val dl = api().get(rel) ?: return@withContext "远端不存在"
            if (localFiles().write(rel, dl.first)) "已用远端覆盖本地" else "写入失败"
        } catch (e: Exception) {
            "失败: ${e.message}"
        }
    }

    suspend fun pushLocal(rel: String): String = withContext(Dispatchers.IO) {
        try {
            val sf = localFiles()
            val e = sf.entryFor(rel) ?: return@withContext "本地不存在"
            if (e.ambiguous) return@withContext "同名歧义，拒绝上传"
            val data = sf.read(e) ?: return@withContext "本地读取失败"
            when (api().put(rel, data, true, null)) {
                PutStatus.CREATED -> "已用本地覆盖远端"
                PutStatus.CONFLICT -> "远端已变化，被拒绝（请运行同步处理冲突）"
                PutStatus.ERROR -> "上传失败"
            }
        } catch (e: Exception) {
            "失败: ${e.message}"
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfigIntent(intent)
        setContent { FileSyncApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyConfigIntent(intent)
    }

    /** adb 深链注入配置：am start --es server <url> --es token <令牌> --es subdir <子目录> */
    private fun applyConfigIntent(i: Intent?) {
        val server = i?.getStringExtra("server") ?: return
        val p = getSharedPreferences("filesync", Context.MODE_PRIVATE)
        p.edit()
            .putString("host", server.trim())
            .putString("token", (i.getStringExtra("token") ?: "").trim())
            .putString("subdir", (i.getStringExtra("subdir") ?: "").trim())
            .commit()
    }
}

@Composable
fun FileSyncApp() {
    val ctx = LocalContext.current
    val model = remember { AppModel(ctx.applicationContext) }
    FileSyncTheme {
        Surface(Modifier.fillMaxSize()) {
            BackHandler(enabled = model.screen != Screen.Main) { model.screen = Screen.Main }
            AnimatedContent(
                targetState = model.screen,
                transitionSpec = {
                    (fadeIn(tween(160)) + slideInHorizontally(tween(160)) { it / 12 })
                        .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { -it / 12 })
                },
                label = "screen",
            ) { s ->
                when (s) {
                    Screen.Main -> MainScreen(model)
                    Screen.Settings -> SettingsScreen(model)
                    is Screen.Preview -> PreviewScreen(model, s.rel)
                }
            }
            model.conflict?.let { ConflictDialog(it) }
        }
    }
}

@Composable
private fun FileSyncTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(model: AppModel) {
    val scope = rememberCoroutineScope()
    var dir by remember { mutableStateOf("") }
    var entries by remember(dir) { mutableStateOf<List<DirEntry>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var viewMode by remember { mutableStateOf("local") }
    var cloudList by remember { mutableStateOf<List<RemoteFile>?>(null) }
    LaunchedEffect(dir, model.refreshTick, model.treeUri) {
        entries = if (model.treeUri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    model.localFiles().listDir(dir)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
    LaunchedEffect(viewMode, model.refreshTick, model.treeUri) {
        if (viewMode == "cloud" && model.treeUri != null) {
            cloudList = withContext(Dispatchers.IO) {
                runCatching { model.api().ls("") }.getOrNull()
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (dir.isEmpty()) "FileSync" else dir.substringAfterLast('/')) },
                navigationIcon = {
                    if (dir.isNotEmpty()) {
                        IconButton(onClick = { dir = parentDir(dir) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { model.screen = Screen.Settings }) {
                        Icon(Icons.Default.Settings, null)
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (model.treeUri == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { model.screen = Screen.Settings }) { Text("选择同步目录") }
                }
                return@Column
            }
            Button(
                onClick = { scope.launch { model.runSync(null) } },
                enabled = !model.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(52.dp),
            ) {
                if (model.busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("同步中")
                } else {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(12.dp))
                    Text("同步")
                }
            }
            AnimatedVisibility(
                visible = model.lastStats != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Text(
                    model.lastStats.orEmpty(),
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            AnimatedVisibility(
                visible = selected.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Button(
                    onClick = {
                        val sel = selected
                        scope.launch {
                            model.runSync { it in sel }
                            selected = emptySet()
                        }
                    },
                    enabled = !model.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .height(44.dp),
                ) { Text("同步选中 (${selected.size})") }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = viewMode == "local", onClick = { viewMode = "local" }, label = { Text("本地") })
                FilterChip(selected = viewMode == "cloud", onClick = { viewMode = "cloud" }, label = { Text("云端") })
            }
            if (viewMode == "cloud") {
                CloudFileList(
                    model = model,
                    refreshTick = model.refreshTick,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                return@Column
            }
            val e = entries
            when {
                e == null -> Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(e) { en ->
                        val rel = joinDir(dir, en.name)
                        if (en.isDir) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { dir = rel }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("▸ ", color = MaterialTheme.colorScheme.primary)
                                Text(en.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        } else {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { model.screen = Screen.Preview(rel) }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = rel in selected,
                                    onCheckedChange = { on ->
                                        selected = if (on) selected + rel else selected - rel
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(en.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        formatBytes(en.size),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    if (model.logLines.isNotEmpty()) {
                        item {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(tween(200)),
                            ) {
                                Card(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                ) {
                                    Column(
                                        Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        model.logLines.takeLast(10).forEach {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(model: AppModel) {
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.onTreePicked(uri)
    }
    val cfgImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val raw = model.context.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: return@runCatching
                val o = JSONObject(raw)
                model.host = o.optString("server", model.host)
                model.token = o.optString("token", model.token)
                model.subDir = o.optString("subdir", model.subDir)
                model.saveSettings()
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = { model.screen = Screen.Main }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = model.host,
                onValueChange = { model.host = it; model.saveSettings() },
                label = { Text("服务器地址（IP 或 https://IP:端口）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model.port,
                onValueChange = { model.port = it; model.saveSettings() },
                label = { Text("端口（填完整 URL 时忽略）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model.token,
                onValueChange = { model.token = it; model.saveSettings() },
                label = { Text("访问令牌") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model.subDir,
                onValueChange = { model.subDir = it; model.saveSettings() },
                label = { Text("云端子目录（可选，同步组）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { cfgImporter.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }) { Text("从文件导入配置") }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        testing = true
                        scope.launch {
                            testResult = model.testConnection()
                            testing = false
                        }
                    },
                    enabled = !testing,
                ) { Text("测试连接") }
                if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (model.treeUri == null) "选择同步目录" else "更换同步目录")
            }
            model.treeUri?.let { uriStr ->
                val dirName = remember(uriStr) {
                    DocumentFile.fromTreeUri(model.context, Uri.parse(uriStr))?.name ?: uriStr
                }
                Text(dirName, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { confirmReset = true }) { Text("重置同步基线") }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("重置同步基线") },
            text = { Text("下次同步将重新比较两端内容，内容不同的文件需手动选择版本。") },
            confirmButton = {
                TextButton({ confirmReset = false; model.resetBaseline() }) { Text("重置") }
            },
            dismissButton = { TextButton({ confirmReset = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewScreen(model: AppModel, rel: String) {
    val ext = rel.substringAfterLast('.', "").lowercase()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(rel.substringAfterLast('/')) },
                navigationIcon = {
                    IconButton(onClick = { model.screen = Screen.Main }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (ext) {
                    "md", "markdown", "txt", "log" -> MarkdownPane(model, rel)
                    "pdf" -> PdfPane(model, rel)
                    else -> OtherPane(model, rel)
                }
            }
            ActionRow(model, rel)
        }
    }
}

@Composable
private fun MarkdownPane(model: AppModel, rel: String) {
    var content by remember(rel) { mutableStateOf<String?>(null) }
    var editing by remember(rel) { mutableStateOf(false) }
    var text by remember(rel) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var missing by remember(rel) { mutableStateOf(false) }
    LaunchedEffect(rel) {
        val sf = model.localFiles()
        val e = withContext(Dispatchers.IO) { sf.entryFor(rel) }
        if (e == null || e.ambiguous) {
            missing = true
            content = null
        } else {
            val c = withContext(Dispatchers.IO) { sf.read(e)?.toString(Charsets.UTF_8) }
            content = c
            text = c ?: ""
        }
    }
    val c = content
    if (c == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (missing) "本地不存在" else "加载中…")
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = !editing, onClick = { editing = false }, label = { Text("预览") })
            FilterChip(selected = editing, onClick = { editing = true; text = c }, label = { Text("编辑") })
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            model.localFiles().write(rel, text.toByteArray(Charsets.UTF_8))
                        }
                        if (ok) {
                            content = text
                            model.log("已保存 $rel")
                            model.refreshTick++
                        } else {
                            model.log("保存失败 $rel")
                        }
                    }
                },
                enabled = editing && text != c,
            ) { Text("保存") }
        }
        if (editing) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )
        } else {
            MarkdownView(c, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun MarkdownView(markdown: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val markwon = remember { Markwon.create(ctx) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    AndroidView(
        modifier = modifier,
        factory = { c ->
            TextView(c).apply { movementMethod = LinkMovementMethod.getInstance() }
        },
        update = { tv ->
            tv.setTextColor(onSurface.toArgb())
            markwon.setMarkdown(tv, markdown)
        },
    )
}

@Composable
private fun PdfPane(model: AppModel, rel: String) {
    var pages by remember(rel) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var status by remember(rel) { mutableStateOf("渲染中…") }
    LaunchedEffect(rel) {
        withContext(Dispatchers.IO) {
            try {
                val uri = model.localFiles().uriFor(rel)
                if (uri == null) {
                    status = "本地不存在"
                } else {
                    pages = renderPdf(model.context.contentResolver, uri)
                    status = ""
                }
            } catch (e: Exception) {
                status = "渲染失败: ${e.message}"
            }
        }
    }
    if (status.isNotEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(status) }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(pages.size) { i ->
                Image(
                    pages[i].asImageBitmap(),
                    null,
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }
    }
}

private fun renderPdf(resolver: android.content.ContentResolver, uri: Uri): List<Bitmap> {
    val pfd = resolver.openFileDescriptor(uri, "r") ?: throw Exception("无法打开")
    val out = ArrayList<Bitmap>()
    try {
        val renderer = PdfRenderer(pfd)
        try {
            val count = min(renderer.pageCount, 50)
            for (i in 0 until count) {
                renderer.openPage(i).use { page ->
                    val scale = (1400f / max(page.width, page.height)).coerceIn(1f, 3f)
                    val bmp = Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out.add(bmp)
                }
            }
        } finally {
            renderer.close()
        }
    } finally {
        pfd.close()
    }
    return out
}

@Composable
private fun OtherPane(model: AppModel, rel: String) {
    var info by remember(rel) { mutableStateOf<DirEntry?>(null) }
    LaunchedEffect(rel) {
        info = withContext(Dispatchers.IO) {
            model.localFiles().listDir(parentDir(rel))
                .firstOrNull { it.name == rel.substringAfterLast('/') }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val i = info
        when {
            i == null -> CircularProgressIndicator()
            else -> Text(formatBytes(i.size), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ActionRow(model: AppModel, rel: String) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var confirmPush by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                android.util.Log.d("FileSync", "Single file button clicked rel=$rel busy=${model.busy}")
                scope.launch { model.runSync { it == rel || it.startsWith("$rel/") } }
            },
            enabled = !model.busy && model.treeUri != null,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("同步此文件") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                onClick = { scope.launch { result = model.fetchRemote(rel) } },
                enabled = !model.busy,
            ) { Text("用远端覆盖本地") }
            TextButton(
                onClick = { confirmPush = true },
                enabled = !model.busy,
            ) { Text("用本地覆盖远端") }
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmPush) {
        AlertDialog(
            onDismissRequest = { confirmPush = false },
            title = { Text("覆盖远端") },
            text = { Text("远端同名文件将被替换。") },
            confirmButton = {
                TextButton({
                    confirmPush = false
                    scope.launch { result = model.pushLocal(rel) }
                }) { Text("覆盖") }
            },
            dismissButton = { TextButton({ confirmPush = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ConflictDialog(p: ConflictPrompt) {
    AlertDialog(
        onDismissRequest = { p.onChoice("skip") },
        title = { Text("冲突: ${p.info.rel}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("本地: ${formatBytes(p.info.localSize)} · ${shortSha(p.info.localSha)}")
                Text("远端: ${formatBytes(p.info.remoteSize)} · ${shortSha(p.info.remoteSha)}")
                Text("两端均被修改，保留哪个版本？")
            }
        },
        confirmButton = { TextButton({ p.onChoice("local") }) { Text("保留本地") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ p.onChoice("remote") }) { Text("保留远端") }
                TextButton({ p.onChoice("skip") }) { Text("跳过") }
            }
        },
    )
}

private fun joinDir(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

private fun parentDir(dir: String) = dir.substringBefore('/', "")

private fun shortSha(s: String) = s.take(12)

private fun formatBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = "KMGTPE"
    var div = 1024L
    var exp = 0
    var x = n / 1024
    while (x >= 1024 && exp < units.length - 1) {
        div *= 1024
        exp++
        x /= 1024
    }
    return String.format("%.1f %cB", n / div.toDouble(), units[exp])
}

@Composable
private fun CloudFileList(model: AppModel, refreshTick: Int, modifier: Modifier = Modifier) {
    var list by remember { mutableStateOf<List<RemoteFile>?>(null) }
    LaunchedEffect(refreshTick) {
        list = withContext(Dispatchers.IO) {
            runCatching { model.api().ls("") }.getOrNull()
        }
    }
    val l = list
    when {
        l == null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        l.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) { Text("云端为空") }
        else -> LazyColumn(modifier) {
            items(l) { e ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(e.path, style = MaterialTheme.typography.bodyLarge)
                        Text(formatBytes(e.size), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
