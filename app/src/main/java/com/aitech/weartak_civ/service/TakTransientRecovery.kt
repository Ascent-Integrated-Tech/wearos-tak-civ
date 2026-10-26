package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.data.takserver.TakEnrollmentException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

internal object TakTransientRecovery {
    fun isRetryable(error: Throwable): Boolean {
        val causes = generateSequence(error) { it.cause }.take(16).toList()
        if (causes.last().cause != null) return false
        if (causes.any {
                it is CancellationException || it is SSLException ||
                    it is GeneralSecurityException || it is SecurityException ||
                    it is TakEnrollmentException || it is IllegalArgumentException
            }) return false
        return causes.last() is IOException || causes.any {
            it is SocketException || it is SocketTimeoutException || it is UnknownHostException
        }
    }

    fun delayMs(streak: Int): Long = TakReconnectBackoff.delayMs(streak.coerceIn(1, 16) + 1)
}
