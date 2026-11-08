package org.tgtunnel.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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

private val logIds = AtomicLong(0)

private enum class Screen { HOME, SETTINGS }
private enum class ConnectionState { DISCONNECTED, CONNECTED, ERROR }

private data class AppSettings(
    val port: String = "1443",
    val secret: String = "",
    val autostart: Boolean = false,
    val verboseLogs: Boolean = false,
    val workerDomain: String = "",
    val cfProxyFallback: Boolean = true
)

private data class LogEntry(
    val id: Long,
    val time: String,
    val message: String
)

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
                cfProxyFallback = preferences.getBoolean(KEY_CF_PROXY, true)
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
        settings = next
        preferences.edit()
            .putInt(KEY_PORT, next.port.toIntOrNull() ?: 1443)
            .putString(KEY_SECRET, next.secret)
            .putBoolean(KEY_AUTOSTART, next.autostart)
            .putBoolean(KEY_VERBOSE, next.verboseLogs)
            .putString(KEY_CF_WORKER, next.workerDomain)
            .putBoolean(KEY_CF_PROXY, next.cfProxyFallback)
            .apply()
    }

    fun addLog(message: String) {
        logs = (listOf(newLog(message)) + logs).take(50)
    }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    LaunchedEffect(Unit) {
        if (NativeBridge.isRunning()) {
            connectionState = ConnectionState.CONNECTED
        } else if (settings.autostart) {
            val port = settings.port.toIntOrNull()
            if (port != null && port in 1..65535) {
                val result = withContext(Dispatchers.IO) {
                    NativeBridge.start(port, settings.secret, settings.workerDomain, settings.cfProxyFallback) { message ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post { addLog(message) }
                    }
                }
                connectionState = if (result.startsWith("OK:")) ConnectionState.CONNECTED else ConnectionState.ERROR
                addLog("Автозапуск: " + result.removePrefix("OK:").removePrefix("ERR:"))
            }
        }
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
                    onConnect = {
                        val port = settings.port.toIntOrNull()
                        if (port == null || port !in 1..65535) {
                            connectionState = ConnectionState.ERROR
                            addLog("Некорректный порт")
                        } else {
                            if (!connectionBusy) {
                                connectionBusy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        NativeBridge.start(port, settings.secret, settings.workerDomain, settings.cfProxyFallback) { message ->
                                            android.os.Handler(android.os.Looper.getMainLooper()).post { addLog(message) }
                                        }
                                    }
                                    connectionState = if (result.startsWith("OK:")) {
                                        ConnectionState.CONNECTED
                                    } else {
                                        ConnectionState.ERROR
                                    }
                                    addLog(result.removePrefix("OK:").removePrefix("ERR:"))
                                    connectionBusy = false
                                }
                            }
                        }
                    },
                    onStop = {
                        if (!connectionBusy) {
                            connectionBusy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { NativeBridge.stop() }
                                connectionState = ConnectionState.DISCONNECTED
                                addLog(result)
                                connectionBusy = false
                            }
                        }
                    }
                )
                Screen.SETTINGS -> SettingsScreen(
                    settings = settings,
                    engineInfo = NativeBridge.info(),
                    onBack = { screen = Screen.HOME },
                    onSettingsChanged = ::updateSettings
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
        item(key = "logs") { LogsCard(logs) }
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
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
                Text(statusText, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
            EngineRow("Язык движка", "C++")
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
private fun LogsCard(logs: List<LogEntry>) {
    val clipboard = LocalClipboardManager.current
    val logText = logs.asReversed().joinToString("\\n") { "[${it.time}] ${it.message}" }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            logs.take(6).forEach { entry ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Text(entry.time, color = ConnectBlue, fontSize = 10.sp)
                    Text(entry.message, color = TextPrimary, fontSize = 12.sp)
                }
            }
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(logText)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("КОПИРОВАТЬ ЛОГ", color = ConnectBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    engineInfo: String,
    onBack: () -> Unit,
    onSettingsChanged: (AppSettings) -> Unit
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

private fun newLog(message: String): LogEntry = LogEntry(
    id = logIds.incrementAndGet(),
    time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
    message = message
)
