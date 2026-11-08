
package org.tgtunnel.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Background = Color(0xFF0B1118)
private val SurfaceDark = Color(0xFF151F2A)
private val SurfaceLight = Color(0xFF1B2835)
private val TextPrimary = Color(0xFFEAF1F7)
private val TextSecondary = Color(0xFF91A3B5)

private val ConnectBlue = Color(0xFF75D5FF)
private val ConnectedGreen = Color(0xFFA5E6B2)
private val ErrorRed = Color(0xFFFF777F)
private val AccentPurple = Color(0xFFB7A2FF)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class LogEntry(
    val time: String,
    val message: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TgTunnelApp()
        }
    }
}

@Composable
fun TgTunnelApp() {
    var connectionState by remember {
        mutableStateOf(ConnectionState.DISCONNECTED)
    }

    var logs by remember {
        mutableStateOf(
            listOf(
                LogEntry(currentTime(), "TgTunnel запущен"),
                LogEntry(currentTime(), "Ожидание подключения")
            )
        )
    }

    fun addLog(message: String) {
        logs = listOf(
            LogEntry(currentTime(), message)
        ) + logs
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Background,
            surface = SurfaceDark,
            primary = ConnectBlue,
            onBackground = TextPrimary,
            onSurface = TextPrimary
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 28.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Header()

                ConnectionCard(
                    state = connectionState,
                    onConnect = {
                        // Пока C++-движок не подключён.
                        // Не имитируем успешное соединение.
                        addLog("Движок ещё не подключён")
                    }
                )

                SectionTitle("ПОДКЛЮЧЕНИЕ")

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    InfoCard(
                        title = "ЛОКАЛЬНЫЙ АДРЕС",
                        value = "127.0.0.1",
                        subtitle = "Порт 1443",
                        modifier = Modifier.weight(1f)
                    )

                    InfoCard(
                        title = "ПРОТОКОЛ",
                        value = "MTProto",
                        subtitle = "Telegram",
                        modifier = Modifier.weight(1f)
                    )
                }

                SectionTitle("ДВИЖОК")

                EngineCard()

                SectionTitle("ЖУРНАЛ СОБЫТИЙ")

                LogsCard(logs)

                Footer()
            }
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(
                        SurfaceLight,
                        RoundedCornerShape(15.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "T",
                    color = ConnectBlue,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column {
                Text(
                    "TgTunnel",
                    color = TextPrimary,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Telegram connectivity",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .background(
                    SurfaceLight,
                    CircleShape
                )
                .padding(horizontal = 13.dp, vertical = 8.dp)
        ) {
            Text(
                "0.1.0",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    state: ConnectionState,
    onConnect: () -> Unit
) {
    val buttonColor by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.CONNECTED -> ConnectedGreen
            else -> ConnectBlue
        },
        label = "buttonColor"
    )

    val statusColor = when (state) {
        ConnectionState.CONNECTED -> ConnectedGreen
        ConnectionState.CONNECTING -> AccentPurple
        ConnectionState.ERROR -> ErrorRed
        ConnectionState.DISCONNECTED -> TextSecondary
    }

    val statusText = when (state) {
        ConnectionState.CONNECTED -> "Подключено"
        ConnectionState.CONNECTING -> "Подключение..."
        ConnectionState.ERROR -> "Ошибка"
        ConnectionState.DISCONNECTED -> "Отключено"
    }

    val description = when (state) {
        ConnectionState.CONNECTED -> "Прокси работает"
        ConnectionState.CONNECTING -> "Устанавливаем соединение"
        ConnectionState.ERROR -> "Не удалось запустить прокси"
        ConnectionState.DISCONNECTED -> "Telegram proxy не запущен"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceDark
        )
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "СОСТОЯНИЕ ПОДКЛЮЧЕНИЯ",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(statusColor, CircleShape)
                )

                Text(
                    statusText,
                    color = TextPrimary,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                description,
                color = TextSecondary,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(5.dp))

            Button(
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = Color(0xFF101820)
                )
            ) {
                Text(
                    if (state == ConnectionState.CONNECTED)
                        "ПОДКЛЮЧЕНО"
                    else
                        "ПОДКЛЮЧИТЬСЯ",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
            }

            Text(
                "Управление локальным MTProto-прокси",
                modifier = Modifier.fillMaxWidth(),
                color = TextSecondary,
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        color = TextSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp
    )
}

@Composable
private fun InfoCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceDark
        )
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                title,
                color = TextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                value,
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                subtitle,
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun EngineCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceDark
        )
    ) {
        Column(
            modifier = Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            EngineRow("Платформа", "Android / Linux")
            EngineRow("Язык движка", "C++")
            EngineRow("Интерфейс", "Kotlin + Compose")
            EngineRow("Состояние", "Ожидает интеграции", AccentPurple)
        }
    }
}

@Composable
private fun EngineRow(
    title: String,
    value: String,
    valueColor: Color = TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = TextSecondary,
            fontSize = 12.sp
        )

        Text(
            value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun LogsCard(logs: List<LogEntry>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceDark
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            logs.take(5).forEach { entry ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        entry.time,
                        color = ConnectBlue,
                        fontSize = 10.sp
                    )

                    Text(
                        entry.message,
                        color = TextPrimary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun Footer() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "TgTunnel",
            color = TextSecondary,
            fontSize = 11.sp
        )

        Text(
            "MTProto • Open Source",
            color = TextSecondary,
            fontSize = 11.sp
        )
    }
}

private fun currentTime(): String {
    return SimpleDateFormat(
        "HH:mm:ss",
        Locale.getDefault()
    ).format(Date())
}

