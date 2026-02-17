/* NEURALGRAM COMPLETE AI CAMERA SYSTEM */
/* Fixed: JNI name, memory leaks, thread safety, removed main() */

#include <jni.h>
#include <stdio.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <time.h>
#include <pthread.h>
#include <android/log.h>

#define LOG_TAG "NeuralGram"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ============ CONFIGURATION ============ */
#define MAX_FAVORITES   100
#define FEATURE_COUNT   10
#define PARAM_COUNT     5
#define SCENE_COUNT     7

/* ============ STRUCTURES ============ */
typedef struct {
    float favorite_features[MAX_FAVORITES * FEATURE_COUNT];
    float favorite_parameters[MAX_FAVORITES * PARAM_COUNT];
    int   favorite_count;
    float personal_bias[FEATURE_COUNT];
    int   learning_strength;
    time_t last_update;
    int   scene_patterns[SCENE_COUNT];
} PersonalMemory;

/* ============ GLOBAL STATE (protected by mutex) ============ */
static PersonalMemory personal_memory;
static int   total_images_processed = 0;
static int   current_scene_type     = 0;
static float last_params[PARAM_COUNT] = {1.1f, 1.15f, 1.2f, 0.08f, 0.5f};
static pthread_mutex_t memory_mutex = PTHREAD_MUTEX_INITIALIZER;

/* ============ INITIALIZATION ============ */
JNIEXPORT void JNICALL
Java_com_neuralgram_app_MainActivity_initPersonality(JNIEnv* env, jobject obj) {
    pthread_mutex_lock(&memory_mutex);

    personal_memory.favorite_count  = 0;
    personal_memory.learning_strength = 75;
    personal_memory.last_update      = time(NULL);

    for (int i = 0; i < FEATURE_COUNT; i++)
        personal_memory.personal_bias[i] = 0.5f;

    for (int i = 0; i < SCENE_COUNT; i++)
        personal_memory.scene_patterns[i] = 0;

    pthread_mutex_unlock(&memory_mutex);

    LOGI("Complete AI system initialized");
    LOGI("Memory: %d favorites, %d features, %d parameters",
         MAX_FAVORITES, FEATURE_COUNT, PARAM_COUNT);
}

/* ============ EXTRACT PHOTO SIGNATURE ============ */
static void extract_photo_signature(const uint8_t* image, int width, int height,
                                    float* signature) {
    float brightness_sum = 0;
    float r_sum = 0, g_sum = 0, b_sum = 0;
    float min_bright = 1.0f, max_bright = 0.0f;
    float warm_sum = 0, cool_sum = 0;
    float saturation_sum = 0;
    int   sample_count = 0;
    int   total_bytes  = width * height * 3;

    for (int y = 0; y < height; y += 8) {
        for (int x = 0; x < width; x += 8) {
            int idx = (y * width + x) * 3;
            if (idx + 2 >= total_bytes) continue;

            float r = image[idx]     / 255.0f;
            float g = image[idx + 1] / 255.0f;
            float b = image[idx + 2] / 255.0f;

            float brightness = 0.299f * r + 0.587f * g + 0.114f * b;
            brightness_sum += brightness;
            r_sum += r; g_sum += g; b_sum += b;

            if (brightness < min_bright) min_bright = brightness;
            if (brightness > max_bright) max_bright = brightness;

            warm_sum += (r + g * 0.5f);
            cool_sum += b;

            float max_ch = fmaxf(fmaxf(r, g), b);
            float min_ch = fminf(fminf(r, g), b);
            if (max_ch > 0)
                saturation_sum += (max_ch - min_ch) / max_ch;

            sample_count++;
        }
    }

    if (sample_count == 0) sample_count = 1;

    signature[0] = brightness_sum / sample_count;
    signature[1] = r_sum / sample_count;
    signature[2] = g_sum / sample_count;
    signature[3] = b_sum / sample_count;
    signature[4] = max_bright - min_bright;
    signature[5] = warm_sum / (warm_sum + cool_sum + 0.001f);
    signature[6] = saturation_sum / sample_count;
    signature[7] = r_sum / (g_sum + 0.001f);
    signature[8] = g_sum / (b_sum + 0.001f);
    signature[9] = (r_sum + g_sum) / (b_sum + 0.001f);
}

/* ============ DETECT SCENE TYPE ============ */
static int detect_scene_type(const float* signature) {
    float brightness = signature[0];
    float contrast   = signature[4];
    float warmth     = signature[5];
    float saturation = signature[6];
    float rg_ratio   = signature[7];

    if (brightness < 0.3f && contrast < 0.2f)                        return 2; // Low light
    if (brightness > 0.4f && brightness < 0.7f && rg_ratio > 1.1f)   return 0; // Portrait
    if (warmth > 0.7f && brightness > 0.3f)                           return 3; // Sunset
    if (brightness > 0.6f && contrast > 0.3f)                         return 1; // Landscape
    if (saturation > 0.7f)                                             return 5; // Macro
    if (brightness > 0.3f && brightness < 0.6f)                       return 4; // Indoor
    return 6; // Unknown
}

