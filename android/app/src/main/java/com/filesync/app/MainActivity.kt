package com.filesync.app

import android.app.Activity
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
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.filesync.app.util.Updater
import com.filesync.app.util.sha256Hex
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.spans.LastLineSpacingSpan
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.json.JSONArray
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
sealed class Screen {
    data object Main : Screen()
    data object Settings : Screen()
    data class Preview(val rel: String) : Screen()
}

/** 共享元素转场作用域（文件名从列表"飞入"预览页标题）。 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.fileSharedBounds(key: String): Modifier {
    val sts = LocalSharedTransitionScope.current ?: return this
    val avs = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sts) {
        this@fileSharedBounds.sharedBounds(
            rememberSharedContentState(key = key),
            animatedVisibilityScope = avs,
        )
    }
}

class ConflictPrompt(val info: ConflictInfo, val onChoice: (String) -> Unit)

/** 同步完成后的按钮状态 */
enum class SyncOutcome { Success, Issues, Fail }

data class SyncResult(val outcome: SyncOutcome, val summary: String, val issues: Int)

/** 同步方案：一组"本机目录 + 云端分组"。基线按 目录+分组 独立存储，方案间互不干扰。 */
data class Profile(val id: String, val name: String, val treeUri: String?, val subDir: String)

class AppModel(val context: Context) {
    private val prefs = context.getSharedPreferences("filesync", Context.MODE_PRIVATE)

    var screen by mutableStateOf<Screen>(Screen.Main)

    // 主屏浏览状态放 Model：进预览/设置再返回时不丢失（remember 局部状态会随页面销毁重置）
    var mainDir by mutableStateOf("")
    var viewMode by mutableStateOf("local")
    var selected by mutableStateOf<Set<String>>(emptySet())
    var cloudSelected by mutableStateOf<Set<String>>(emptySet())

    // 列表滚动位置与条目缓存跨页面保留：预览返回后不再回到顶部；
    // 仅在 目录/方案 切换（key 变化）时清空并回到顶部
    val listState = LazyListState()
    var mainEntries by mutableStateOf<List<DirEntry>?>(null)
    var mainEntriesKey by mutableStateOf("")

    var host by mutableStateOf(prefs.getString("host", "127.0.0.1") ?: "127.0.0.1")
    var port by mutableStateOf(prefs.getString("port", "8080") ?: "8080")
    var token by mutableStateOf(prefs.getString("token", "") ?: "")
    var subDir by mutableStateOf(prefs.getString("subdir", "") ?: "")
    var dynamicColor by mutableStateOf(prefs.getBoolean("dynamicColor", true))
    var treeUri by mutableStateOf<String?>(prefs.getString("treeUri", null))
    var busy by mutableStateOf(false)
    var refreshTick by mutableStateOf(0)
    var conflict by mutableStateOf<ConflictPrompt?>(null)
    val logLines = mutableStateListOf<String>()


    // 同步结果反馈：底部按钮完成态 + Snackbar 摘要（简短统计 4s 自动消失）
    val snackbarHostState = SnackbarHostState()
    var syncResult by mutableStateOf<SyncResult?>(null)

    /** 执行同步并把结果写入按钮态与 Snackbar。 */
    suspend fun runAndReport(sync: suspend () -> SyncEngine.Stats?) {
        val stats = sync()
        val issues = stats?.let { it.skipped + it.failed } ?: 1
        val r = when {
            stats == null -> SyncResult(SyncOutcome.Fail, "同步失败", 1)
            issues > 0 -> SyncResult(SyncOutcome.Issues, summarizeStats(stats), issues)
            else -> SyncResult(SyncOutcome.Success, summarizeStats(stats), 0)
        }
        syncResult = r
        snackbarHostState.showSnackbar(
            r.summary,
            withDismissAction = false,
            duration = if (r.outcome == SyncOutcome.Issues) {
                SnackbarDuration.Long
            } else {
                SnackbarDuration.Short
            },
        )
        // Snackbar 消失后恢复按钮文案（用户中途切页/再次触发时同样收敛）
        if (syncResult == r) syncResult = null
    }

    private fun summarizeStats(s: SyncEngine.Stats): String {
        val parts = mutableListOf<String>()
        if (s.uploaded > 0) parts.add("上传 ${s.uploaded}")
        if (s.downloaded > 0) parts.add("下载 ${s.downloaded}")
        if (s.resolved > 0) parts.add("解决冲突 ${s.resolved}")
        if (s.cleaned > 0) parts.add("清理 ${s.cleaned}")
        if (s.warnings > 0) parts.add("需注意 ${s.warnings}")
        if (s.skipped > 0) parts.add("待处理冲突 ${s.skipped}")
        if (s.failed > 0) parts.add("失败 ${s.failed}")
        return if (parts.isEmpty()) "两端无变化" else parts.joinToString(" · ")
    }

