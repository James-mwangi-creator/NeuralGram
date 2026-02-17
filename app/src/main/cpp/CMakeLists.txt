cmake_minimum_required(VERSION 3.22.1)
project(neuralgram)

# ── NeuralGram JNI library ──────────────────────────────────────────────────
add_library(neuralgram SHARED neuralgram.c)

target_link_libraries(neuralgram
    android
    log
    m)         # math library for fmaxf / fminf