/* ============ ADD FAVORITE PHOTO ============ */
JNIEXPORT void JNICALL
Java_com_neuralgram_app_MainActivity_addFavorite(
    JNIEnv* env, jobject obj,
    jbyteArray image, jint width, jint height,
    jfloat exposure, jfloat contrast, jfloat saturation,
    jfloat sharpness, jfloat warmth) {

    jbyte* img_bytes = (*env)->GetByteArrayElements(env, image, NULL);
    if (!img_bytes) {
        LOGE("addFavorite: failed to get image bytes");
        return;
    }

    pthread_mutex_lock(&memory_mutex);

    /* Evict oldest if full */
    if (personal_memory.favorite_count >= MAX_FAVORITES) {
        memmove(&personal_memory.favorite_features[0],
                &personal_memory.favorite_features[FEATURE_COUNT],
                (MAX_FAVORITES - 1) * FEATURE_COUNT * sizeof(float));
        memmove(&personal_memory.favorite_parameters[0],
                &personal_memory.favorite_parameters[PARAM_COUNT],
                (MAX_FAVORITES - 1) * PARAM_COUNT * sizeof(float));
        personal_memory.favorite_count--;
    }

    int idx = personal_memory.favorite_count;

    float signature[FEATURE_COUNT];
    extract_photo_signature((const uint8_t*)img_bytes, width, height, signature);

    memcpy(&personal_memory.favorite_features[idx * FEATURE_COUNT],
           signature, FEATURE_COUNT * sizeof(float));

    personal_memory.favorite_parameters[idx * PARAM_COUNT + 0] = exposure;
    personal_memory.favorite_parameters[idx * PARAM_COUNT + 1] = contrast;
    personal_memory.favorite_parameters[idx * PARAM_COUNT + 2] = saturation;
    personal_memory.favorite_parameters[idx * PARAM_COUNT + 3] = sharpness;
    personal_memory.favorite_parameters[idx * PARAM_COUNT + 4] = warmth;

    personal_memory.favorite_count++;
    personal_memory.last_update = time(NULL);

    int scene = detect_scene_type(signature);
    personal_memory.scene_patterns[scene]++;
    current_scene_type = scene;

    float learn_rate = 0.1f * (personal_memory.learning_strength / 100.0f);
    for (int i = 0; i < FEATURE_COUNT; i++) {
        personal_memory.personal_bias[i] =
            personal_memory.personal_bias[i] * (1.0f - learn_rate) +
            signature[i] * learn_rate;
    }

    int count = personal_memory.favorite_count;
    pthread_mutex_unlock(&memory_mutex);

    (*env)->ReleaseByteArrayElements(env, image, img_bytes, JNI_ABORT);
    LOGI("Favorite #%d added (Scene: %d)", count, scene);
}

/* ============ GENERATE PERSONALIZED PARAMETERS ============ */
static void generate_personalized_parameters(float* exposure, float* contrast,
                                             float* saturation, float* sharpness,
                                             float* warmth) {
    float params[PARAM_COUNT] = {1.1f, 1.15f, 1.2f, 0.08f, 0.5f};

    /* Must be called with memory_mutex held */
    if (personal_memory.favorite_count > 0) {
        float bias_strength = personal_memory.learning_strength / 200.0f;

        params[0] += (personal_memory.personal_bias[0] - 0.5f) * 0.3f * bias_strength;
        params[1] += (personal_memory.personal_bias[4] - 0.5f) * 0.4f * bias_strength;
        params[2] += (personal_memory.personal_bias[6] - 0.5f) * 0.4f * bias_strength;
        params[4]  =  0.5f + (personal_memory.personal_bias[5] - 0.5f) * bias_strength;

        switch (current_scene_type) {
            case 0: // Portrait
                params[2] *= 1.1f; params[3] *= 0.8f; params[4] *= 1.2f; break;
            case 1: // Landscape
                params[0] *= 1.15f; params[1] *= 1.2f; params[2] *= 1.3f; break;
            case 2: // Low light
                params[0] *= 1.3f; params[3] *= 0.5f; break;
            case 3: // Sunset
                params[4] *= 1.4f; params[2] *= 1.25f; break;
            case 4: // Indoor
                params[0] *= 1.1f; params[3] *= 0.7f; break;
            case 5: // Macro
                params[2] *= 1.2f; params[3] *= 1.3f; break;
            default: break;
        }
    }

    params[0] = fmaxf(0.8f, fminf(1.8f, params[0]));
    params[1] = fmaxf(0.8f, fminf(1.5f, params[1]));
    params[2] = fmaxf(0.8f, fminf(1.8f, params[2]));
    params[3] = fmaxf(0.01f, fminf(0.2f,  params[3]));
    params[4] = fmaxf(0.2f, fminf(0.8f,  params[4]));

    memcpy(last_params, params, PARAM_COUNT * sizeof(float));

    *exposure   = params[0];
    *contrast   = params[1];
    *saturation = params[2];
    *sharpness  = params[3];
    *warmth     = params[4];
}

