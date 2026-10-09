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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.jarvis.ui.theme.*

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
            .background(HudBackground)
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HudSurfaceDark, RoundedCornerShape(6.dp))
                    .border(1.dp, HudCyanNeon.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "💬 CHAT NEURONAL DE JARVIS",
                    style = MaterialTheme.typography.titleMedium,
                    color = HudCyanNeon
                )
                Text(
                    text = "ESTADO: SEGURO",
                    style = MaterialTheme.typography.labelSmall,
                    color = HudSuccessGreen
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages) { msg ->
                    val isUser = msg.sender == "Usuario"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Card(
                            modifier = Modifier.widthIn(max = 320.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isUser) HudCyanNeon.copy(alpha = 0.15f) else HudSurface
                            ),
                            border = BorderStroke(1.dp, if (isUser) HudCyanNeon.copy(alpha = 0.4f) else HudPrimaryAmber.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = msg.sender,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isUser) HudCyanNeon else HudPrimaryAmber
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = msg.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White
                                )

                                val urlRegex = """(https?://[^\s)]+)""".toRegex()
                                val matches = urlRegex.findAll(msg.text)
                                for (match in matches) {
                                    val url = match.value
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = HudSurfaceDark),
                                        border = BorderStroke(0.5.dp, HudCyanNeon.copy(alpha = 0.5f)),
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
                                            color = HudCyanNeon,
                                            modifier = Modifier.padding(6.dp)
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
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = HudCyanNeon)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "JARVIS está procesando respuesta neuronal...",
                                style = MaterialTheme.typography.bodySmall,
                                color = HudTextMuted
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HudSurfaceDark, RoundedCornerShape(8.dp))
                    .border(1.dp, HudCyanNeon, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = { viewModel.onInputChanged(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Consultar a JARVIS...", color = HudTextMuted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = HudCyanNeon,
                        unfocusedBorderColor = HudTextMuted.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(6.dp)
                )
                Button(
                    onClick = { viewModel.sendMessage() },
                    enabled = !state.isLoading && state.input.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = HudCyanNeon),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Enviar", color = Color.Black)
                }
            }
        }
    }
}
