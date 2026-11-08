#pragma once

#include <cstddef>
#include <cstdint>

namespace tgtunnel {

enum class MtprotoTransport : std::uint8_t {
    Abriged,
    Intermediate,
    PaddedIntermediate
};

struct MtprotoHandshake {
    MtprotoTransport transport;
    std::uint16_t dc_id;
    bool media;
};

// Parses the decrypted 64-byte MTProto obfuscation header.
// Crypto is deliberately kept outside this function so the parser is
// deterministic and can be tested without a platform crypto provider.
[[nodiscard]] bool parse_decrypted_handshake(
    const std::uint8_t* data,
    std::size_t size,
    MtprotoHandshake& result
) noexcept;

}  // namespace tgtunnel
