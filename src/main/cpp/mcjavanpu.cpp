#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <fstream>
#include <chrono>
#include <ctime>
#include <filesystem>
#include <unistd.h>
#include <cerrno>

#include "QnnInterface.h"
#include "QnnLog.h"
#include "QnnBackend.h"
#include "QnnDevice.h"
#include "QnnContext.h"
#include "QnnGraph.h"
#include "QnnTensor.h"
#include "QnnTypes.h"
#include "QnnOpDef.h"

#define TAG "MCJavaNPU"
#define BACKEND_ID_HTP 6
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {
std::ofstream gLog;
std::string gTrace;
const char* kLogPath = "logs/mcjavanpu-npu.log";
const size_t kMaxTrace = 48000;

void ensureLog() {
    if (gLog.is_open()) return;
    try {
        std::filesystem::create_directories("logs");
        gLog.open(kLogPath, std::ios::app);
    } catch (...) {
    }
}

void logLine(const char* level, const std::string& msg) {
    gTrace += std::string("[") + level + "] " + msg + "\n";
    if (gTrace.size() > kMaxTrace) gTrace.erase(0, gTrace.size() - kMaxTrace);
    ensureLog();
    if (gLog.is_open()) {
        auto now = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
        char ts[64];
        std::tm tm{};
        localtime_r(&now, &tm);
        std::strftime(ts, sizeof(ts), "%Y-%m-%d %H:%M:%S", &tm);
        gLog << "[" << ts << "] [" << level << "] " << msg << std::endl;
        gLog.flush();
    }
}

void info(const std::string& msg) {
    LOGI("%s", msg.c_str());
    logLine("INFO", msg);
}

void error(const std::string& msg) {
    LOGE("%s", msg.c_str());
    logLine("ERROR", msg);
}

void stage(const std::string& msg) {
    info("STAGE " + msg);
}

struct Runtime {
    void* qnn = nullptr;
    const QnnInterface_t* iface = nullptr;
    Qnn_LogHandle_t logger = nullptr;
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    Qnn_ContextHandle_t context = nullptr;
    std::string infoText;
    std::string error;
    bool ready = false;
};
Runtime g;

using GetProvidersFn = Qnn_ErrorHandle_t (*)(const QnnInterface_t ***, uint32_t *);

bool fail(const char* name, Qnn_ErrorHandle_t rc) {
    std::string msg;
    if (g.iface) {
        const auto& ftbl = g.iface->QNN_INTERFACE_VER_NAME;
        if (ftbl.errorGetMessage) {
            const char* qmsg = nullptr;
            Qnn_ErrorHandle_t mrc = ftbl.errorGetMessage(rc, &qmsg);
            if (mrc == QNN_SUCCESS && qmsg) msg = qmsg;
        }
    }
    char buf[768];
    std::snprintf(buf, sizeof(buf), "%s rc=%d%s%s",
                  name, (int)rc,
                  msg.empty() ? "" : " message=",
                  msg.empty() ? "" : msg.c_str());
    g.error = buf;
    error(g.error);
    return false;
}

void qnnLogCallback(const char* fmt, QnnLog_Level_t level, uint64_t, va_list args) {
    if (!fmt) return;
    char buf[2048];
    std::vsnprintf(buf, sizeof(buf), fmt, args);
    const char* levelName = "QNN";
    switch (level) {
        case QNN_LOG_LEVEL_ERROR: levelName = "QNN_ERROR"; break;
        case QNN_LOG_LEVEL_WARN: levelName = "QNN_WARN"; break;
        case QNN_LOG_LEVEL_INFO: levelName = "QNN_INFO"; break;
        case QNN_LOG_LEVEL_VERBOSE: levelName = "QNN_VERBOSE"; break;
        case QNN_LOG_LEVEL_DEBUG: levelName = "QNN_DEBUG"; break;
        default: break;
    }
    logLine(levelName, buf);
}

bool loadRuntime() {
    stage("QNN_LOAD_BEGIN");

    // The Minecraft JVM is launched by FCL/ZL2, so its linker namespace does
    // not necessarily search the plugin APK's nativeLibraryDir by soname.
    // npu_probe solved the same class of problem by using an app-owned
    // directory + absolute dlopen paths. Here we derive the directory of
    // this already-loaded library and use absolute sibling paths.
    Dl_info selfInfo{};
    std::string libDir;
    if (dladdr(reinterpret_cast<void*>(&loadRuntime), &selfInfo) && selfInfo.dli_fname) {
        libDir = selfInfo.dli_fname;
        const size_t slash = libDir.find_last_of('/');
        if (slash != std::string::npos) libDir.resize(slash);
    }

    if (libDir.empty()) {
        g.error = "cannot determine libmcjavanpu.so directory";
        error(g.error);
        return false;
    }

    info("QNN_LIB_DIR=" + libDir);

    // Same environment strategy as the proven npu_probe runner.
    // FastRPC/HTP uses colon-separated search paths.  The V73 Skel is a
    // DSP-side image: it must be discoverable through ADSP_LIBRARY_PATH, not
    // dlopen'ed into the ARM64 host process.
    // FastRPC on this Android 16/HTP V73 stack uses semicolon-separated
    // ADSP_LIBRARY_PATH entries. This matches the known-good npu_probe runner.
    const std::string adsp =
        libDir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp";
    const std::string ldPath =
        libDir + ":/vendor/dsp/cdsp:/vendor/lib64/";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);
    setenv("LD_LIBRARY_PATH", ldPath.c_str(), 1);
    info("ADSP_LIBRARY_PATH=" + adsp);
    info("LD_LIBRARY_PATH=" + ldPath);

    // Load the bundled stack by absolute path. Dependency failures are logged
    // individually; the final HTP load result decides whether initialization
    // can continue.
    // Do not dlopen the V73 Stub from the ARM64 app process. Its DT_NEEDED
    // chain contains libcdsprpc.so, which Android's app linker namespace may
    // reject even though QNN can resolve/use the DSP side through FastRPC.
    // The known-good probe does not explicitly dlopen the stub either.
    const char* deps[] = {
        "libc++_shared.so",
        "libQnnSystem.so",
        "libQnnHtpPrepare.so"
    };

    for (const char* name : deps) {
        const std::string path = libDir + "/" + name;
        info(std::string("QNN_DEP_BEGIN path=") + path);
        dlerror();
        void* h = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (h) {
            info(std::string("QNN_DEP_OK name=") + name);
        } else {
            const char* err = dlerror();
            error(std::string("QNN_DEP_FAIL name=") + name +
                  " error=" + (err ? err : "?"));
        }
    }

    const std::string skelPath = libDir + "/libQnnHtpV73Skel.so";
    {
        std::ifstream skel(skelPath);
        if (!skel.good()) {
            error("HTP_V73_SKEL_MISSING path=" + skelPath);
            g.error = "libQnnHtpV73Skel.so missing from runtime directory";
            return false;
        }
        info("HTP_V73_SKEL_PRESENT path=" + skelPath);
    }

    const std::string htpPath = libDir + "/libQnnHtp.so";
    info("DLOPEN_BEGIN path=" + htpPath);
    dlerror();
    g.qnn = dlopen(htpPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (g.qnn) {
        info("QNN_LOAD_OK path=" + htpPath);
        return true;
    }

    const char* err = dlerror();
    error(std::string("QNN_LOAD_FAIL path=") + htpPath +
          " error=" + (err ? err : "?"));

    // Keep vendor fallback for diagnostics. Android 16 may reject this path
    // from the app namespace, but the exact linker error is useful evidence.
    const char* vendorPaths[] = {
        "/odm/lib64/aiframe/libQnnHtp.so",
        "/odm/lib64/libQnnHtp.so"
    };
    for (const char* path : vendorPaths) {
        info(std::string("VENDOR_DLOPEN_BEGIN path=") + path);
        dlerror();
        g.qnn = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (g.qnn) {
            info(std::string("VENDOR_QNN_LOAD_OK path=") + path);
            return true;
        }
        const char* vendorErr = dlerror();
        error(std::string("VENDOR_QNN_LOAD_FAIL path=") + path +
              " error=" + (vendorErr ? vendorErr : "?"));
    }

    g.error = "dlopen bundled libQnnHtp.so failed";
    error(g.error);
    return false;
}

bool initQnn() {
    g.error.clear();
    g.infoText.clear();
    stage("INIT_BEGIN");
    info("PID=" + std::to_string((long long)getpid()));
    char cwd[1024]{};
    if (getcwd(cwd, sizeof(cwd))) info(std::string("CWD=") + cwd);
    else error(std::string("GETCWD_FAIL errno=") + std::to_string(errno));

    if (!loadRuntime()) return false;

    stage("GET_PROVIDERS_BEGIN");
    auto getProviders =
        reinterpret_cast<GetProvidersFn>(dlsym(g.qnn, "QnnInterface_getProviders"));
    if (!getProviders) {
        g.error = "dlsym QnnInterface_getProviders failed";
        error(g.error);
        return false;
    }
    info("GET_PROVIDERS_SYMBOL_OK");

    const QnnInterface_t** providers = nullptr;
    uint32_t count = 0;
    Qnn_ErrorHandle_t rc = getProviders(&providers, &count);
    if (rc != QNN_SUCCESS || !providers || count == 0) {
        return fail("getProviders", rc);
    }
    info("GET_PROVIDERS_OK count=" + std::to_string(count));

    for (uint32_t i = 0; i < count; ++i) {
        if (providers[i]) {
            info("PROVIDER[" + std::to_string(i) + "] backendId=" +
                 std::to_string((unsigned)providers[i]->backendId) +
                 " name=" + (providers[i]->providerName ? providers[i]->providerName : "null"));
            if (providers[i]->backendId == BACKEND_ID_HTP) {
                g.iface = providers[i];
            }
        }
    }

    // Never silently fall back to CPU.  A successful test is only meaningful
    // if the HTP provider was actually selected.
    if (!g.iface) {
        g.error = "HTP provider (backendId=6) not found; refusing CPU fallback";
        error(g.error);
        return false;
    }
    info("HTP_PROVIDER_SELECTED backendId=6");

    const auto& ftbl = g.iface->QNN_INTERFACE_VER_NAME;

    stage("BACKEND_CREATE_BEGIN");
    if (ftbl.logCreate) {
        rc = ftbl.logCreate(qnnLogCallback, QNN_LOG_LEVEL_DEBUG, &g.logger);
        info("LOG_CREATE rc=" + std::to_string((int)rc) + " level=DEBUG callback=enabled");
        if (rc != QNN_SUCCESS) g.logger = nullptr;
    }

    rc = ftbl.backendCreate(g.logger, nullptr, &g.backend);
    if (rc != QNN_SUCCESS || !g.backend) return fail("backendCreate", rc);
    info("BACKEND_CREATE_OK");

    stage("DEVICE_CREATE_BEGIN");
    rc = ftbl.deviceCreate(g.logger, nullptr, &g.device);
    if (rc != QNN_SUCCESS || !g.device) {
        error("DEVICE_CREATE_FAILED: HTP runtime/device initialization did not complete");
        return fail("deviceCreate", rc);
    }
    info("DEVICE_CREATE_OK");

    stage("CONTEXT_CREATE_BEGIN");
    rc = ftbl.contextCreate(g.backend, g.device, nullptr, &g.context);
    if (rc != QNN_SUCCESS || !g.context) return fail("contextCreate", rc);
    info("CONTEXT_CREATE_OK");

    char buf[256];
    std::snprintf(buf, sizeof(buf),
                  "backendId=%u providers=%u api=%u.%u",
                  (unsigned)g.iface->backendId,
                  (unsigned)count,
                  (unsigned)g.iface->apiVersion.coreApiVersion.major,
                  (unsigned)g.iface->apiVersion.coreApiVersion.minor);
    g.infoText = buf;
    g.ready = true;

    stage("INIT_SUCCESS");
    info("QNN_READY " + g.infoText);
    return true;
}

Qnn_Tensor_t makeTensor(const char* name, Qnn_TensorType_t type,
                        Qnn_DataType_t dt, uint32_t* dims, uint32_t rank) {
    Qnn_Tensor_t t = QNN_TENSOR_INIT;
    t.version = QNN_TENSOR_VERSION_1;
    t.v1.name = name;
    t.v1.type = type;
    t.v1.dataFormat = QNN_TENSOR_DATA_FORMAT_FLAT_BUFFER;
    t.v1.dataType = dt;
    t.v1.rank = rank;
    t.v1.dimensions = dims;
    t.v1.memType = QNN_TENSORMEMTYPE_RAW;
    t.v1.clientBuf.data = nullptr;
    t.v1.clientBuf.dataSize = 0;
    return t;
}

bool smokeTest() {
    if (!g.ready) {
        error("SMOKE_SKIPPED runtime_not_ready");
        return false;
    }

    stage("SMOKE_BEGIN");
    const auto& ftbl = g.iface->QNN_INTERFACE_VER_NAME;
    const uint32_t n = 16;
    uint32_t dims[1] = {n};

    Qnn_GraphHandle_t graph = nullptr;
    Qnn_ErrorHandle_t rc = ftbl.graphCreate(g.context, "mcjavanpu_smoke", nullptr, &graph);
    if (rc != QNN_SUCCESS || !graph) {
        error("GRAPH_CREATE_FAIL rc=" + std::to_string((int)rc));
        return false;
    }
    info("GRAPH_CREATE_OK");

    Qnn_Tensor_t a = makeTensor("a", QNN_TENSOR_TYPE_APP_WRITE, QNN_DATATYPE_FLOAT_32, dims, 1);
    Qnn_Tensor_t b = makeTensor("b", QNN_TENSOR_TYPE_APP_WRITE, QNN_DATATYPE_FLOAT_32, dims, 1);
    Qnn_Tensor_t c = makeTensor("c", QNN_TENSOR_TYPE_APP_READ, QNN_DATATYPE_FLOAT_32, dims, 1);

    rc = ftbl.tensorCreateGraphTensor(graph, &a);
    if (rc == QNN_SUCCESS) rc = ftbl.tensorCreateGraphTensor(graph, &b);
    if (rc == QNN_SUCCESS) rc = ftbl.tensorCreateGraphTensor(graph, &c);
    if (rc != QNN_SUCCESS) {
        error("TENSOR_CREATE_FAIL rc=" + std::to_string((int)rc));
        return false;
    }
    info("TENSOR_CREATE_OK");

    Qnn_Scalar_t scalar = QNN_SCALAR_INIT;
    scalar.dataType = QNN_DATATYPE_UINT_32;
    scalar.uint32Value = QNN_OP_ELEMENT_WISE_BINARY_OPERATION_ADD;

    Qnn_Param_t param = QNN_PARAM_INIT;
    param.paramType = QNN_PARAMTYPE_SCALAR;
    param.name = QNN_OP_ELEMENT_WISE_BINARY_PARAM_OPERATION;
    param.scalarParam = scalar;

    Qnn_Tensor_t inputs[2] = {a, b};
    Qnn_OpConfig_t op = QNN_OPCONFIG_INIT;
    op.v1.name = "add";
    op.v1.packageName = "qti.aisw";
    op.v1.typeName = QNN_OP_ELEMENT_WISE_BINARY;
    op.v1.numOfParams = 1;
    op.v1.params = &param;
    op.v1.numOfInputs = 2;
    op.v1.inputTensors = inputs;
    op.v1.numOfOutputs = 1;
    op.v1.outputTensors = &c;

    rc = ftbl.graphAddNode(graph, op);
    if (rc != QNN_SUCCESS) {
        error("GRAPH_ADD_NODE_FAIL rc=" + std::to_string((int)rc));
        return false;
    }
    info("GRAPH_ADD_NODE_OK");

    stage("GRAPH_FINALIZE_BEGIN");
    rc = ftbl.graphFinalize(graph, nullptr, nullptr);
    if (rc != QNN_SUCCESS) {
        error("GRAPH_FINALIZE_FAIL rc=" + std::to_string((int)rc));
        return false;
    }
    info("GRAPH_FINALIZE_OK backendId=" + std::to_string((unsigned)g.iface->backendId));

    std::vector<float> av(n), bv(n), cv(n, -999.0f);
    for (uint32_t i = 0; i < n; ++i) {
        av[i] = static_cast<float>(i);
        bv[i] = 2.0f;
    }

    Qnn_Tensor_t ea = a, eb = b, ec = c;
    ea.v1.clientBuf.data = av.data();
    ea.v1.clientBuf.dataSize = sizeof(float) * n;
    eb.v1.clientBuf.data = bv.data();
    eb.v1.clientBuf.dataSize = sizeof(float) * n;
    ec.v1.clientBuf.data = cv.data();
    ec.v1.clientBuf.dataSize = sizeof(float) * n;

    Qnn_Tensor_t execIn[2] = {ea, eb};
    Qnn_Tensor_t execOut[1] = {ec};

    stage("GRAPH_EXECUTE_BEGIN");
    auto t0 = std::chrono::steady_clock::now();
    rc = ftbl.graphExecute(graph, execIn, 2, execOut, 1, nullptr, nullptr);
    auto t1 = std::chrono::steady_clock::now();
    auto us = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();

    info("GRAPH_EXECUTE_RETURN rc=" + std::to_string((int)rc) +
         " elapsed_us=" + std::to_string(us));

    if (rc != QNN_SUCCESS) {
        error("GRAPH_EXECUTE_FAIL rc=" + std::to_string((int)rc));
        return false;
    }

    for (uint32_t i = 0; i < n; ++i) {
        if (cv[i] != av[i] + bv[i]) {
            error("OUTPUT_VERIFY_FAIL index=" + std::to_string(i) +
                  " got=" + std::to_string(cv[i]) +
                  " expected=" + std::to_string(av[i] + bv[i]));
            return false;
        }
    }

    stage("SMOKE_SUCCESS");
    info("HTP_EXECUTE_SUCCESS backendId=" +
         std::to_string((unsigned)g.iface->backendId) +
         " elapsed_us=" + std::to_string(us));
    info("OUTPUT_VERIFY_OK");
    return true;
}

void shutdownRuntime() {
    stage("SHUTDOWN_BEGIN");
    if (!g.iface) {
        info("SHUTDOWN_NO_RUNTIME");
        return;
    }

    const auto& ftbl = g.iface->QNN_INTERFACE_VER_NAME;
    if (ftbl.contextFree && g.context) ftbl.contextFree(g.context, nullptr);
    if (ftbl.deviceFree && g.device) ftbl.deviceFree(g.device);
    if (ftbl.backendFree && g.backend) ftbl.backendFree(g.backend);
    if (ftbl.logFree && g.logger) ftbl.logFree(g.logger);

    g.context = nullptr;
    g.device = nullptr;
    g.backend = nullptr;
    g.logger = nullptr;
    g.ready = false;

    if (g.qnn) dlclose(g.qnn);
    g.qnn = nullptr;
    g.iface = nullptr;
    info("SHUTDOWN_DONE");
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_bslsjdk_mcjavanpu_NpuRuntime_nativeInit(JNIEnv*, jclass) {
    return initQnn() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_bslsjdk_mcjavanpu_NpuRuntime_nativeGetDeviceInfo(JNIEnv* env, jclass) {
    if (!g.error.empty()) return env->NewStringUTF(g.error.c_str());
    return env->NewStringUTF(g.infoText.empty() ? "unknown" : g.infoText.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_bslsjdk_mcjavanpu_NpuRuntime_nativeGetDiagnostics(JNIEnv* env, jclass) {
    return env->NewStringUTF(gTrace.empty() ? "NO_NATIVE_TRACE" : gTrace.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_bslsjdk_mcjavanpu_NpuRuntime_nativeGetLogPath(JNIEnv* env, jclass) {
    return env->NewStringUTF(kLogPath);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_bslsjdk_mcjavanpu_NpuRuntime_nativeTest(JNIEnv*, jclass) {
    return smokeTest() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_bslsjdk_mcjavanpu_NpuRuntime_nativeBenchmark(JNIEnv* env, jclass) {
    return env->NewStringUTF("stage0 graphExecute smoke test ready");
}

extern "C" JNIEXPORT void JNICALL
Java_bslsjdk_mcjavanpu_NpuRuntime_nativeShutdown(JNIEnv*, jclass) {
    shutdownRuntime();
}
