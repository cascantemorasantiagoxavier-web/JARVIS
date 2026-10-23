package com.example.jarvis.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.jarvis.core.brain.PrivacySettings
import com.example.jarvis.core.json.JarvisJson
import com.example.jarvis.core.realtime.RealtimeSettings
import com.example.jarvis.core.realtime.RealtimeUsage
import com.example.jarvis.core.weather.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable enum class VoiceEngine { REALTIME, LOCAL }

/** unified: el backend negocia el SDP (ninguna credencial llega al teléfono). ephemeral: client secret temporal. */
@Serializable enum class ConnectMode { UNIFIED, EPHEMERAL }

@Serializable enum class CoreAccent { AMBER, CYAN }

/** Ajustes del usuario. Sin secretos: el token va cifrado en SecretStore. */
@Serializable
data class AppSettings(
    val backendUrl: String = "",
    val voiceEngine: VoiceEngine = VoiceEngine.REALTIME,
    val realtimeModel: String = "gpt-realtime-2.1-mini",
    val realtimeVoice: String = "cedar",
    val realtimeSpeed: Double = 1.0,
    val connectMode: ConnectMode = ConnectMode.UNIFIED,
    val idleTimeoutSeconds: Int = 30,
    val maxSessionMinutes: Int = 10,
    val wakeWordEnabled: Boolean = false,
    val wakeSensitivity: Float = 0.5f,
    val localContinuous: Boolean = true,
    val ttsVoiceName: String? = null,
    val ttsRate: Float = 0.95f,
    val ttsPitch: Float = 0.9f,
    val shareMemoriesWithModel: Boolean = true,
    val autoNotesContext: Boolean = false,
    val weatherPlace: Place? = null,
    val defaultLocation: String? = null,
    val accent: CoreAccent = CoreAccent.AMBER,
    val reduceMotion: Boolean = false,
    val defaultMode: String = "AUTO",
    val favoriteApps: List<String> = DEFAULT_FAVORITES,
    val vaultUri: String? = null,
    val usageDay: String = "",
    val usageToday: StoredUsage = StoredUsage(),
    val onboardingDone: Boolean = false,
) {
    fun realtime() = RealtimeSettings(
        model = realtimeModel,
        voice = realtimeVoice,
        speed = realtimeSpeed,
        idleTimeoutSeconds = idleTimeoutSeconds,
        maxSessionMinutes = maxSessionMinutes,
    )

    fun privacy() = PrivacySettings(shareMemoriesWithModel = shareMemoriesWithModel, autoNotesContext = autoNotesContext)

    companion object {
        /** Apps del diseño de referencia; solo se muestran las que estén instaladas. */
        val DEFAULT_FAVORITES = listOf(
            "com.spotify.music", "com.google.android.youtube", "com.google.android.apps.maps", "com.android.chrome",
            "com.google.android.gm", "com.android.vending", "com.whatsapp", "com.instagram.android",
            "com.zhiliaoapp.musically", "com.twitter.android", "special:CALCULATOR", "special:CALENDAR",
            "special:SETTINGS", "special:CAMERA", "com.google.android.apps.photos",
        )
    }
}

@Serializable
data class StoredUsage(val audioIn: Long = 0, val cachedAudioIn: Long = 0, val textIn: Long = 0, val audioOut: Long = 0, val textOut: Long = 0, val responses: Int = 0, val sessions: Int = 0, val seconds: Long = 0) {
    fun toUsage() = RealtimeUsage(audioIn, cachedAudioIn, textIn, audioOut, textOut, responses, seconds)
    operator fun plus(u: RealtimeUsage) = copy(
        audioIn = audioIn + u.audioIn, cachedAudioIn = cachedAudioIn + u.cachedAudioIn, textIn = textIn + u.textIn,
        audioOut = audioOut + u.audioOut, textOut = textOut + u.textOut, responses = responses + u.responses,
        sessions = sessions + 1, seconds = seconds + u.sessionSeconds,
    )
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_settings")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private val key = stringPreferencesKey("settings_json")

    val settings: StateFlow<AppSettings> = context.dataStore.data
        .map { prefs -> prefs[key]?.let { runCatching { JarvisJson.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val current: AppSettings get() = settings.value

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val now = prefs[key]?.let { runCatching { JarvisJson.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
            prefs[key] = JarvisJson.encodeToString(AppSettings.serializer(), transform(now))
        }
    }
}

/**
 * Token del backend cifrado con AES-256-GCM; la clave vive en el Android Keystore y no puede
 * extraerse del dispositivo. El token nunca se escribe en logs ni en ajustes en claro.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_secrets", Context.MODE_PRIVATE)
    @Volatile private var cachedToken: String? = null

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun saveToken(token: String) {
        val clean = token.trim()
        if (clean.isEmpty()) {
            prefs.edit().remove(PREF).apply()
            cachedToken = ""
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        val enc = Base64.getEncoder()
        prefs.edit().putString(PREF, enc.encodeToString(cipher.iv) + ":" + enc.encodeToString(ct)).apply()
        cachedToken = clean
    }

    fun token(): String {
        cachedToken?.let { return it }
        val stored = prefs.getString(PREF, null) ?: return "".also { cachedToken = it }
        val parts = stored.split(':')
        val value = runCatching {
            val dec = Base64.getDecoder()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, dec.decode(parts[0])))
            String(cipher.doFinal(dec.decode(parts[1])), Charsets.UTF_8)
        }.getOrDefault("")
        cachedToken = value
        return value
    }

    fun hasToken(): Boolean = token().isNotEmpty()

    companion object {
        private const val ALIAS = "jarvis_backend_token_key"
        private const val PREF = "backend_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
