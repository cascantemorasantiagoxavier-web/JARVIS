package com.example.jarvis.ai.shorts.youtube

import android.content.Context
import android.util.Log

object YouTubeAuthManager {
    private const val PREFS_NAME = "jarvis_youtube_auth_prefs"
    private const val KEY_ACCESS_TOKEN = "yt_access_token"
    private const val KEY_REFRESH_TOKEN = "yt_refresh_token"
    private const val KEY_CHANNEL_TITLE = "yt_channel_title"

    fun isConnected(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrBlank()
    }

    fun getChannelName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CHANNEL_TITLE, "Canal de YouTube") ?: "Canal de YouTube"
    }

    fun saveTokens(context: Context, accessToken: String, refreshToken: String?, channelTitle: String = "JARVIS Channel") {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            refreshToken?.let { putString(KEY_REFRESH_TOKEN, it) }
            putString(KEY_CHANNEL_TITLE, channelTitle)
            apply()
        }
        Log.i("JARVIS", "[YOUTUBE_AUTH] Saved OAuth tokens for $channelTitle")
    }

    fun getAccessToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACCESS_TOKEN, null)
    }

    fun disconnect(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        Log.i("JARVIS", "[YOUTUBE_AUTH] Disconnected YouTube account")
    }
}
