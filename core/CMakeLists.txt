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
