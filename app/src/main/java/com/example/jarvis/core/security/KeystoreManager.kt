package com.example.jarvis.core.security

interface KeystoreManager {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}
