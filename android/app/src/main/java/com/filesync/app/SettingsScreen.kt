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
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(model: AppModel) {
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDeleteProfile by remember { mutableStateOf(false) }
    var showGroupInfo by remember { mutableStateOf(false) }
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
    // 导出配置：server/token/subdir 三字段（不含本机同步目录 treeUri），与 CLI --config 兼容
    val cfgExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                val o = JSONObject()
                o.put("server", model.serverUrl)
                o.put("token", model.token.trim())
                o.put("subdir", model.subDir.trim())
                model.context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(o.toString(2).toByteArray(Charsets.UTF_8))
                } ?: return@runCatching
                model.log("配置已导出")
            }.onFailure { model.log("导出失败: ${it.message}") }
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
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("服务器")
            OutlinedTextField(
                value = model.host,
                onValueChange = { model.host = it; model.saveSettings() },
                label = { Text("服务器地址（IP 或 https://IP:端口）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = model.port,
                onValueChange = { model.port = it; model.saveSettings() },
                label = { Text("端口（填完整 URL 时忽略）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = model.token,
                onValueChange = { model.token = it; model.saveSettings() },
                label = { Text("访问令牌") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ListItem(
                headlineContent = { Text("测试连接") },
                supportingContent = {
                    testResult?.let { Text(it) } ?: Text("发送 ping 并统计云端文件数")
                },
                trailingContent = {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                },
                modifier = Modifier.clickable(enabled = !testing) {
                    testing = true
                    scope.launch {
                        testResult = model.testConnection()
                        testing = false
                    }
                },
            )
            SectionHeader("同步")
            OutlinedTextField(
                value = model.subDir,
                onValueChange = {
                    model.subDir = it
                    model.updateCurrentProfile { p -> p.copy(subDir = it.trim()) }
                    model.saveSettings()
                },
                label = { Text("云端子目录（可选，同步组）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                trailingIcon = {
                    IconButton(onClick = { showGroupInfo = true }) {
                        Icon(Icons.Default.Info, contentDescription = "分组说明")
                    }
                },
            )
            val dirName = model.treeUri?.let { uriStr ->
                remember(uriStr) {
                    DocumentFile.fromTreeUri(model.context, Uri.parse(uriStr))?.name ?: uriStr
                }
            }
            ListItem(
                headlineContent = { Text(if (model.treeUri == null) "选择同步目录" else "同步目录") },
                supportingContent = {
                    Text(
                        dirName ?: "点击选择（SAF，权限持久化）",
                    )
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickable { picker.launch(null) },
            )
            ListItem(
                headlineContent = { Text("添加方案") },
                supportingContent = { Text("新建一组 本机目录 + 云端分组，互不干扰") },
                trailingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                modifier = Modifier.clickable {
                    model.addProfile()
                    picker.launch(null) // 新方案立即选目录
                },
            )
            ListItem(
                headlineContent = { Text("同步日志") },
                supportingContent = { Text("最近 ${LOG_MAX} 条同步记录（上传 / 下载 / 冲突 / 基线重置）") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickable { model.screen = Screen.Log },
            )
            if (model.treeUri != null) {
                ListItem(
                    headlineContent = {
                        Text("重置同步基线", color = MaterialTheme.colorScheme.error)
                    },
                    supportingContent = {
                        Text(
                            "下次同步将重新比较两端内容，内容不同的文件需手动选择版本",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable { confirmReset = true },
                )
            }
            if (model.profiles.size > 1) {
                ListItem(
                    headlineContent = {
                        Text("删除当前方案", color = MaterialTheme.colorScheme.error)
                    },
                    supportingContent = {
                        Text(
                            "移除\"${model.currentProfile()?.name}\"（云端与本机文件均不受影响）",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable { confirmDeleteProfile = true },
                )
            }
            SectionHeader("外观")
            ListItem(
                headlineContent = { Text("动态取色") },
                supportingContent = { Text("Material You：跟随系统壁纸取色（Android 12+ 生效）") },
                trailingContent = {
                    Switch(
                        checked = model.dynamicColor,
                        onCheckedChange = { model.dynamicColor = it; model.saveSettings() },
                    )
                },
                modifier = Modifier.clickable {
                    model.dynamicColor = !model.dynamicColor
                    model.saveSettings()
                },
            )
            ListItem(
                headlineContent = { Text("预测性返回手势") },
                supportingContent = { Text("返回时跟手缩放过渡；关闭后立即切换页面") },
                trailingContent = {
                    Switch(
                        checked = model.predictiveBack,
                        onCheckedChange = { model.setPredictiveBackEnabled(it) },
                    )
                },
                modifier = Modifier.clickable {
                    model.setPredictiveBackEnabled(!model.predictiveBack)
                },
            )
            SectionHeader("配置")
            ListItem(
                headlineContent = { Text("检查更新") },
                supportingContent = {
                    when {
                        model.manualChecking -> Text("正在检查…")
                        model.updateErr != null -> Text(
                            model.updateErr!!,
                            color = MaterialTheme.colorScheme.error,
                        )
                        model.manualCheckResult != null -> Text(model.manualCheckResult!!)
                        else -> Text("当前版本 v${model.currentVersion()}（启动时也会自动检查）")
                    }
                },
                trailingContent = {
                    if (model.manualChecking) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                },
                modifier = Modifier.clickable(enabled = !model.manualChecking) {
                    model.checkUpdate(true)
                },
            )
            ListItem(
                headlineContent = { Text("导出配置") },
                supportingContent = { Text("JSON：服务器 / 令牌 / 分组（不含本机同步目录）") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickable { cfgExporter.launch("filesync-client.json") },
            )
            ListItem(
                headlineContent = { Text("从文件导入配置") },
                supportingContent = { Text("JSON 文件：服务器 / 令牌 / 分组") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickable {
                    cfgImporter.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showGroupInfo) {
        AlertDialog(
            onDismissRequest = { showGroupInfo = false },
            title = { Text("云端分组") },
            text = {
                Text(
                    "分组即云端子目录：相同分组的设备互相同步，不同分组互相隔离。\n\n" +
                        "修改分组即换同步组：基线按分组独立保存，本地文件不受影响，改完直接同步即可。",
                )
            },
            confirmButton = { TextButton({ showGroupInfo = false }) { Text("知道了") } },
        )
    }
    if (confirmDeleteProfile) {
        AlertDialog(
            onDismissRequest = { confirmDeleteProfile = false },
            title = { Text("删除当前方案") },
            text = {
                Text(
                    "将移除方案\"${model.currentProfile()?.name}\"（本机目录 + 分组绑定）。\n" +
                        "云端与本机文件都不会被改动；重新添加方案并选回目录即可恢复。",
                )
            },
            confirmButton = {
                TextButton({
                    confirmDeleteProfile = false
                    model.deleteCurrentProfile()
                }) { Text("删除") }
            },
            dismissButton = { TextButton({ confirmDeleteProfile = false }) { Text("取消") } },
        )
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
