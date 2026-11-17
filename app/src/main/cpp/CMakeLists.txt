cmake_minimum_required(VERSION 3.22.1)
project(nevera_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(nevera_native SHARED
    nevera_dsp.cpp
)

target_compile_options(nevera_native PRIVATE -O3 -fvisibility=hidden -Wall -Wextra)
find_library(log-lib log)
target_link_libraries(nevera_native PRIVATE ${log-lib})
target_link_options(nevera_native PRIVATE "-Wl,-z,max-page-size=16384")
