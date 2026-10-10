package com.example.jarvis.features.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Color Palette for Clean ChatGPT-Inspired Light Theme
private val ChatBackground = Color(0xFFF7F7F8)
private val ChatHeaderBg = Color(0xFFFFFFFF)
private val ChatHeaderText = Color(0xFF111827)
private val ChatSubtextColor = Color(0xFF6B7280)
private val ChatBorderColor = Color(0xFFE5E7EB)

private val UserBubbleBg = Color(0xFFF3F4F6)
private val UserBubbleBorder = Color(0xFFE5E7EB)
private val UserTextColor = Color(0xFF1F2937)

private val JarvisBubbleBg = Color(0xFFFFFFFF)
private val JarvisBubbleBorder = Color(0xFFE5E7EB)
private val JarvisTextColor = Color(0xFF1F2937)

private val JarvisAccentCyan = Color(0xFF0891B2)
private val JarvisAccentAmber = Color(0xFFD97706)
private val JarvisSuccessGreen = Color(0xFF10B981)

@Composable
fun JarvisChatScreen(viewModel: ChatViewModel = remember { ChatViewModel() }) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Clean Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ChatHeaderBg, RoundedCornerShape(10.dp))
                    .border(1.dp, ChatBorderColor, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = JarvisAccentCyan.copy(alpha = 0.12f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "J",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = JarvisAccentCyan
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "JARVIS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ChatHeaderText
                        )
                        Text(
                            text = "Asistente Personal Inteligente",
                            style = MaterialTheme.typography.labelSmall,
                            color = ChatSubtextColor
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = JarvisSuccessGreen.copy(alpha = 0.1f),
                    border = BorderStroke(0.5.dp, JarvisSuccessGreen.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "● EN LÍNEA",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisSuccessGreen,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Conversation Body
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = JarvisAccentCyan.copy(alpha = 0.1f),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "⚡",
                                        fontSize = 22.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Hola, soy JARVIS. ¿En qué puedo ayudarte hoy?",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = ChatHeaderText
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Puedes preguntarme sobre tareas, fechas, mapas, o pedirme investigar empresas e importaciones.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ChatSubtextColor
                            )
                        }
                    }
                }

                items(state.messages) { msg ->
                    val isUser = msg.sender == "Usuario"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Card(
                            modifier = Modifier.widthIn(max = 330.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isUser) UserBubbleBg else JarvisBubbleBg
                            ),
                            border = BorderStroke(1.dp, if (isUser) UserBubbleBorder else JarvisBubbleBorder),
                            shape = RoundedCornerShape(
                                topStart = 12.dp,
                                topEnd = 12.dp,
                                bottomStart = if (isUser) 12.dp else 2.dp,
                                bottomEnd = if (isUser) 2.dp else 12.dp
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = if (isUser) "Tú" else "JARVIS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isUser) JarvisAccentCyan else JarvisAccentAmber
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = msg.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isUser) UserTextColor else JarvisTextColor,
                                    lineHeight = 20.sp
                                )

                                val urlRegex = """(https?://[^\s)]+)""".toRegex()
                                val matches = urlRegex.findAll(msg.text)
                                for (match in matches) {
                                    val url = match.value
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = ChatBackground),
                                        border = BorderStroke(0.5.dp, JarvisAccentCyan.copy(alpha = 0.5f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {}
                                            }
                                    ) {
                                        Text(
                                            text = "🔗 Abrir Fuente: $url",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = JarvisAccentCyan,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }

                                if (!isUser && (msg.text.contains("🏢") || msg.text.contains("Prospectos comerciales"))) {
                                    val companyLines = msg.text.split("\n\n").filter { it.contains("🏢") }
                                    for (comp in companyLines) {
                                        val nameLine = comp.lines().firstOrNull { it.contains("🏢") }?.replace("🏢", "")?.trim() ?: "Negocio"
                                        val urlLine = comp.lines().firstOrNull { it.contains("http") }?.let { urlRegex.find(it)?.value } ?: ""
                                        Spacer(modifier = Modifier.height(6.dp))
                                        BusinessCardView(
                                            name = nameLine,
                                            address = "Información recuperada en tiempo real",
                                            phone = "",
                                            url = urlLine,
                                            onCallClick = { phone ->
                                                try {
                                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {}
                                            },
                                            onMapsClick = { query ->
                                                try {
                                                    val mapsUrl = "https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}"
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl)).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {}
                                            },
                                            onUrlClick = { targetUrl ->
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {}
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.isLoading) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = JarvisAccentCyan,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "JARVIS está pensando...",
                                style = MaterialTheme.typography.bodySmall,
                                color = ChatSubtextColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Clean Rounded Input Container
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ChatHeaderBg,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ChatBorderColor),
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = { viewModel.onInputChanged(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Pregunta lo que quieras a JARVIS...", color = ChatSubtextColor, fontSize = 14.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ChatHeaderText,
                            unfocusedTextColor = ChatHeaderText,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        maxLines = 4
                    )

                    Button(
                        onClick = { viewModel.sendMessage() },
                        enabled = !state.isLoading && state.input.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = JarvisAccentCyan,
                            disabledContainerColor = ChatBorderColor
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Enviar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun BusinessCardView(
    name: String,
    address: String,
    phone: String,
    url: String,
    onCallClick: (String) -> Unit,
    onMapsClick: (String) -> Unit,
    onUrlClick: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        colors = CardDefaults.cardColors(containerColor = ChatBackground),
        border = BorderStroke(1.dp, ChatBorderColor),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(text = "🏢 $name", style = MaterialTheme.typography.titleSmall, color = JarvisAccentAmber, fontWeight = FontWeight.Bold)
            if (address.isNotBlank()) {
                Text(text = "📍 $address", style = MaterialTheme.typography.bodySmall, color = UserTextColor)
            }
            if (phone.isNotBlank()) {
                Text(text = "📞 $phone", style = MaterialTheme.typography.bodySmall, color = JarvisAccentCyan)
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (phone.isNotBlank()) {
                    OutlinedButton(
                        onClick = { onCallClick(phone) },
                        border = BorderStroke(0.5.dp, JarvisAccentCyan),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("📞 Llamar", style = MaterialTheme.typography.labelSmall, color = JarvisAccentCyan)
                    }
                }
                OutlinedButton(
                    onClick = { onMapsClick(name) },
                    border = BorderStroke(0.5.dp, JarvisAccentAmber),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("🗺️ Maps", style = MaterialTheme.typography.labelSmall, color = JarvisAccentAmber)
                }
                if (url.isNotBlank()) {
                    OutlinedButton(
                        onClick = { onUrlClick(url) },
                        border = BorderStroke(0.5.dp, JarvisAccentCyan),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("🔗 Fuente", style = MaterialTheme.typography.labelSmall, color = JarvisAccentCyan)
                    }
                }
            }
        }
    }
}
