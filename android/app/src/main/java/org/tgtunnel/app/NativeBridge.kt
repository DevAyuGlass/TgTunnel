package org.tgtunnel.app

/**
 * Public app-facing proxy API. The Android transport uses the platform TLS
 * trust store and Java crypto provider; the C++ core remains independently
 * built and covered by native compatibility tests.
 */
internal object NativeBridge {
    fun info(): String = LocalProxyEngine.info()

    fun isRunning(): Boolean = LocalProxyEngine.isRunning()

    fun start(port: Int, secret: String, workerDomain: String, cfProxyEnabled: Boolean, onLog: (String) -> Unit): String =
        LocalProxyEngine.start(port, secret, workerDomain, cfProxyEnabled, onLog)

    fun stop(): String = LocalProxyEngine.stop()
}
