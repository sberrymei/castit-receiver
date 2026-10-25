package io.sberry.castitreceiver.airplay.util

import android.util.Log

/**
 * Logger — A thin wrapper around android.util.Log with redaction support.
 */
object Logger {
    private const val TAG = "AirPlay"

    private fun redact(message: String): String =
        LogRedactor.redact(message)

    fun v(message: String) {
        Log.v(TAG, redact(message))
    }

    fun d(message: String) {
        Log.d(TAG, redact(message))
    }

    fun i(message: String) {
        Log.i(TAG, redact(message))
    }

    fun w(message: String) {
        Log.w(TAG, redact(message))
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, redact(message), LogRedactor.redactThrowable(throwable))
        } else {
            Log.e(TAG, redact(message))
        }
    }

    fun redactForTest(message: String): String = redact(message)
}
