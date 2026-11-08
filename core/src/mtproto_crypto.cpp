#include "tgtunnel/mtproto_crypto.h"

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>

#include <mbedtls/sha256.h>

#if defined(__ANDROID__)
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>
#else
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>
#endif

namespace tgtunnel {
namespace {

constexpr std::size_t kSkip = 8;
constexpr std::size_t kPrekeySize = 32;
constexpr std::size_t kIvSize = 16;
constexpr std::size_t kTailOffset = 56;

bool secure_random(std::uint8_t* output, std::size_t size) noexcept {
    if (output == nullptr) return false;
    const int fd = open("/dev/urandom", O_RDONLY
#ifdef O_CLOEXEC
                        | O_CLOEXEC
#endif
    );
    if (fd < 0) return false;

    std::size_t offset = 0;
    while (offset < size) {
        const ssize_t count = read(fd, output + offset, size - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            close(fd);
            return false;
        }
        offset += static_cast<std::size_t>(count);
    }
    close(fd);
    return true;
}

bool reserved_start(const std::array<std::uint8_t, 64>& bytes) noexcept {
    if (bytes[0] == 0xef) return true;
    constexpr std::array<std::array<std::uint8_t, 4>, 8> reserved{{
        {{0x48, 0x45, 0x41, 0x44}}, // HEAD
        {{0x50, 0x4f, 0x53, 0x54}}, // POST
        {{0x47, 0x45, 0x54, 0x20}}, // GET
        {{0xee, 0xee, 0xee, 0xee}},
        {{0xdd, 0xdd, 0xdd, 0xdd}},
        {{0x16, 0x03, 0x01, 0x02}},
        {{0x4f, 0x50, 0x54, 0x49}}, // OPTI
        {{0x00, 0x00, 0x00, 0x00}}
    }};
    for (const auto& prefix : reserved) {
        if (std::equal(prefix.begin(), prefix.end(), bytes.begin())) return true;
    }
    return std::all_of(bytes.begin() + 4, bytes.begin() + 8,
                       [](std::uint8_t value) { return value == 0; });
}

std::array<std::uint8_t, 4> transport_tag(MtprotoTransport transport) noexcept {
    switch (transport) {
        case MtprotoTransport::Abriged: return {0xef, 0xef, 0xef, 0xef};
        case MtprotoTransport::Intermediate: return {0xee, 0xee, 0xee, 0xee};
        case MtprotoTransport::PaddedIntermediate: return {0xdd, 0xdd, 0xdd, 0xdd};
    }
    return {};
}

}  // namespace

Aes256Ctr::Aes256Ctr(const std::array<std::uint8_t, 32>& key,
                     const std::array<std::uint8_t, 16>& iv) noexcept {
    mbedtls_aes_init(&context_);
    nonce_counter_ = iv;
    valid_ = mbedtls_aes_setkey_enc(&context_, key.data(), 256) == 0;
}

Aes256Ctr::~Aes256Ctr() {
    mbedtls_aes_free(&context_);
}

bool Aes256Ctr::update(const std::uint8_t* input, const std::size_t size,
                       std::uint8_t* output) noexcept {
    if (!valid_ || (size > 0 && (input == nullptr || output == nullptr))) return false;
    if (size == 0) return true;
    return mbedtls_aes_crypt_ctr(
        &context_, size, &nc_off_, nonce_counter_.data(),
        stream_block_.data(), input, output
    ) == 0;
}

bool sha256(const std::uint8_t* data, const std::size_t size,
            std::array<std::uint8_t, 32>& digest) noexcept {
    if (size > 0 && data == nullptr) return false;
    return mbedtls_sha256(data, size, digest.data(), 0) == 0;
}

bool decrypt_client_handshake(
    const std::array<std::uint8_t, 64>& encrypted,
    const MtprotoSecret& secret,
    MtprotoHandshake& parsed,
    MtprotoPrekeyIv& client_prekey_iv
) noexcept {
    std::copy_n(encrypted.begin() + static_cast<std::ptrdiff_t>(kSkip),
                client_prekey_iv.size(), client_prekey_iv.begin());

    std::array<std::uint8_t, kPrekeySize + MtprotoSecret{}.size()> key_material{};
    std::copy_n(client_prekey_iv.begin(), kPrekeySize, key_material.begin());
    std::copy(secret.begin(), secret.end(), key_material.begin() + kPrekeySize);

    std::array<std::uint8_t, 32> key{};
    if (!sha256(key_material.data(), key_material.size(), key)) return false;

    std::array<std::uint8_t, 16> iv{};
    std::copy_n(client_prekey_iv.begin() + static_cast<std::ptrdiff_t>(kPrekeySize),
                kIvSize, iv.begin());

    Aes256Ctr cipher(key, iv);
    if (!cipher.valid()) return false;

    std::array<std::uint8_t, 64> plaintext{};
    if (!cipher.update(encrypted.data(), encrypted.size(), plaintext.data())) return false;
    return parse_decrypted_handshake(plaintext.data(), plaintext.size(), parsed);
}

bool generate_relay_init(
    const MtprotoTransport transport,
    const std::uint16_t dc_id,
    const bool media,
    std::array<std::uint8_t, 64>& relay_init
) noexcept {
    if (dc_id == 0 || dc_id > 32767) return false;

    bool generated = false;
    for (int attempt = 0; attempt < 128; ++attempt) {
        if (!secure_random(relay_init.data(), relay_init.size())) return false;
        if (!reserved_start(relay_init)) {
            generated = true;
            break;
        }
    }
    if (!generated) return false;

    std::array<std::uint8_t, 32> key{};
    std::array<std::uint8_t, 16> iv{};
    std::copy_n(relay_init.begin() + static_cast<std::ptrdiff_t>(kSkip),
                key.size(), key.begin());
    std::copy_n(relay_init.begin() + static_cast<std::ptrdiff_t>(kSkip + kPrekeySize),
                iv.size(), iv.begin());

    Aes256Ctr cipher(key, iv);
    if (!cipher.valid()) return false;

    std::array<std::uint8_t, 64> encrypted{};
    if (!cipher.update(relay_init.data(), relay_init.size(), encrypted.data())) return false;

    std::array<std::uint8_t, 8> tail{};
    const auto tag = transport_tag(transport);
    std::copy(tag.begin(), tag.end(), tail.begin());
    const std::int16_t signed_dc = static_cast<std::int16_t>(media ? -dc_id : dc_id);
    const auto raw_dc = static_cast<std::uint16_t>(signed_dc);
    tail[4] = static_cast<std::uint8_t>(raw_dc & 0xffU);
    tail[5] = static_cast<std::uint8_t>((raw_dc >> 8U) & 0xffU);
    if (!secure_random(tail.data() + 6, 2)) return false;

    for (std::size_t i = 0; i < tail.size(); ++i) {
        const std::size_t pos = kTailOffset + i;
        const std::uint8_t keystream = static_cast<std::uint8_t>(encrypted[pos] ^ relay_init[pos]);
        relay_init[pos] = static_cast<std::uint8_t>(tail[i] ^ keystream);
    }
    return true;
}

}  // namespace tgtunnel
