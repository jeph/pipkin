cmake_minimum_required(VERSION 3.22)

# pipkin-syntax, the tree-sitter grammars; pipkin-llama in llama/, llama.cpp's server for local models; and
# pipkin-opencl-probe in opencl/, which finds the GPU that OpenCL reports.
project(pipkin LANGUAGES C CXX)

# The pinned sources fetched by Gradle's fetchTreeSitterSources task.
if(NOT TREE_SITTER_SOURCES)
  message(FATAL_ERROR "TREE_SITTER_SOURCES is not set")
endif()
set(TS ${TREE_SITTER_SOURCES})

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_VISIBILITY_PRESET hidden)

# Grammar source directories, each holding parser.c and maybe scanner.c.
set(GRAMMAR_DIRS
  bash/src
  c/src
  c-sharp/src
  cpp/src
  css/src
  dart/src
  dockerfile/src
  go/src
  ini/src
  java/src
  javascript/src
  json/src
  kotlin/src
  lua/src
  make/src
  perl/src
  php/php/src
  python/src
  ruby/src
  rust/src
  swift/src
  toml/src
  typescript/tsx/src
  typescript/typescript/src
  xml/xml/src
  yaml/src
)

set(GRAMMAR_FILES)
foreach(dir ${GRAMMAR_DIRS})
  set(files ${TS}/${dir}/parser.c)
  if(EXISTS ${TS}/${dir}/scanner.c)
    list(APPEND files ${TS}/${dir}/scanner.c)
  endif()
  # Headers shared between a grammar's dialects (PHP's common/scanner.h) include "tree_sitter/..."
  # from outside src, so each grammar's own src goes on its include path.
  set_source_files_properties(${files} PROPERTIES INCLUDE_DIRECTORIES ${TS}/${dir})
  list(APPEND GRAMMAR_FILES ${files})
endforeach()

add_library(pipkin-syntax SHARED syntax.c ${TS}/tree-sitter/lib/src/lib.c ${GRAMMAR_FILES})

target_include_directories(pipkin-syntax PRIVATE ${TS}/tree-sitter/lib/include ${TS}/tree-sitter/lib/src)

# tree-sitter's own build flags; with symbols hidden, only the JNI functions are exported.
target_compile_definitions(pipkin-syntax PRIVATE _POSIX_C_SOURCE=200112L _DEFAULT_SOURCE TREE_SITTER_HIDE_SYMBOLS)

# JNI fixes every native function's signature, so unused env and class parameters are expected.
target_compile_options(pipkin-syntax PRIVATE -Wall -Wextra -Werror -Wno-unused-parameter)

# Third-party sources build at -O2 without warnings, even in debug builds, where parsing at -O0
# would lag behind a streaming reply.
set_source_files_properties(${TS}/tree-sitter/lib/src/lib.c ${GRAMMAR_FILES} PROPERTIES COMPILE_OPTIONS "-O2;-w")

add_subdirectory(opencl)
add_subdirectory(llama)
