cmake_minimum_required(VERSION 3.22.1)
project(TgTunnel LANGUAGES C CXX)

add_subdirectory(core)

add_executable(tgtunnel-cli
    cli/main.cpp
)
target_link_libraries(tgtunnel-cli PRIVATE tgtunnel_core)
target_compile_features(tgtunnel-cli PRIVATE cxx_std_17)