    // 自动更新（GitHub Release）：检查 / 下载进度 / 结果提示
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var updateInfo by mutableStateOf<Updater.Info?>(null)
    var updateProgress by mutableStateOf(-1) // -1 = 未在下载
    var manualChecking by mutableStateOf(false)
    var manualCheckResult by mutableStateOf<String?>(null)
    var updateErr by mutableStateOf<String?>(null)

    fun currentVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    /** manual=false 时 24h 节流（自动检查）；manual=true 立即检查并展示结果。 */
    fun checkUpdate(manual: Boolean) {
        if (manualChecking) return
        if (!manual &&
            System.currentTimeMillis() - prefs.getLong("lastUpdateCheck", 0) < 24 * 3600 * 1000L
        ) {
            return
        }
        manualChecking = true
        updateErr = null
        if (manual) manualCheckResult = null
        modelScope.launch {
            val info = try {
                withContext(Dispatchers.IO) { Updater.check(currentVersion(), serverUrl, token.trim(), manual) }
            } catch (e: Exception) {
                manualChecking = false
                if (manual) updateErr = "检查失败: ${e.message}"
                return@launch
            }
            manualChecking = false
            prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).commit()
            if (info == null) {
                if (manual) manualCheckResult = "已是最新版本（v${currentVersion()}）"
            } else {
                updateInfo = info
            }
        }
    }

    fun downloadAndInstall() {
        val info = updateInfo ?: return
        if (updateProgress >= 0) return
        updateProgress = 0
        updateErr = null
        modelScope.launch {
            val apk = withContext(Dispatchers.IO) {
                Updater.download(info.apkUrl, File(context.cacheDir, "apk/update.apk")) { p ->
                    updateProgress = p
                }
            }
            updateProgress = -1
            if (apk == null) {
                updateErr = "下载失败，请检查网络后重试"
                return@launch
            }
            val ok = try {
                Updater.install(context, apk)
            } catch (e: Exception) {
                updateErr = "无法调起安装: ${e.message}"
                false
            }
            if (ok) updateInfo = null
        }
    }

    // ---------- 同步方案（多目录多分组） ----------

    var profiles = mutableStateListOf<Profile>()
    var currentId by mutableStateOf<String?>(null)

    fun currentProfile(): Profile? = profiles.firstOrNull { it.id == currentId }

    /** 从 prefs 载入方案列表；旧版单目录配置自动迁移为一个方案。 */
    private fun loadProfiles() {
        val raw = prefs.getString("profiles", null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    profiles.add(
                        Profile(
                            o.getString("id"),
                            o.getString("name"),
                            o.optString("treeUri").takeIf { it.isNotEmpty() },
                            o.optString("subdir"),
                        ),
                    )
                }
            } catch (_: Exception) {
            }
        } else if (treeUri != null) {
            // 旧版迁移：现有 目录+分组 → 第一个方案
            val name = try {
                DocumentFile.fromTreeUri(context, Uri.parse(treeUri!!))?.name ?: "同步目录"
            } catch (_: Exception) {
                "同步目录"
            }
            profiles.add(
                Profile(
                    java.util.UUID.randomUUID().toString(),
                    name,
                    treeUri,
                    prefs.getString("subdir", "") ?: "",
                ),
            )
        }
        currentId = prefs.getString("currentProfileId", null)
            ?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id
        // 把当前方案同步进旧字段（api()/stateStore() 等沿用）
        currentProfile()?.let { p ->
            treeUri = p.treeUri
            subDir = p.subDir
        }
    }

    fun saveProfiles() {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("treeUri", p.treeUri ?: "")
                    .put("subdir", p.subDir),
            )
        }
        prefs.edit()
            .putString("profiles", arr.toString())
            .putString("currentProfileId", currentId)
            .commit()
    }

    fun switchProfile(id: String) {
        val p = profiles.firstOrNull { it.id == id } ?: return
        currentId = id
        treeUri = p.treeUri
        subDir = p.subDir
        mainDir = ""
        selected = emptySet()
        refreshTick++
        saveProfiles()
    }

    /** 新增方案（目录未选），设为当前；随后在设置页为其选择同步目录。 */
    fun addProfile() {
        profiles.add(Profile(java.util.UUID.randomUUID().toString(), "方案 ${profiles.size + 1}", null, ""))
        currentId = profiles.last().id
        treeUri = null
        subDir = ""
        mainDir = ""
        selected = emptySet()
        saveProfiles()
        refreshTick++
    }

    fun deleteCurrentProfile() {
        val cur = currentProfile() ?: return
        if (profiles.size <= 1) return
        profiles.remove(cur)
        currentId = profiles.firstOrNull()?.id
        currentProfile()?.let { p ->
            treeUri = p.treeUri
            subDir = p.subDir
        }
        mainDir = ""
        selected = emptySet()
        saveProfiles()
        refreshTick++
    }

    fun updateCurrentProfile(transform: (Profile) -> Profile) {
        val cur = currentProfile() ?: return
        val idx = profiles.indexOfFirst { it.id == cur.id }
        if (idx >= 0) {
            profiles[idx] = transform(profiles[idx])
            saveProfiles()
        }
    }

    init {
        loadProfiles()
    }

    // 预测性返回：开关 + 跟手进度（Animatable 支持提交/取消的平滑收敛，避免旧页面弹回原尺寸）
    var predictiveBack by mutableStateOf(prefs.getBoolean("predictiveBack", true))
    val backAnimatable = Animatable(0f)
    val backProgress: Float get() = backAnimatable.value

    suspend fun settleBackProgress() {
        backAnimatable.animateTo(0f, tween(150))
    }

    fun setPredictiveBackEnabled(v: Boolean) {
        predictiveBack = v
        prefs.edit().putBoolean("predictiveBack", v).commit()
    }

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
            .putBoolean("dynamicColor", dynamicColor)
            .commit()
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
        // 目录写入当前方案。不再重置分组/基线：基线按"目录+分组"独立存储，
        // 新组合天然是空基线（首次同步自动按内容对齐），旧组合切回时基线还在
        val name = try {
            DocumentFile.fromTreeUri(context, uri)?.name ?: "同步目录"
        } catch (_: Exception) {
            "同步目录"
        }
        val cur = currentProfile()
        if (cur == null) {
            profiles.add(Profile(java.util.UUID.randomUUID().toString(), name, treeUri, subDir))
            currentId = profiles.last().id
        } else {
            updateCurrentProfile { p ->
                // 首次为方案选目录时用目录名作为方案名
                p.copy(treeUri = treeUri, name = p.treeUri?.let { p.name } ?: name)
            }
        }
        saveProfiles()
        screen = Screen.Main
        mainDir = ""
        selected = emptySet()
        cloudSelected = emptySet()
    }

    fun localFiles(): SafFiles = SafFiles(context, Uri.parse(treeUri))

    fun api(): ServerApi = HttpServerApi(serverUrl, token.trim(), subDir.trim())

    /** 全局唯一 Markwon（官方建议单例复用）：完整插件集 + 段落间距 + Obsidian 式换行。 */
    val markwon: Markwon by lazy {
        val density = context.resources.displayMetrics.density
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    val spacing = (8 * density).toInt()
                    builder.addFactory(Paragraph::class.java) { _, _ -> LastLineSpacingSpan(spacing) }
                }

                // 段落内单个换行渲染为真实换行（Obsidian 默认行为）。
                // CorePlugin 的 visitor 对 SoftLineBreak append 空格（CommonMark 规范
                // 默认折叠软换行），必须覆盖 visitor 而非 spansFactory
                override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                    builder.on(SoftLineBreak::class.java) { visitor, _ ->
                        visitor.builder().append('\n')
                    }
                }
            })
            .build()
    }

    /** 基线按"同步目录 + 分组"独立存储：修改云端子目录（分组）后无需重置，
     *  新组首次同步自动按内容对齐；切回旧组基线仍在。 */
    fun stateStore(): PrefsStateStore =
        PrefsStateStore(prefs, "state:" + (treeUri ?: "") + ":" + subDir.trim())

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
            return withContext(Dispatchers.IO) { buildEngine().sync(filter) }
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
        // Android 15 (targetSdk 35) 强制 edge-to-edge：透明状态栏 + 显式控制图标明暗
        enableEdgeToEdge()
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FileSyncApp() {
    val ctx = LocalContext.current
    val model = remember { AppModel(ctx.applicationContext) }
    val appScope = rememberCoroutineScope()
    FileSyncTheme(useDynamicColor = model.dynamicColor) {
        Surface(Modifier.fillMaxSize()) {
            // 启动自动检查 GitHub Release（24h 节流），发现新版自动弹窗
            LaunchedEffect(Unit) { model.checkUpdate(false) }
            val canGoBack = model.screen != Screen.Main || model.mainDir.isNotEmpty()
            if (model.predictiveBack) {
                // 预测性返回（官方模式）：跟手 snapTo 进度 → 提交导航后平滑收敛，
                // 取消也平滑恢复（不瞬间归零，避免旧页面弹回原尺寸的"跳一下"）
                PredictiveBackHandler(enabled = canGoBack) { backEvent ->
                    try {
                        backEvent.collect { e -> model.backAnimatable.snapTo(e.progress) }
                        if (model.screen != Screen.Main) {
                            model.screen = Screen.Main
                        } else {
                            model.mainDir = parentDir(model.mainDir)
                        }
                        model.settleBackProgress()
                    } catch (_: CancellationException) {
                        // 取消时当前协程已被取消，恢复动画放到存活的作用域里跑
                        appScope.launch { model.settleBackProgress() }
                    }
                }
            } else {
                // 关闭预测性返回：立即切换
                BackHandler(enabled = canGoBack) {
                    if (model.screen != Screen.Main) {
                        model.screen = Screen.Main
                    } else {
                        model.mainDir = parentDir(model.mainDir)
                    }
                }
            }
            SharedTransitionLayout {
                CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                    AnimatedContent(
                        targetState = model.screen,
                        transitionSpec = {
                            // 返回（目标为主屏）：内容向右推回；前进：从右滑入
                            val goingBack = targetState == Screen.Main
                            if (goingBack) {
                                (fadeIn(tween(150)) + slideInHorizontally(tween(150)) { -it / 12 })
                                    .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { it / 12 })
                            } else {
                                (fadeIn(tween(160)) + slideInHorizontally(tween(160)) { it / 12 })
                                    .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { -it / 12 })
                            }
                        },
                        label = "screen",
                    ) { s ->
                        CompositionLocalProvider(
                            LocalNavAnimatedVisibilityScope provides this@AnimatedContent,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val p = model.backProgress
                                        // 只缩放+圆角，不做透明渐隐（文字半透明很难看）
                                        scaleX = 1f - 0.1f * p
                                        scaleY = 1f - 0.1f * p
                                        shape = RoundedCornerShape(28.dp * p)
                                        clip = p > 0.02f
                                    }
                            ) {
                                when (s) {
                                    Screen.Main -> MainScreen(model)
                                    Screen.Settings -> SettingsScreen(model)
                                    is Screen.Preview -> PreviewScreen(model, s.rel)
                                }
                            }
                        }
                    }
                }
            }
            model.conflict?.let { ConflictDialog(it) }
            // 自动更新：发现新版 → 弹窗（说明 + 进度 + 调起安装）
            model.updateInfo?.let { info ->
                AlertDialog(
                    onDismissRequest = {
                        if (model.updateProgress < 0) model.updateInfo = null
                    },
                    title = { Text("发现新版本 ${info.tag}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (info.notes.isNotBlank()) {
                                Text(info.notes, style = MaterialTheme.typography.bodySmall)
                            }
                            if (model.updateProgress >= 0) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    LinearProgressIndicator(
                                        progress = { model.updateProgress / 100f },
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("${model.updateProgress}%", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            model.updateErr?.let {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { model.downloadAndInstall() },
                            enabled = model.updateProgress < 0,
                        ) {
                            Text(if (model.updateProgress >= 0) "下载中…" else "下载并安装")
                        }
                    },
                    dismissButton = {
                        TextButton({ model.updateInfo = null }) {
                            Text(if (model.updateProgress >= 0) "后台下载" else "稍后再说")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FileSyncTheme(useDynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    // 亮色主题 → 状态栏深色图标；暗色主题 → 白色图标。
    // Android 15 (targetSdk 35) 强制 edge-to-edge 后图标明暗必须显式设置，
    // 否则默认白色图标在亮色背景上完全看不清
    (ctx as? Activity)?.window?.let { w ->
        DisposableEffect(dark) {
            val controller = WindowCompat.getInsetsController(w, w.decorView)
            controller.isAppearanceLightStatusBars = !dark
            onDispose { }
        }
    }
    val scheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
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

internal fun joinDir(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

internal fun parentDir(dir: String) = dir.substringBefore('/', "")

internal fun shortSha(s: String) = s.take(12)

internal fun formatBytes(n: Long): String {
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
