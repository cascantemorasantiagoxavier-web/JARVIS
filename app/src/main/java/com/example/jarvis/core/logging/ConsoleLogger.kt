package com.example.jarvis.core.logging

import android.util.Log

class ConsoleLogger : AppLogger {
    override fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, message)
    }
}
