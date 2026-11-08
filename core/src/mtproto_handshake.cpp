#include "tgtunnel/mtproto_handshake.h"

namespace tgtunnel {
namespace {

constexpr std::size_t kProtocolTagOffset = 56;
constexpr std::size_t kDcIndexOffset = 60;
constexpr std::size_t kHandshakeSize = 64;

bool equals_tag(const std::uint8_t* data, const std::uint8_t a,
                const std::uint8_t b, const std::uint8_t c,
                const std::uint8_t d) noexcept {
    return data[0] == a && data[1] == b &&
           data[2] == c && data[3] == d;
}

}  // namespace

bool parse_decrypted_handshake(
    const std::uint8_t* data,
    const std::size_t size,
    MtprotoHandshake& result
) noexcept {
    if (data == nullptr || size != kHandshakeSize) {
        return false;
    }

    MtprotoTransport transport;
    const auto* tag = data + kProtocolTagOffset;

    if (equals_tag(tag, 0xef, 0xef, 0xef, 0xef)) {
        transport = MtprotoTransport::Abriged;
    } else if (equals_tag(tag, 0xee, 0xee, 0xee, 0xee)) {
        transport = MtprotoTransport::Intermediate;
    } else if (equals_tag(tag, 0xdd, 0xdd, 0xdd, 0xdd)) {
        transport = MtprotoTransport::PaddedIntermediate;
    } else {
        return false;
    }

    const std::uint16_t raw =
        static_cast<std::uint16_t>(data[kDcIndexOffset]) |
        (static_cast<std::uint16_t>(data[kDcIndexOffset + 1]) << 8U);
    const std::int32_t signed_dc = (raw & 0x8000U) != 0U
        ? static_cast<std::int32_t>(raw) - 0x10000
        : static_cast<std::int32_t>(raw);

    // DC 0 is invalid; abs(INT16_MIN) cannot be represented as int16.
    if (signed_dc == 0 || signed_dc == -32768) {
        return false;
    }

    result.transport = transport;
    result.media = signed_dc < 0;
    result.dc_id = static_cast<std::uint16_t>(
        signed_dc < 0 ? -signed_dc : signed_dc
    );
    return true;
}

}  // namespace tgtunnel
