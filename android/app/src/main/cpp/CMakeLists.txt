cmake_minimum_required(VERSION 3.22.1)
project(tgtunnel_android LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

get_filename_component(TGTUNNEL_ROOT
    "${CMAKE_CURRENT_LIST_DIR}/../../../../../"
    ABSOLUTE
)

add_subdirectory(
    "${TGTUNNEL_ROOT}/core"
    "${CMAKE_CURRENT_BINARY_DIR}/core"
)

add_library(tgtunnel SHARED
    jni_bridge.cpp
)

target_include_directories(tgtunnel PRIVATE
    "${TGTUNNEL_ROOT}/core/include"
)

target_compile_options(tgtunnel PRIVATE
    -O2
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

target_link_options(tgtunnel PRIVATE
    -Wl,--gc-sections
)

target_link_libraries(tgtunnel PRIVATE tgtunnel_core)
