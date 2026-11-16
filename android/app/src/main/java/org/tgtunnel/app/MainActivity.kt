package org.tgtunnel.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Background = Color(0xFF0B1118)
private val Panel = Color(0xFF151F2A)
private val PanelRaised = Color(0xFF1B2835)
private val TextPrimary = Color(0xFFEAF1F7)
private val TextSecondary = Color(0xFF91A3B5)
private val ConnectBlue = Color(0xFF75D5FF)
private val ConnectedGreen = Color(0xFFA5E6B2)
private val ErrorRed = Color(0xFFFF777F)
private val AccentPurple = Color(0xFFB7A2FF)

private const val PREFS_NAME = "tgtunnel_settings"
private const val KEY_PORT = "local_port"
private const val KEY_AUTOSTART = "autostart"
private const val KEY_VERBOSE = "verbose_logs"
private const val KEY_SECRET = "mtproto_secret"
private const val KEY_CF_WORKER = "cloudflare_worker_domain"
private const val KEY_CF_PROXY = "cloudflare_proxy_fallback"
private const val KEY_DC_IP = "dc_ip_redirects"
private const val KEY_LOG_FOLDER_URI = "log_folder_uri"
private const val KEY_LOG_FILE_URI = "log_file_uri"
private const val KEY_LOG_FILE_DAY = "log_file_day"
private const val KEY_LOG_FILE_TREE_URI = "log_file_tree_uri"
private const val DEFAULT_DC_IP = "2:149.154.167.220,4:149.154.167.220"

private val logIds = AtomicLong(0)
private val logSessionId = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) +
    "-${java.util.UUID.randomUUID().toString().take(8)}"
@Volatile private var activeLogFileUri: String? = null
@Volatile private var activeLogFolderUri: String? = null
private val logWriter = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "tgtunnel-log-writer").apply { isDaemon = true }
}

private enum class Screen { HOME, SETTINGS, LOGS }
private enum class ConnectionState { DISCONNECTED, CONNECTED, ERROR }

internal data class AppSettings(
    val port: String = "1443",
    val secret: String = "",
    val autostart: Boolean = false,
    val verboseLogs: Boolean = false,
    val workerDomain: String = "",
    val cfProxyFallback: Boolean = true,
    val dcIp: String = DEFAULT_DC_IP,
    val logFolderUri: String = ""
)

private data class LogEntry(
    val id: Long,
    val time: String,
    val message: String
)

internal object TunnelEvents {
    @Volatile var listener: ((String) -> Unit)? = null
    fun emit(message: String) { listener?.invoke(message) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(11, 17, 24)
        window.navigationBarColor = android.graphics.Color.rgb(11, 17, 24)

        setContent { TgTunnelApp() }
    }
}