/* ============ PROCESS IMAGE ============ */
JNIEXPORT void JNICALL
Java_com_neuralgram_app_MainActivity_processImage(
    JNIEnv* env, jobject obj,
    jbyteArray input, jbyteArray output,
    jint width, jint height) {

    jbyte* in  = (*env)->GetByteArrayElements(env, input,  NULL);
    jbyte* out = (*env)->GetByteArrayElements(env, output, NULL);

    /* FIX: release whatever was fetched on early exit */
    if (!in || !out) {
        LOGE("processImage: null arrays");
        if (in)  (*env)->ReleaseByteArrayElements(env, input,  in,  JNI_ABORT);
        if (out) (*env)->ReleaseByteArrayElements(env, output, out, JNI_ABORT);
        return;
    }

    pthread_mutex_lock(&memory_mutex);
    total_images_processed++;

    float signature[FEATURE_COUNT];
    extract_photo_signature((const uint8_t*)in, width, height, signature);
    current_scene_type = detect_scene_type(signature);

    float exposure, contrast, saturation, sharpness, warmth;
    generate_personalized_parameters(&exposure, &contrast, &saturation,
                                     &sharpness, &warmth);
    int processed = total_images_processed;
    pthread_mutex_unlock(&memory_mutex);

    int total_pixels = width * height;

    for (int i = 0; i < total_pixels * 3; i += 3) {
        float r = (uint8_t)in[i]     / 255.0f;
        float g = (uint8_t)in[i + 1] / 255.0f;
        float b = (uint8_t)in[i + 2] / 255.0f;

        /* Warmth */
        if (warmth > 0.5f) {
            r *= 1.0f + (warmth - 0.5f) * 0.4f;
            b *= 1.0f - (warmth - 0.5f) * 0.3f;
        } else {
            b *= 1.0f + (0.5f - warmth) * 0.4f;
            r *= 1.0f - (0.5f - warmth) * 0.3f;
        }

        /* Exposure */
        r *= exposure; g *= exposure; b *= exposure;

        /* Contrast */
        r = 0.5f + (r - 0.5f) * contrast;
        g = 0.5f + (g - 0.5f) * contrast;
        b = 0.5f + (b - 0.5f) * contrast;

        /* Saturation */
        float lum = 0.299f * r + 0.587f * g + 0.114f * b;
        r = lum + saturation * (r - lum);
        g = lum + saturation * (g - lum);
        b = lum + saturation * (b - lum);

        /* Clamp */
        r = fmaxf(0.0f, fminf(1.0f, r));
        g = fmaxf(0.0f, fminf(1.0f, g));
        b = fmaxf(0.0f, fminf(1.0f, b));

        out[i]     = (jbyte)(r * 255);
        out[i + 1] = (jbyte)(g * 255);
        out[i + 2] = (jbyte)(b * 255);
    }

    LOGI("Processed image #%d (%dx%d) Exp=%.2f Con=%.2f Sat=%.2f Warm=%.2f Scene=%d",
         processed, width, height, exposure, contrast, saturation, warmth,
         current_scene_type);

    (*env)->ReleaseByteArrayElements(env, input,  in,  JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, output, out, 0);
}

/* ============ INTERFACE FUNCTIONS ============ */
/* FIX: Renamed from Java_test_function to testSystem (valid JNI name) */
JNIEXPORT void JNICALL
Java_com_neuralgram_app_MainActivity_testSystem(JNIEnv* env, jobject obj) {
    pthread_mutex_lock(&memory_mutex);
    int fav   = personal_memory.favorite_count;
    int learn = personal_memory.learning_strength;
    int proc  = total_images_processed;
    time_t upd = personal_memory.last_update;
    int patterns[SCENE_COUNT];
    memcpy(patterns, personal_memory.scene_patterns, sizeof(patterns));
    pthread_mutex_unlock(&memory_mutex);

    const char* scene_names[] = {
        "Portrait", "Landscape", "Low Light", "Sunset",
        "Indoor", "Macro", "Unknown"
    };

    LOGI("==============================");
    LOGI("NEURALGRAM AI CAMERA SYSTEM");
    LOGI("Favorites Stored: %d", fav);
    LOGI("Learning Strength: %d%%", learn);
    LOGI("Images Processed: %d", proc);
    LOGI("Last Update: %s", ctime(&upd));
    for (int i = 0; i < SCENE_COUNT; i++) {
        if (patterns[i] > 0)
            LOGI("  %s: %d", scene_names[i], patterns[i]);
    }
    LOGI("==============================");
}

