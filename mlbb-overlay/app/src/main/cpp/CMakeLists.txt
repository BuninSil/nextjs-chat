cmake_minimum_required(VERSION 3.18)
project(tunbridge C)

# zdtun собирается напрямую из исходников (его собственный CMakeLists тянет
# лишние десктопные таргеты).
add_library(zdtun STATIC
        zdtun/zdtun.c
        zdtun/utils.c)
target_include_directories(zdtun PUBLIC zdtun)
target_compile_definitions(zdtun PUBLIC ANDROID _LITTLE_ENDIAN)
target_compile_options(zdtun PRIVATE -Wall -Wno-unused-variable -Wno-unused-function -fPIC)

add_library(tunbridge SHARED tunbridge.c)
find_library(log-lib log)
target_link_libraries(tunbridge zdtun ${log-lib})
