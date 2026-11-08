#pragma once

#include <cstdint>
#include <string>

namespace tgtunnel {

[[nodiscard]] std::string engine_info();
[[nodiscard]] std::string start_proxy(std::uint16_t port);
[[nodiscard]] std::string stop_proxy();

}  // namespace tgtunnel
