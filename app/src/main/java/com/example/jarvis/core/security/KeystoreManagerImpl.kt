package com.example.jarvis.core.security

import android.util.Base64

class KeystoreManagerImpl : KeystoreManager {
    override fun encrypt(plainText: String): String {
        return Base64.encodeToString(plainText.toByteArray(), Base64.DEFAULT)
    }

    override fun decrypt(cipherText: String): String {
        return try {
            String(Base64.decode(cipherText, Base64.DEFAULT))
        } catch (_: Exception) {
            cipherText
        }
    }
}
