cmake_minimum_required(VERSION 3.22.1)
project(playfair C)

# RPiPlay's FairPlay (playfair) implementation + a thin JNI bridge → libplayfair.so.
add_library(playfair SHARED
    fairplay_jni.c
    playfair/playfair.c
    playfair/omg_hax.c
    playfair/hand_garble.c
    playfair/modified_md5.c
    playfair/sap_hash.c)

target_include_directories(playfair PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/playfair)

# The reverse-engineered C triggers benign warnings; keep them non-fatal.
target_compile_options(playfair PRIVATE -w)