@Composable
private fun TgTunnelApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    var settings by remember {
        mutableStateOf(
            AppSettings(
                port = preferences.getInt(KEY_PORT, 1443).toString(),
                secret = preferences.getString(KEY_SECRET, null) ?: generateSecret().also { preferences.edit().putString(KEY_SECRET, it).apply() },
                autostart = preferences.getBoolean(KEY_AUTOSTART, false),
                verboseLogs = preferences.getBoolean(KEY_VERBOSE, false),
                workerDomain = preferences.getString(KEY_CF_WORKER, "") ?: "",
                cfProxyFallback = preferences.getBoolean(KEY_CF_PROXY, true),
                dcIp = preferences.getString(KEY_DC_IP, DEFAULT_DC_IP) ?: DEFAULT_DC_IP,
                logFolderUri = preferences.getString(KEY_LOG_FOLDER_URI, "") ?: ""
            )
        )
    }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var connectionState by remember { mutableStateOf(ConnectionState.DISCONNECTED) }
    var connectionBusy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var logs by remember {
        mutableStateOf(
            listOf(
                newLog("TGTunnel запущен"),
                newLog("Ожидание подключения")
            )
        )
    }

    fun updateSettings(next: AppSettings) {
        val folderChanged = next.logFolderUri != settings.logFolderUri
        settings = next
        val editor = preferences.edit()
            .putInt(KEY_PORT, next.port.toIntOrNull() ?: 1443)
            .putString(KEY_SECRET, next.secret)
            .putBoolean(KEY_AUTOSTART, next.autostart)
            .putBoolean(KEY_VERBOSE, next.verboseLogs)
            .putString(KEY_CF_WORKER, next.workerDomain)
            .putBoolean(KEY_CF_PROXY, next.cfProxyFallback)
            .putString(KEY_DC_IP, next.dcIp)
            .putString(KEY_LOG_FOLDER_URI, next.logFolderUri)
        if (folderChanged) editor.remove(KEY_LOG_FILE_URI).remove(KEY_LOG_FILE_DAY).remove(KEY_LOG_FILE_TREE_URI)
        editor.apply()
    }

    fun addLog(message: String) {
        val entry = newLog(message)
        logs = (logs + entry).takeLast(1000)
        // File logging is deliberately opt-in: no selected folder means no persistent log writes.
        if (settings.logFolderUri.isNotBlank()) {
            appendPersistentLog(context.applicationContext, settings.logFolderUri, entry)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            updateSettings(settings.copy(logFolderUri = uri.toString()))
            addLog("Папка для файлового логирования выбрана")
        }
    }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    LaunchedEffect(Unit) {
        TunnelEvents.listener = { message ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                when {
                    message.startsWith("SERVICE_STATE:CONNECTED") -> connectionState = ConnectionState.CONNECTED
                    message.startsWith("SERVICE_STATE:ERROR") -> connectionState = ConnectionState.ERROR
                    message.startsWith("SERVICE_STATE:STOPPED") -> connectionState = ConnectionState.DISCONNECTED
                }
                if (!message.startsWith("SERVICE_STATE:")) addLog(message)
                else addLog(message.substringAfter(':'))
            }
        }
        if (NativeBridge.isRunning()) {
            connectionState = ConnectionState.CONNECTED
        } else if (settings.autostart) {
            startTunnelService(context, settings)
            addLog("Запрошен фоновый автозапуск TGTunnel")
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { TunnelEvents.listener = null }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ConnectBlue,
            secondary = AccentPurple,
            background = Background,
            surface = Panel,
            onBackground = TextPrimary,
            onSurface = TextPrimary
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    settings = settings,
                    state = connectionState,
                    logs = logs,
                    busy = connectionBusy,
                    onSettings = { screen = Screen.SETTINGS },
                    onOpenLogs = { screen = Screen.LOGS },
                    onConnect = {
                        val port = settings.port.toIntOrNull()
                        if (port == null || port !in 1..65535) {
                            connectionState = ConnectionState.ERROR
                            addLog("Некорректный порт")
                        } else {
                            if (!connectionBusy) {
                                connectionBusy = true
                                scope.launch {
                                    startTunnelService(context, settings)
                                    connectionState = ConnectionState.CONNECTED
                                    addLog("Запуск фонового сервиса TGTunnel…")
                                    connectionBusy = false
                                }
                            }
                        }
                    },
                    onStop = {
                        if (!connectionBusy) {
                            connectionBusy = true
                            scope.launch {
                                stopTunnelService(context)
                                connectionState = ConnectionState.DISCONNECTED
                                addLog("Запрошена остановка TGTunnel")
                                connectionBusy = false
                            }
                        }
                    }
                )
                Screen.SETTINGS -> SettingsScreen(
                    settings = settings,
                    engineInfo = NativeBridge.info(),
                    onBack = { screen = Screen.HOME },
                    onSettingsChanged = ::updateSettings,
                    onChooseLogFolder = { folderPicker.launch(null) },
                    onClearLogFolder = {
                        updateSettings(settings.copy(logFolderUri = ""))
                        addLog("Файловое логирование отключено")
                    }
                )
                Screen.LOGS -> LogViewerScreen(
                    logs = logs,
                    onBack = { screen = Screen.HOME }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    settings: AppSettings,
    state: ConnectionState,
    logs: List<LogEntry>,
    busy: Boolean,
    onSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onConnect: () -> Unit,
    onStop: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Header(onSettings = onSettings, state = state)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
        item(key = "connection") {
            ConnectionCard(
                state = state,
                port = settings.port,
                busy = busy,
                onConnect = onConnect,
                onStop = onStop
            )
        }
        item(key = "section-connection") { SectionTitle("ПОДКЛЮЧЕНИЕ") }
        item(key = "connection-info") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoCard(
                    title = "ЛОКАЛЬНЫЙ АДРЕС",
                    value = "127.0.0.1",
                    subtitle = "Порт ${settings.port}",
                    modifier = Modifier.weight(1f)
                )
                InfoCard(
                    title = "ПРОТОКОЛ",
                    value = "MTProto",
                    subtitle = "Telegram",
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item(key = "secret") { SecretCard(secret = settings.secret, port = settings.port) }
        item(key = "engine-title") { SectionTitle("ДВИЖОК") }
        item(key = "engine") { EngineCard() }
        item(key = "logs-title") { SectionTitle("ЖУРНАЛ СОБЫТИЙ") }
        item(key = "logs") { LogsCard(logs, onOpenLogs) }
        item(key = "footer") { Footer(onSettings = onSettings) }
        }
    }
}

