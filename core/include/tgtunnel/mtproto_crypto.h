#pragma once

#include "tgtunnel/mtproto_handshake.h"

#include <array>
#include <cstddef>
#include <cstdint>

#include <mbedtls/aes.h>

namespace tgtunnel {

using MtprotoSecret = std::array<std::uint8_t, 16>;
using MtprotoPrekeyIv = std::array<std::uint8_t, 48>;

class Aes256Ctr final {
public:
    Aes256Ctr(const std::array<std::uint8_t, 32>& key,
              const std::array<std::uint8_t, 16>& iv) noexcept;
    ~Aes256Ctr();

    Aes256Ctr(const Aes256Ctr&) = delete;
    Aes256Ctr& operator=(const Aes256Ctr&) = delete;

    [[nodiscard]] bool valid() const noexcept { return valid_; }
    [[nodiscard]] bool update(const std::uint8_t* input, std::size_t size,
                              std::uint8_t* output) noexcept;

private:
    mbedtls_aes_context context_{};
    std::array<std::uint8_t, 16> nonce_counter_{};
    std::array<std::uint8_t, 16> stream_block_{};
    std::size_t nc_off_{0};
    bool valid_{false};
};

[[nodiscard]] bool sha256(const std::uint8_t* data, std::size_t size,
                          std::array<std::uint8_t, 32>& digest) noexcept;

[[nodiscard]] bool decrypt_client_handshake(
    const std::array<std::uint8_t, 64>& encrypted,
    const MtprotoSecret& secret,
    MtprotoHandshake& parsed,
    MtprotoPrekeyIv& client_prekey_iv
) noexcept;

[[nodiscard]] bool generate_relay_init(
    MtprotoTransport transport,
    std::uint16_t dc_id,
    bool media,
    std::array<std::uint8_t, 64>& relay_init
) noexcept;

}  // namespace tgtunnel
