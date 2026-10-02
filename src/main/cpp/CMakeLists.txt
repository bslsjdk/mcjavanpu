cmake_minimum_required(VERSION 3.22.1)
project(mcjavanpu)

add_library(mcjavanpu SHARED mcjavanpu.cpp)

set(QNN_INC "" CACHE PATH "Path to the QNN include directory")
if(NOT QNN_INC)
    message(FATAL_ERROR "QNN_INC must point to a QNN include directory")
endif()

target_include_directories(mcjavanpu PRIVATE ${QNN_INC})

find_library(log-lib log)
target_link_libraries(mcjavanpu ${log-lib} dl)

target_compile_features(mcjavanpu PRIVATE cxx_std_17)
target_compile_options(mcjavanpu PRIVATE
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
        -Wall
        -Wextra)
target_link_options(mcjavanpu PRIVATE -Wl,--gc-sections)
