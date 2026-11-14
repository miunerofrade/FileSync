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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(model: AppModel) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, model.treeUri) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    var cloudList by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(model.mainDir, model.refreshTick, model.treeUri) {
        val key = "${model.treeUri}|${model.mainDir}"
        if (model.mainEntriesKey != key) {
            // 目录/方案切换：清空旧条目并回到顶部；预览/设置返回时 key 未变，位置保留
            model.mainEntriesKey = key
            model.mainEntries = null
            model.listState.scrollToItem(0)
        }
        model.mainEntries = if (model.treeUri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    model.localFiles().listDir(model.mainDir)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
    LaunchedEffect(model.viewMode, model.refreshTick, model.treeUri) {
        if (model.viewMode == "cloud" && model.treeUri != null) {
            cloudList = withContext(Dispatchers.IO) {
                runCatching { model.api().ls("") }.getOrNull()
            }
        }
    }
    // 底部主按钮：一钮三态（全量同步 / 同步选中的 N 个文件 / 删除云端选中的 N 个文件），
    // 颜色随语义渐变，放底部便于单手触达
    val deleteMode = model.viewMode == "cloud" && model.cloudSelected.isNotEmpty()
    val selectionSync = model.viewMode == "local" && model.selected.isNotEmpty()
    val btnContainer by animateColorAsState(
        when {
            deleteMode -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.primary
        },
        label = "btnContainer",
    )
    val btnContent = when {
        deleteMode -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onPrimary
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 左上角 = 同步方案切换器：当前方案名 + 分组，点击弹方案列表
                    var menuOpen by remember { mutableStateOf(false) }
                    val cur = model.currentProfile()
                    Box {
                        val caretRot by animateFloatAsState(
                            if (menuOpen) 180f else 0f,
                            label = "caret",
                        )
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { menuOpen = true }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    if (model.mainDir.isEmpty()) {
                                        cur?.name ?: "FileSync"
                                    } else {
                                        model.mainDir.substringAfterLast('/')
                                    },
                                )
                                // 当前分组始终可见，避免"在往哪个组同步"产生歧义
                                Text(
                                    "云端分组: " + model.subDir.trim().ifEmpty { "（根目录）" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "切换同步方案",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.graphicsLayer { rotationZ = caretRot },
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            // 谷歌风格：大圆角容器 + 色调层级
                            shape = RoundedCornerShape(20.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 3.dp,
                            shadowElevation = 8.dp,
                        ) {
                            model.profiles.forEach { p ->
                                val selected = p.id == model.currentId
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                p.name + (p.treeUri?.let { "" } ?: "（未选目录）"),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                            )
                                            // 同名方案靠分组副文本区分
                                            Text(
                                                "云端分组: " + p.subDir.trim().ifEmpty { "（根目录）" },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        if (selected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        } else {
                                            Spacer(Modifier.width(24.dp)) // 未选中占位，保持文字对齐
                                        }
                                    },
                                    onClick = {
                                        menuOpen = false
                                        model.switchProfile(p.id)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            "管理方案（设置）",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            "添加 / 删除方案，切换同步目录",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    model.screen = Screen.Settings
                                },
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (model.mainDir.isNotEmpty()) {
                        IconButton(onClick = { model.mainDir = parentDir(model.mainDir) }) {
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
        bottomBar = {
            Button(
                onClick = {
                    when {
                        deleteMode -> showBatchDeleteConfirm = true
                        selectionSync -> {
                            val sel = model.selected
                            scope.launch {
                                model.runSync { p -> sel.any { s -> p == s || p.startsWith("$s/") } }
                                model.selected = emptySet()
                            }
                        }
                        else -> scope.launch { model.runSync(null) }
                    }
                },
                enabled = !model.busy,
                colors = ButtonDefaults.buttonColors(containerColor = btnContainer, contentColor = btnContent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding()
                    .height(52.dp),
            ) {
                if (model.busy) {
                    // 同步进行中：图标持续旋转
                    val angle = rememberInfiniteTransition(label = "spin")
                        .animateFloat(
                            0f, 360f,
                            infiniteRepeatable(tween(900, easing = LinearEasing)),
                            label = "angle",
                        ).value
                    Icon(
                        Icons.Default.Refresh, null,
                        Modifier.graphicsLayer { rotationZ = angle },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("同步中")
                } else {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(12.dp))
                    AnimatedContent(
                        targetState = when {
                            deleteMode -> "删除云端选中的 ${model.cloudSelected.size} 个文件"
                            selectionSync -> "同步选中的 ${model.selected.size} 个文件"
                            else -> "全量同步"
                        },
                        transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) },
                        label = "btnLabel",
                    ) { Text(it) }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (model.treeUri == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { model.screen = Screen.Settings }) { Text("选择同步目录") }
                }
                return@Column
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = model.viewMode == "local",
                    onClick = { model.viewMode = "local" },
                    label = { Text("本地") },
                )
                FilterChip(
                    selected = model.viewMode == "cloud",
                    onClick = { model.viewMode = "cloud" },
                    label = { Text("云端") },
                )
                if (model.viewMode == "local") {
                    Spacer(Modifier.weight(1f))
                    val curFiles = (model.mainEntries ?: emptyList()).filter { !it.isDir }
                        .map { joinDir(model.mainDir, it.name) }
                    val allSel = curFiles.isNotEmpty() && curFiles.all { it in model.selected }
                    TextButton(
                        onClick = {
                            model.selected =
                                if (allSel) model.selected - curFiles.toSet()
                                else model.selected + curFiles.toSet()
                        },
                        enabled = curFiles.isNotEmpty(),
                    ) { Text(if (allSel) "取消全选" else "全选") }
                } else {
                    Spacer(Modifier.weight(1f))
                    val cloudFiles = (cloudList ?: emptyList()).map { it.path }
                    val allSel = cloudFiles.isNotEmpty() && cloudFiles.all { it in model.cloudSelected }
                    TextButton(
                        onClick = {
                            model.cloudSelected =
                                if (allSel) model.cloudSelected - cloudFiles.toSet()
                                else model.cloudSelected + cloudFiles.toSet()
                        },
                        enabled = cloudFiles.isNotEmpty(),
                    ) { Text(if (allSel) "取消全选" else "全选") }
                }
            }
            if (model.viewMode == "cloud") {
                CloudFileList(
                    model = model,
                    refreshTick = model.refreshTick,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                return@Column
            }
            val e = model.mainEntries
            when {
                e == null -> SkeletonList(8, Modifier.weight(1f).fillMaxWidth())
                else -> LazyColumn(state = model.listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(e) { en ->
                            val rel = joinDir(model.mainDir, en.name)
                            if (en.isDir) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .animateItem()
                                        .clickable { model.mainDir = rel }
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                Checkbox(
                                    checked = rel in model.selected,
                                    onCheckedChange = { on ->
                                        model.selected =
                                            if (on) model.selected + rel else model.selected - rel
                                    },
                                )
                                Text("▸ ", color = MaterialTheme.colorScheme.primary)
                                Text(en.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        } else {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                                    .clickable { model.screen = Screen.Preview(rel) }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = rel in model.selected,
                                    onCheckedChange = { on ->
                                        model.selected =
                                            if (on) model.selected + rel else model.selected - rel
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        en.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.fileSharedBounds("file-$rel"),
                                    )
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
    if (showBatchDeleteConfirm) {
        val targets = model.cloudSelected
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("删除云端文件") },
            text = {
                Text(
                    "将只删除云端的 ${targets.size} 个文件：\n" +
                        targets.sorted().joinToString("\n") { "· $it" } +
                        "\n\n本地文件不会被改动。",
                )
            },
            confirmButton = {
                TextButton({
                    showBatchDeleteConfirm = false
                    scope.launch {
                        val failed = mutableListOf<String>()
                        var ok = 0
                        withContext(Dispatchers.IO) {
                            for (p in targets) {
                                try {
                                    if (model.api().delete(p)) ok++ else failed.add(p)
                                } catch (e: Exception) {
                                    failed.add(p)
                                }
                            }
                        }
                        model.cloudSelected = emptySet()
                        model.refreshTick++
                        model.log(
                            "批量删除云端: 成功 $ok" +
                                if (failed.isNotEmpty()) ", 失败 ${failed.size}: ${failed.joinToString("、")}" else "",
                        )
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton({ showBatchDeleteConfirm = false }) { Text("取消") } },
        )
    }
}

/** shimmer 骨架屏：列表加载中的占位（无第三方依赖）。 */
@Composable
private fun SkeletonList(rows: Int, modifier: Modifier = Modifier) {
    val x = rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f,
        1600f,
        infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "shx",
    ).value
    val base = MaterialTheme.colorScheme.surfaceVariant
    val hl = MaterialTheme.colorScheme.surface
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(rows) {
            val brush = Brush.linearGradient(
                listOf(base, hl, base),
                start = Offset(x - 400f, 0f),
                end = Offset(x, 400f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(brush),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth(0.55f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(brush),
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.35f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(brush),
                    )
                }
            }
        }
    }
}
@Composable
private fun CloudFileList(model: AppModel, refreshTick: Int, modifier: Modifier = Modifier) {
    var list by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refreshTick) {
        list = withContext(Dispatchers.IO) {
            runCatching { model.api().ls("") }.getOrNull()
        }
    }
    val l = list
    when {
        l == null -> SkeletonList(6, modifier)
        l.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) { Text("云端为空") }
        else -> LazyColumn(modifier) {
            items(l) { e ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = e.path in model.cloudSelected,
                        onCheckedChange = { on ->
                            model.cloudSelected =
                                if (on) model.cloudSelected + e.path else model.cloudSelected - e.path
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(e.path, style = MaterialTheme.typography.bodyLarge)
                        Text(formatBytes(e.size), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { confirmDelete = e.path }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除云端文件 ${e.path}")
                    }
                }
            }
        }
    }
    confirmDelete?.let { path ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除云端文件") },
            text = {
                Text("将只删除云端的 $path。\n本地文件不会被改动；下次同步会提示\u201c服务器已不存在，本地保留\u201d。")
            },
            confirmButton = {
                TextButton({
                    confirmDelete = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            try {
                                model.api().delete(path)
                            } catch (e: Exception) {
                                model.log("删除失败 $path: ${e.message}")
                                false
                            }
                        }
                        if (ok) {
                            model.log("已删除云端 $path")
                            model.refreshTick++ // 触发云端列表刷新
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("取消") } },
        )
    }
}