@Composable
private fun Header(onSettings: () -> Unit, state: ConnectionState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(Panel)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = PanelRaised, shape = RoundedCornerShape(15.dp)) {
                Box(modifier = Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    Text("T", color = ConnectBlue, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("TGTunnel", color = TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("MTProto · Android", color = TextSecondary, fontSize = 11.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                Modifier.size(8.dp).background(
                    when (state) {
                        ConnectionState.CONNECTED -> ConnectedGreen
                        ConnectionState.ERROR -> ErrorRed
                        ConnectionState.DISCONNECTED -> TextSecondary
                    }, CircleShape
                )
            )
            TextButton(onClick = onSettings) {
                Text("⚙", color = TextPrimary, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: ConnectionState,
    port: String,
    busy: Boolean,
    onConnect: () -> Unit,
    onStop: () -> Unit
) {
    val buttonColor = when (state) {
        ConnectionState.CONNECTED -> ConnectedGreen
        else -> ConnectBlue
    }
    val statusColor = when (state) {
        ConnectionState.CONNECTED -> ConnectedGreen
        ConnectionState.ERROR -> ErrorRed
        ConnectionState.DISCONNECTED -> TextSecondary
    }
    val statusText = when (state) {
        ConnectionState.CONNECTED -> "Прокси запущен"
        ConnectionState.ERROR -> "Ошибка запуска"
        ConnectionState.DISCONNECTED -> "Отключено"
    }
    val description = when (state) {
        ConnectionState.CONNECTED -> "Локальный порт слушает; Telegram подключится при первом запросе"
        ConnectionState.ERROR -> "Движок не смог запустить прокси"
        ConnectionState.DISCONNECTED -> "Локальный MTProto-прокси не запущен"
    }

    val cardShape = RoundedCornerShape(24.dp)
    val connectedHighlight = state == ConnectionState.CONNECTED
    val cardBackground = if (connectedHighlight) Color(0xFF10271F) else Panel
    val cardBorder = when (state) {
        ConnectionState.CONNECTED -> ConnectedGreen.copy(alpha = 0.75f)
        ConnectionState.ERROR -> ErrorRed.copy(alpha = 0.65f)
        ConnectionState.DISCONNECTED -> Color.Transparent
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, cardBorder, cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(21.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Text(
                "СОСТОЯНИЕ ПОДКЛЮЧЕНИЯ",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(9.dp).background(statusColor, CircleShape))
                Text(statusText, color = statusColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Text(description, color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = if (state == ConnectionState.CONNECTED) onStop else onConnect,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = Color(0xFF101820)
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
            ) {
                Text(
                    when {
                        busy -> "ЗАПУСК..."
                        state == ConnectionState.CONNECTED -> "ОТКЛЮЧИТЬСЯ"
                        else -> "ПОДКЛЮЧИТЬСЯ"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
            }
            Text(
                "127.0.0.1:$port",
                modifier = Modifier.fillMaxWidth(),
                color = TextSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}


@Composable
private fun SecretCard(secret: String, port: String) {
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val link = "tg://proxy?server=127.0.0.1&port=" + port + "&secret=dd" + secret
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("СЕКРЕТ MTProto", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("dd" + secret, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Ссылка добавляет локальный прокси в Telegram на этом устройстве (127.0.0.1:$port). После открытия подтверди подключение в Telegram.",
                color = TextSecondary,
                fontSize = 11.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(link)) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("КОПИРОВАТЬ tg://", color = ConnectBlue, fontSize = 10.sp)
                }
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                        } catch (_: android.content.ActivityNotFoundException) {
                            Toast.makeText(context, "Telegram не найден", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("ОТКРЫТЬ В TELEGRAM", color = ConnectBlue, fontSize = 10.sp)
                }
            }
        }
    }
}

private fun generateSecret(): String {
    val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
    return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
}

@Composable
private fun InfoCard(title: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun EngineCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EngineRow("Платформа", "Android only")
            EngineRow("Язык движка", "Kotlin / JVM")
            EngineRow("Интерфейс", "Kotlin + Compose")
            EngineRow("Транспорт", "Telegram WS + CF Worker + CF Proxy + TCP", ConnectedGreen)
        }
    }
}

@Composable
private fun EngineRow(title: String, value: String, valueColor: Color = TextPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = TextSecondary, fontSize = 12.sp)
        Text(value, color = valueColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LogsCard(logs: List<LogEntry>, onOpenLogs: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val logText = buildString {
        appendLine("TGTunnel — журнал событий")
        appendLine("Записей в памяти: ${logs.size}")
        appendLine()
        append(logs.joinToString("\n") { "[${it.time}] ${it.message}" })
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("ПОСЛЕДНИЕ СОБЫТИЯ", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("${logs.size} / 1000", color = TextSecondary, fontSize = 10.sp)
            }
            logs.takeLast(6).asReversed().forEach { entry ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Text(entry.time, color = ConnectBlue, fontSize = 10.sp)
                    Text(entry.message, color = logColor(entry.message), fontSize = 12.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onOpenLogs) {
                    Text("ОТКРЫТЬ ЖУРНАЛ  →", color = ConnectBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(logText)) }) {
                    Text("КОПИРОВАТЬ", color = ConnectBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun LogViewerScreen(logs: List<LogEntry>, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var search by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf("ВСЕ") }
    var followLatest by remember { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }

    val visibleLogs = remember(logs, search, selectedLevel) {
        logs.filter { entry ->
            val levelMatches = selectedLevel == "ВСЕ" || logLevel(entry.message) == selectedLevel
            levelMatches && (search.isBlank() ||
                entry.message.contains(search, ignoreCase = true) ||
                entry.time.contains(search, ignoreCase = true))
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index
            Triple(listState.isScrollInProgress, lastVisible, layout.totalItemsCount)
        }.collect { (scrolling, lastVisible, total) ->
            if (scrolling && !programmaticScroll && lastVisible != null && lastVisible < total - 1) {
                followLatest = false
            } else if (!scrolling && lastVisible != null && total > 0 && lastVisible >= total - 1) {
                followLatest = true
            }
        }
    }

    LaunchedEffect(visibleLogs.size, followLatest) {
        if (followLatest && visibleLogs.isNotEmpty()) {
            programmaticScroll = true
            try {
                listState.animateScrollToItem(visibleLogs.lastIndex)
            } finally {
                programmaticScroll = false
            }
        }
    }

    val copiedText = remember(visibleLogs) {
        buildString {
            appendLine("TGTunnel — журнал событий")
            appendLine("Записей: ${visibleLogs.size}")
            appendLine()
            append(visibleLogs.joinToString("\n") { "[${it.time}] ${it.message}" })
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().background(Panel)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("← НАЗАД", color = ConnectBlue, fontSize = 12.sp) }
            Column(horizontalAlignment = Alignment.End) {
                Text("Журнал событий", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("LIVE · ${visibleLogs.size} записей", color = ConnectedGreen, fontSize = 10.sp)
            }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            singleLine = true,
            label = { Text("Поиск по журналу") },
            placeholder = { Text("DNS, WebSocket, ошибка...") }
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("ВСЕ", "CONNECT", "INFO", "WARN", "ERROR").forEach { level ->
                TextButton(onClick = { selectedLevel = level }) {
                    Text(
                        level,
                        color = if (selectedLevel == level) ConnectBlue else TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = if (selectedLevel == level) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (followLatest) "Автопрокрутка включена" else "Просмотр истории · автопрокрутка выключена",
                color = TextSecondary,
                fontSize = 10.sp
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(copiedText)) }) {
                Text("КОПИРОВАТЬ", color = ConnectBlue, fontSize = 10.sp)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(visibleLogs, key = { it.id }) { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(logBackground(entry.message), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(entry.time, color = TextSecondary, fontSize = 10.sp)
                    Text(
                        logLevel(entry.message),
                        color = logColor(entry.message),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        entry.message,
                        color = logColor(entry.message),
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (!followLatest) {
            Button(
                onClick = {
                    followLatest = true
                    if (visibleLogs.isNotEmpty()) {
                        // The state update also triggers the automatic scroll effect.
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ConnectBlue, contentColor = Background)
            ) {
                Text("↓  К ПОСЛЕДНИМ СОБЫТИЯМ", fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun logLevel(message: String): String {
    val value = message.lowercase(Locale.ROOT)
    return when {
        listOf("error", "failed", "exception", "unable", "не удалось", "ошибка", "abort").any { value.contains(it) } -> "ERROR"
        listOf("warning", "inconclusive", "retry", "предупреждение").any { value.contains(it) } -> "WARN"
        listOf(
            "websocket established",
            "client connected",
            "connected to ",
            "first binary response",
            "route promoted",
            "route selected",
            "domain healthy",
            "local listener started"
        ).any { value.contains(it) } -> "CONNECT"
        else -> "INFO"
    }
}

private fun logColor(message: String): Color = when (logLevel(message)) {
    "ERROR" -> ErrorRed
    "WARN" -> AccentPurple
    "CONNECT" -> ConnectedGreen
    else -> TextPrimary
}

private fun logBackground(message: String): Color =
    if (logLevel(message) == "CONNECT") Color(0xFF10271F) else Panel

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    engineInfo: String,
    onBack: () -> Unit,
    onSettingsChanged: (AppSettings) -> Unit,
    onChooseLogFolder: () -> Unit,
    onClearLogFolder: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().background(Panel).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Настройки", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("ГОТОВО", color = ConnectBlue) }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        item {
            SettingsPanel {
                Text("ЛОКАЛЬНЫЙ ПРОКСИ", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = settings.port,
                    onValueChange = { value ->
                        if (value.length <= 5 && value.all(Char::isDigit)) {
                            onSettingsChanged(settings.copy(port = value))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Порт") },
                    supportingText = { Text("Допустимый диапазон: 1–65535") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = settings.dcIp,
                    onValueChange = { onSettingsChanged(settings.copy(dcIp = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("DC → IP") },
                    placeholder = { Text(DEFAULT_DC_IP) },
                    supportingText = { Text("Перенаправление прямого WebSocket-подключения. Формат DC:IPv4, несколько записей через запятую.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
            }
        }
        item {
            SettingsPanel {
                Text("CLOUDFLARE WORKER", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = settings.workerDomain,
                    onValueChange = { onSettingsChanged(settings.copy(workerDomain = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Домен Worker") },
                    placeholder = { Text("name.username.workers.dev") },
                    supportingText = {
                        Text("Необязательно. Укажи домен своего Worker без https://; несколько доменов — через запятую.")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Text(
                    "Worker — отдельный туннель через твой Cloudflare Worker. Он пробуется до автоматического CF Proxy.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                SettingSwitch(
                    title = "Cloudflare Proxy fallback",
                    subtitle = "Автоматический пул доменов Flowseal, без собственного домена",
                    checked = settings.cfProxyFallback,
                    onCheckedChange = { onSettingsChanged(settings.copy(cfProxyFallback = it)) }
                )
            }
        }
        item {
            SettingsPanel {
                Text("ПОВЕДЕНИЕ", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                SettingSwitch(
                    title = "Автозапуск",
                    subtitle = "Запускать прокси при старте приложения",
                    checked = settings.autostart,
                    onCheckedChange = { onSettingsChanged(settings.copy(autostart = it)) }
                )
                SettingSwitch(
                    title = "Подробные логи",
                    subtitle = "Больше диагностической информации",
                    checked = settings.verboseLogs,
                    onCheckedChange = { onSettingsChanged(settings.copy(verboseLogs = it)) }
                )
            }
        }
        item {
            SettingsPanel {
                Text("ФАЙЛОВОЕ ЛОГИРОВАНИЕ", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (settings.logFolderUri.isBlank()) "Папка не выбрана · запись файлов отключена" else "Папка выбрана · события будут записываться в TGTunnel-YYYY-MM-DD.log",
                    color = if (settings.logFolderUri.isBlank()) TextSecondary else ConnectedGreen,
                    fontSize = 12.sp
                )
                Button(onClick = onChooseLogFolder, modifier = Modifier.fillMaxWidth()) {
                    Text("ВЫБРАТЬ ПАПКУ ДЛЯ ЛОГИРОВАНИЯ")
                }
                if (settings.logFolderUri.isNotBlank()) {
                    TextButton(onClick = onClearLogFolder, modifier = Modifier.fillMaxWidth()) {
                        Text("ОТКЛЮЧИТЬ ФАЙЛОВОЕ ЛОГИРОВАНИЕ", color = ErrorRed)
                    }
                }
                Text(
                    "TGTunnel создаёт отдельный текстовый файл на каждый день. Если папка не выбрана, файлы не создаются; короткий журнал внутри приложения продолжает работать.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
        item {
            SettingsPanel {
                Text("NATIVE CORE", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(engineInfo, color = TextPrimary, fontSize = 13.sp)
                Text(
                    "Локальный listener принимает MTProto. Порядок: Telegram WS → Worker (если настроен) → CF Proxy → прямой TCP.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
        item {
            SettingsPanel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("ПРОЕКТ НА GITHUB", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("TGTunnel", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("↗", color = ConnectBlue, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Исходный код, новые версии и сообщения об ошибках — всё в репозитории проекта.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Text(
                    "github.com/DevAyuGlass/TgTunnel",
                    color = ConnectBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/DevAyuGlass/TgTunnel"))
                            )
                        } catch (_: android.content.ActivityNotFoundException) {
                            Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ОТКРЫТЬ РЕПОЗИТОРИЙ", color = ConnectBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Назад", color = ConnectBlue)
            }
        }
        }
    }
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Footer(onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("TGTunnel · 0.3.0 · Android only", color = TextSecondary, fontSize = 11.sp)
        TextButton(onClick = onSettings) { Text("НАСТРОЙКИ", color = ConnectBlue, fontSize = 10.sp) }
    }
}

private fun appendPersistentLog(context: Context, treeUriText: String, entry: LogEntry) {
    logWriter.execute {
        try {
            val resolver = context.contentResolver
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (preferences.getString(KEY_LOG_FOLDER_URI, "") != treeUriText) return@execute
            var fileUriText = activeLogFileUri
            if (fileUriText.isNullOrBlank() || activeLogFolderUri != treeUriText) {
                val treeUri = Uri.parse(treeUriText)
                val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
                val created = DocumentsContract.createDocument(
                    resolver,
                    parentUri,
                    "text/plain",
                    "TGTunnel-$logSessionId.log"
                ) ?: throw IllegalStateException("Не удалось создать новый файл журнала в выбранной папке")
                fileUriText = created.toString()
                activeLogFileUri = fileUriText
                activeLogFolderUri = treeUriText
            }
            val fileUri = Uri.parse(fileUriText)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "[$timestamp] ${entry.message}\n"
            resolver.openOutputStream(fileUri, "wa")?.use { output ->
                output.write(line.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: throw IllegalStateException("Провайдер хранилища не открыл файл для добавления")
        } catch (error: Exception) {
            android.util.Log.e("TGTunnel", "Persistent log write failed", error)
        }
    }
}

private fun newLog(message: String): LogEntry = LogEntry(
    id = logIds.incrementAndGet(),
    time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
    message = message
)
