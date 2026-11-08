#include "tgtunnel/engine.h"

#include <mutex>
#include <string>

namespace tgtunnel {
namespace {

std::mutex engine_mutex;
bool engine_running = false;

}  // namespace

std::string engine_info() {
    return "TgTunnel native core loaded; MTProto transport is not implemented";
}

std::string start_proxy(const std::uint16_t port) {
    std::lock_guard<std::mutex> lock(engine_mutex);

    if (port == 0) {
        return "ERR:Port must be between 1 and 65535";
    }

    if (engine_running) {
        return "ERR:Engine lifecycle is already active";
    }

    // Do not claim a proxy is running until the real MTProto transport,
    // listener and protocol handshake have been ported and tested.
    return "ERR:MTProto transport is not implemented in this build";
}

std::string stop_proxy() {
    std::lock_guard<std::mutex> lock(engine_mutex);

    if (!engine_running) {
        return "Native core is idle";
    }

    engine_running = false;
    return "Native core stopped";
}

}  // namespace tgtunnel
