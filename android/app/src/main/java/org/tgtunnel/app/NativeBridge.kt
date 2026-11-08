package org.tgtunnel.app

/**
 * Thin JNI boundary. The UI never performs proxy work on the main thread.
 * The current native core is an integration scaffold; it deliberately refuses
 * to report a successful connection until the MTProto transport is implemented.
 */
internal object NativeBridge {
    private val loaded: Boolean = runCatching {
        System.loadLibrary("tgtunnel")
    }.isSuccess

    fun info(): String =
        if (!loaded) "Native library failed to load"
        else runCatching { nativeEngineInfo() }
            .getOrElse { "Native core error: ${it.message ?: "unknown"}" }

    fun start(port: Int): String =
        if (!loaded) "ERR:Native library is unavailable"
        else runCatching { nativeStartProxy(port) }
            .getOrElse { "ERR:Native start failed: ${it.message ?: "unknown"}" }

    fun stop(): String =
        if (!loaded) "Native library is unavailable"
        else runCatching { nativeStopProxy() }
            .getOrElse { "Native stop failed: ${it.message ?: "unknown"}" }

    private external fun nativeEngineInfo(): String
    private external fun nativeStartProxy(port: Int): String
    private external fun nativeStopProxy(): String
}
