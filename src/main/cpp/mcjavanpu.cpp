#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <cstdio>
#include <cstring>
#include <android/log.h>

#define TAG "MCJavaNPU"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {
using GetProvidersFn = int (*)(const void ***, unsigned int *);
using BackendCreateFn = int (*)(void *, const void *, void **);
using DeviceCreateFn = int (*)(void *, const void *, void **);
using ContextCreateFn = int (*)(void *, void *, const void *, void **);
using GraphCreateFn = int (*)(void *, const char *, const void **, void **);
using GraphFinalizeFn = int (*)(void *, const void *, void *);
using FreeFn = int (*)(void *);

struct Runtime {
    void *qnn = nullptr, *backend = nullptr, *device = nullptr, *context = nullptr, *graph = nullptr;
    GetProvidersFn getProviders = nullptr;
    BackendCreateFn backendCreate = nullptr;
    DeviceCreateFn deviceCreate = nullptr;
    ContextCreateFn contextCreate = nullptr;
    GraphCreateFn graphCreate = nullptr;
    GraphFinalizeFn graphFinalize = nullptr;
    FreeFn backendFree = nullptr, deviceFree = nullptr, contextFree = nullptr, graphFree = nullptr;
    std::string deviceInfo;
};
Runtime g;

template <typename T> T sym(const char *name) {
    return reinterpret_cast<T>(dlsym(g.qnn, name));
}

bool loadQnn() {
    const char *paths[] = {
        "/odm/lib64/aiframe/libQnnHtp.so",
        "/odm/lib64/libQnnHtp.so"
    };
    for (const char *path : paths) {
        g.qnn = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (g.qnn) {
            __android_log_print(ANDROID_LOG_INFO, TAG, "QNN loaded: %s", path);
            return true;
        }
        const char *err = dlerror();
        LOGE("QNN load failed: %s (%s)", path, err ? err : "?");
    }
    return false;
}

bool resolve() {
    g.getProviders = sym<GetProvidersFn>("QnnInterface_getProviders");
    g.backendCreate = sym<BackendCreateFn>("QnnBackend_create");
    g.deviceCreate = sym<DeviceCreateFn>("QnnDevice_create");
    g.contextCreate = sym<ContextCreateFn>("QnnContext_create");
    g.graphCreate = sym<GraphCreateFn>("QnnGraph_create");
    g.graphFinalize = sym<GraphFinalizeFn>("QnnGraph_finalize");
    g.backendFree = sym<FreeFn>("QnnBackend_free");
    g.deviceFree = sym<FreeFn>("QnnDevice_free");
    g.contextFree = sym<FreeFn>("QnnContext_free");
    g.graphFree = sym<FreeFn>("QnnGraph_free");
    return g.getProviders && g.backendCreate && g.deviceCreate &&
           g.contextCreate && g.graphCreate && g.graphFinalize;
}

bool providerInfo() {
    const void **providers = nullptr;
    unsigned int count = 0;
    int rc = g.getProviders(&providers, &count);
    if (rc != 0 || !providers || count == 0) return false;

    const unsigned char *p = reinterpret_cast<const unsigned char *>(providers[0]);
    void *namePtr = nullptr;
    unsigned int major = 0, minor = 0;
    std::memcpy(&namePtr, p + 0x00, sizeof(namePtr));
    std::memcpy(&major, p + 0x10, sizeof(major));
    std::memcpy(&minor, p + 0x14, sizeof(minor));

    const char *name = namePtr ? reinterpret_cast<const char *>(namePtr) : "unknown";
    char buf[256];
    std::snprintf(buf, sizeof(buf), "provider=%s api=%u.%u providers=%u",
                  name, major, minor, count);
    g.deviceInfo = buf;
    return true;
}

void cleanup() {
    if (g.graphFree && g.graph) g.graphFree(g.graph);
    g.graph = nullptr;
    if (g.contextFree && g.context) g.contextFree(g.context);
    g.context = nullptr;
    if (g.deviceFree && g.device) g.deviceFree(g.device);
    g.device = nullptr;
    if (g.backendFree && g.backend) g.backendFree(g.backend);
    g.backend = nullptr;
    if (g.qnn) dlclose(g.qnn);
    g.qnn = nullptr;
}

bool initializeRuntime() {
    if (!loadQnn() || !resolve() || !providerInfo()) {
        cleanup();
        return false;
    }

    int rc = g.backendCreate(nullptr, nullptr, &g.backend);
    if (rc != 0 || !g.backend) { LOGE("backendCreate rc=%d", rc); cleanup(); return false; }

    rc = g.deviceCreate(nullptr, nullptr, &g.device);
    if (rc != 0 || !g.device) { LOGE("deviceCreate rc=%d", rc); cleanup(); return false; }

    rc = g.contextCreate(g.backend, g.device, nullptr, &g.context);
    if (rc != 0 || !g.context) { LOGE("contextCreate rc=%d", rc); cleanup(); return false; }

    rc = g.graphCreate(g.context, "mcjavanpu_smoke", nullptr, &g.graph);
    if (rc != 0 || !g.graph) { LOGE("graphCreate rc=%d", rc); cleanup(); return false; }

    rc = g.graphFinalize(g.graph, nullptr, nullptr);
    if (rc != 0) { LOGE("graphFinalize rc=%d", rc); cleanup(); return false; }

    return true;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_bslsjdk_mcjavanpu_NpuRuntime_nativeInit(JNIEnv *, jclass) {
    return initializeRuntime() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_bslsjdk_mcjavanpu_NpuRuntime_nativeGetDeviceInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(g.deviceInfo.empty() ? "unknown" : g.deviceInfo.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_bslsjdk_mcjavanpu_NpuRuntime_nativeTest(JNIEnv *, jclass) {
    return (g.qnn && g.backend && g.device && g.context && g.graph) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_bslsjdk_mcjavanpu_NpuRuntime_nativeBenchmark(JNIEnv *env, jclass) {
    return env->NewStringUTF("stage0 smoke graph ready; graphExecute benchmark is next");
}

extern "C" JNIEXPORT void JNICALL
Java_bslsjdk_mcjavanpu_NpuRuntime_nativeShutdown(JNIEnv *, jclass) {
    cleanup();
}
