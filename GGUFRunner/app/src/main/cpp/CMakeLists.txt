cmake_minimum_required(VERSION 3.22.1)
project(gguf_bridge LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---- llama.cpp build options: CPU only, small and fast ----
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
# NEON + dot-product: runs on virtually every arm64 phone from ~2018 on and
# enables the fast Q4/Q8 int8 kernels. (Add +i8mm only if all your devices have it.)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)

# Use a local copy if you vendored one (git submodule add ... app/src/main/cpp/llama.cpp),
# otherwise it is downloaded once at configure time.
set(LLAMA_LOCAL_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
if(EXISTS "${LLAMA_LOCAL_DIR}/CMakeLists.txt")
    add_subdirectory("${LLAMA_LOCAL_DIR}" llama_build)
else()
    include(FetchContent)
    FetchContent_Declare(llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        b6000
        GIT_SHALLOW    TRUE)
    FetchContent_MakeAvailable(llama)
endif()

add_library(gguf_bridge SHARED gguf_bridge.cpp)
target_compile_options(gguf_bridge PRIVATE -O3 -fvisibility=hidden)
target_link_options(gguf_bridge PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
target_link_libraries(gguf_bridge PRIVATE llama ggml android log)
