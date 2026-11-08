cmake_minimum_required(VERSION 3.22.1)
project(tgtunnel_core LANGUAGES C CXX)

include(FetchContent)

set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    mbedtls
    GIT_REPOSITORY https://github.com/Mbed-TLS/mbedtls.git
    GIT_TAG v3.6.5
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(mbedtls)

add_library(tgtunnel_core STATIC
    src/engine.cpp
    src/mtproto_handshake.cpp
    src/mtproto_crypto.cpp
)

target_include_directories(tgtunnel_core
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_features(tgtunnel_core PUBLIC cxx_std_17)
target_link_libraries(tgtunnel_core PUBLIC mbedcrypto)

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
