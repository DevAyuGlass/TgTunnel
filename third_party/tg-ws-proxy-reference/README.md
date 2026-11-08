# Python reference implementation

This directory preserves the original Python proxy implementation as a
porting/reference source. It is **not** packaged into the Android application
and is not executed at runtime.

The Android app uses Kotlin + Jetpack Compose and loads the native C++ core
through JNI. Protocol behavior must be ported into `core/` incrementally and
verified with compatibility tests before the UI reports a connected state.

See the adjacent `tg-ws-proxy-LICENSE` file for the upstream license.
