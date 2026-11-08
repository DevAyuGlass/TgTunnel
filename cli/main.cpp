#include "tgtunnel/engine.h"

#include <cstdlib>
#include <iostream>
#include <string>

int main(int argc, char** argv) {
    if (argc == 1 || std::string(argv[1]) == "--info") {
        std::cout << tgtunnel::engine_info() << '\n';
        return EXIT_SUCCESS;
    }

    if (std::string(argv[1]) == "start" && argc == 3) {
        try {
            const int port = std::stoi(argv[2]);
            if (port < 1 || port > 65535) {
                std::cerr << "Port must be between 1 and 65535\n";
                return EXIT_FAILURE;
            }

            const auto result = tgtunnel::start_proxy(
                static_cast<std::uint16_t>(port)
            );
            std::cerr << result << '\n';
            return EXIT_FAILURE;
        } catch (const std::exception&) {
            std::cerr << "Invalid port\n";
            return EXIT_FAILURE;
        }
    }

    std::cerr << "Usage: tgtunnel-cli [--info] | start <port>\n";
    return EXIT_FAILURE;
}
