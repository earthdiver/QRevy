cmake_minimum_required(VERSION 3.16)
project(QRevyBoundary LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(ZXING_WRITERS OFF CACHE BOOL "" FORCE)
set(ZXING_READERS ON CACHE BOOL "" FORCE)
add_compile_options("-fexceptions")
add_subdirectory("${ZXING_SOURCE_DIR}/core" "${CMAKE_BINARY_DIR}/zxing")
add_executable(boundary boundary.cpp)
target_link_libraries(boundary PRIVATE ZXing::ZXing)
target_link_options(boundary PRIVATE
    "-fexceptions" "--no-entry" "-sENVIRONMENT=web,worker,node"
    "-sFILESYSTEM=0" "-sMODULARIZE=1" "-sEXPORT_ES6=1" "-sDYNAMIC_EXECUTION=0"
    "-sALLOW_MEMORY_GROWTH=1" "-sEXPORTED_FUNCTIONS=['_malloc','_free','_detectBoundaries','_hasDecoded','_textPointer','_textLength','_bytesPointer','_bytesLength']"
    "-sEXPORTED_RUNTIME_METHODS=['HEAPU8','HEAP32']")