JNIEXPORT jstring JNICALL
Java_com_neuralgram_app_MainActivity_getPersonalityStats(JNIEnv* env, jobject obj) {
    char stats[1024];

    const char* scene_names[] = {
        "Portrait", "Landscape", "Low Light", "Sunset",
        "Indoor", "Macro", "Unknown"
    };

    pthread_mutex_lock(&memory_mutex);
    int fav   = personal_memory.favorite_count;
    int learn = personal_memory.learning_strength;
    int proc  = total_images_processed;
    float bias4 = personal_memory.personal_bias[4];
    int patterns[SCENE_COUNT];
    memcpy(patterns, personal_memory.scene_patterns, sizeof(patterns));

    float avg_warmth = 0, avg_saturation = 0;
    if (fav > 0) {
        for (int i = 0; i < fav; i++) {
            avg_warmth     += personal_memory.favorite_parameters[i * PARAM_COUNT + 4];
            avg_saturation += personal_memory.favorite_parameters[i * PARAM_COUNT + 2];
        }
        avg_warmth     /= fav;
        avg_saturation /= fav;
    }

    int most_common = 0;
    for (int i = 1; i < SCENE_COUNT; i++) {
        if (patterns[i] > patterns[most_common]) most_common = i;
    }
    pthread_mutex_unlock(&memory_mutex);

    if (fav > 0) {
        snprintf(stats, sizeof(stats),
            "NEURALGRAM AI PERSONALITY REPORT\n\n"
            "Favorites Learned: %d\n"
            "Learning Strength: %d%%\n"
            "Images Processed: %d\n"
            "Memory Used: %d/%d\n\n"
            "YOUR PHOTOGRAPHY PROFILE:\n"
            "Most Common Scene: %s\n"
            "Warmth Preference: %.0f%%\n"
            "Saturation Level: %.0f%%\n"
            "Contrast Bias: %.2f\n\n"
            "AI STATUS: Active Learning\n"
            "Your camera is adapting to\nyour unique photography style!",
            fav, learn, proc, fav, MAX_FAVORITES,
            scene_names[most_common],
            avg_warmth * 100, avg_saturation * 100, bias4);
    } else {
        snprintf(stats, sizeof(stats),
            "NEURALGRAM AI CAMERA SYSTEM\n\n"
            "Ready to learn your photography style!\n\n"
            "SYSTEM FEATURES:\n"
            "* Personal memory: %d photos\n"
            "* Scene detection: %d types\n"
            "* Parameter learning: %d params\n"
            "* Offline processing: Yes\n\n"
            "HOW IT WORKS:\n"
            "1. You take photos\n"
            "2. Mark favorites\n"
            "3. AI learns patterns\n"
            "4. Auto-enhances new photos\n\n"
            "All processing happens offline\non your device for privacy!",
            MAX_FAVORITES, SCENE_COUNT, PARAM_COUNT);
    }

    return (*env)->NewStringUTF(env, stats);
}

JNIEXPORT jint JNICALL
Java_com_neuralgram_app_MainActivity_getFavoriteCount(JNIEnv* env, jobject obj) {
    pthread_mutex_lock(&memory_mutex);
    int count = personal_memory.favorite_count;
    pthread_mutex_unlock(&memory_mutex);
    return count;
}

JNIEXPORT void JNICALL
Java_com_neuralgram_app_MainActivity_setLearningStrength(JNIEnv* env, jobject obj,
                                                          jint strength) {
    pthread_mutex_lock(&memory_mutex);
    personal_memory.learning_strength = strength;
    pthread_mutex_unlock(&memory_mutex);
    LOGI("Learning strength updated to %d%%", strength);
}

JNIEXPORT void JNICALL
Java_com_neuralgram_app_MainActivity_clearPersonality(JNIEnv* env, jobject obj) {
    pthread_mutex_lock(&memory_mutex);
    personal_memory.favorite_count = 0;
    total_images_processed         = 0;
    current_scene_type             = 0;
    for (int i = 0; i < FEATURE_COUNT; i++) personal_memory.personal_bias[i] = 0.5f;
    for (int i = 0; i < SCENE_COUNT;   i++) personal_memory.scene_patterns[i] = 0;
    pthread_mutex_unlock(&memory_mutex);
    LOGI("Personality memory cleared");
}
