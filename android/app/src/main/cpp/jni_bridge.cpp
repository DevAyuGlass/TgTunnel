#include <jni.h>

#include <string>

#include "tgtunnel/engine.h"

namespace {

jstring to_java_string(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_tgtunnel_app_NativeBridge_nativeEngineInfo(
    JNIEnv* env,
    jobject /* thiz */
) {
    return to_java_string(env, tgtunnel::engine_info());
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_tgtunnel_app_NativeBridge_nativeStartProxy(
    JNIEnv* env,
    jobject /* thiz */,
    const jint port
) {
    if (port < 1 || port > 65535) {
        return to_java_string(env, "ERR:Port must be between 1 and 65535");
    }

    return to_java_string(
        env,
        tgtunnel::start_proxy(static_cast<std::uint16_t>(port))
    );
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_tgtunnel_app_NativeBridge_nativeStopProxy(
    JNIEnv* env,
    jobject /* thiz */
) {
    return to_java_string(env, tgtunnel::stop_proxy());
}
