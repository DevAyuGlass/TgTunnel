cmake_minimum_required(VERSION 3.22.1)
project(tgtunnel_core LANGUAGES CXX)

add_library(tgtunnel_core STATIC
    src/engine.cpp
)

target_include_directories(tgtunnel_core
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_features(tgtunnel_core PUBLIC cxx_std_17)

set_target_properties(tgtunnel_core PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)

if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(tgtunnel_core PRIVATE
        -Wall
        -Wextra
        -Wpedantic
        -Wconversion
        -Wsign-conversion
    )
endif()

option(TGTUNNEL_BUILD_TESTS "Build TgTunnel core tests" OFF)
if(TGTUNNEL_BUILD_TESTS)
    enable_testing()
    add_executable(tgtunnel-core-tests tests/engine_tests.cpp)
    target_link_libraries(tgtunnel-core-tests PRIVATE tgtunnel_core)
    add_test(NAME tgtunnel-core-tests COMMAND tgtunnel-core-tests)
endif()
