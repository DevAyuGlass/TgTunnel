#include "tgtunnel/engine.h"
#include "tgtunnel/mtproto_handshake.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <string>

namespace {

void put_tag(std::array<std::uint8_t, 64>& data,
             const std::array<std::uint8_t, 4>& tag) {
    for (std::size_t i = 0; i < tag.size(); ++i) {
        data[56 + i] = tag[i];
    }
}

void test_handshake_parser() {
    using tgtunnel::MtprotoTransport;
    using tgtunnel::parse_decrypted_handshake;

    std::array<std::uint8_t, 64> data{};
    tgtunnel::MtprotoHandshake parsed{};

    put_tag(data, {0xef, 0xef, 0xef, 0xef});
    data[60] = 2;
    assert(parse_decrypted_handshake(data.data(), data.size(), parsed));
    assert(parsed.transport == MtprotoTransport::Abriged);
    assert(parsed.dc_id == 2);
    assert(!parsed.media);

    put_tag(data, {0xee, 0xee, 0xee, 0xee});
    data[60] = 4;
    data[61] = 0xff; // -252 in signed little-endian
    assert(parse_decrypted_handshake(data.data(), data.size(), parsed));
    assert(parsed.transport == MtprotoTransport::Intermediate);
    assert(parsed.dc_id == 252);
    assert(parsed.media);

    put_tag(data, {0xdd, 0xdd, 0xdd, 0xdd});
    data[60] = 3;
    data[61] = 0;
    assert(parse_decrypted_handshake(data.data(), data.size(), parsed));
    assert(parsed.transport == MtprotoTransport::PaddedIntermediate);
    assert(parsed.dc_id == 3);
    assert(!parsed.media);

    put_tag(data, {0, 0, 0, 0});
    assert(!parse_decrypted_handshake(data.data(), data.size(), parsed));
    assert(!parse_decrypted_handshake(data.data(), 63, parsed));
    assert(!parse_decrypted_handshake(nullptr, 64, parsed));

    put_tag(data, {0xef, 0xef, 0xef, 0xef});
    data[60] = 0;
    data[61] = 0;
    assert(!parse_decrypted_handshake(data.data(), data.size(), parsed));

    data[60] = 0;
    data[61] = 0x80; // INT16_MIN
    assert(!parse_decrypted_handshake(data.data(), data.size(), parsed));
}

}  // namespace

int main() {
    assert(!tgtunnel::engine_info().empty());

    const std::string invalid = tgtunnel::start_proxy(0);
    assert(invalid.rfind("ERR:", 0) == 0);

    const std::string not_implemented = tgtunnel::start_proxy(1443);
    assert(not_implemented.find("not implemented") != std::string::npos);

    assert(tgtunnel::stop_proxy() == "Native core is idle");

    test_handshake_parser();
    return 0;
}
