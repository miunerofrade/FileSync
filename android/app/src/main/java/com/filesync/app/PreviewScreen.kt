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
internal fun PreviewScreen(model: AppModel, rel: String) {
    val ext = rel.substringAfterLast('.', "").lowercase()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        rel.substringAfterLast('/'),
                        modifier = Modifier.fileSharedBounds("file-$rel"),
                    )
                },
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
            MarkdownView(model, c, Modifier.weight(1f).fillMaxWidth().padding(12.dp))
        }
    }
}

@Composable
private fun MarkdownView(model: AppModel, markdown: String, modifier: Modifier = Modifier) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    AndroidView(
        modifier = modifier,
        factory = { c ->
            TextView(c).apply { movementMethod = LinkMovementMethod.getInstance() }
        },
        update = { tv ->
            tv.setTextColor(onSurface.toArgb())
            tv.textSize = 15f
            tv.setLineSpacing(4 * tv.resources.displayMetrics.density, 1f)
            model.markwon.setMarkdown(tv, markdown)
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { scope.launch { result = model.fetchRemote(rel) } },
                enabled = !model.busy,
                modifier = Modifier.weight(1f).height(40.dp),
            ) { Text("用远端覆盖本地", style = MaterialTheme.typography.labelLarge) }
            OutlinedButton(
                onClick = { confirmPush = true },
                enabled = !model.busy,
                modifier = Modifier.weight(1f).height(40.dp),
            ) { Text("用本地覆盖远端", style = MaterialTheme.typography.labelLarge) }
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
