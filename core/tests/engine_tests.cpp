#include "tgtunnel/engine.h"

#include <cassert>
#include <string>

int main() {
    assert(!tgtunnel::engine_info().empty());

    const std::string invalid = tgtunnel::start_proxy(0);
    assert(invalid.rfind("ERR:", 0) == 0);

    const std::string not_implemented = tgtunnel::start_proxy(1443);
    assert(not_implemented.find("not implemented") != std::string::npos);

    assert(tgtunnel::stop_proxy() == "Native core is idle");
    return 0;
}
