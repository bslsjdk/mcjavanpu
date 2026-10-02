#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

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
struct Runtime {
    void* qnn = nullptr;
    const QnnInterface_t* iface = nullptr;
    Qnn_LogHandle_t logger = nullptr;
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    Qnn_ContextHandle_t context = nullptr;
    std::string info;
    std::string error;
    bool ready = false;
};
Runtime g;

using GetProvidersFn = Qnn_ErrorHandle_t (*)(const QnnInterface_t ***, uint32_t *);

bool fail(const char* stage, Qnn_ErrorHandle_t rc) {
    char buf[256];
    std::snprintf(buf, sizeof(buf), "%s rc=%d", stage, (int)rc);
    g.error = buf;
    LOGE("%s", g.error.c_str());
    return false;
}

bool loadRuntime() {
    const char* paths[] = {
        "/odm/lib64/aiframe/libQnnHtp.so",
        "/odm/lib64/libQnnHtp.so"
    };

    for (const char* path : paths) {
        g.qnn = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (g.qnn) {
            LOGI("QNN loaded: %s", path);
            return true;
        }
        const char* err = dlerror();
        LOGE("QNN load failed: %s (%s)", path, err ? err : "?");
    }
    g.error = "dlopen(libQnnHtp.so) failed";
    return false;
}

bool initQnn() {
    g.error.clear();
    g.info.clear();

    if (!loadRuntime()) return false;

    auto getProviders =
        reinterpret_cast<GetProvidersFn>(dlsym(g.qnn, "QnnInterface_getProviders"));
    if (!getProviders) {
        g.error = "dlsym QnnInterface_getProviders failed";
        LOGE("%s", g.error.c_str());
        return false;
    }

    const QnnInterface_t** providers = nullptr;
    uint32_t count = 0;
    Qnn_ErrorHandle_t rc = getProviders(&providers, &count);
    if (rc != QNN_SUCCESS || !providers || count == 0) {
        return fail("getProviders", rc);
    }

    for (uint32_t i = 0; i < count; ++i) {
        if (providers[i] && providers[i]->backendId == BACKEND_ID_HTP) {
            g.iface = providers[i];
            break;
        }
    }
    if (!g.iface) {
        g.iface = providers[0];
        LOGI("HTP provider id=%d not found; using provider[0] id=%d",
             BACKEND_ID_HTP, (int)g.iface->backendId);
    } else {
        LOGI("selected HTP provider id=%d", (int)g.iface->backendId);
    }

    const auto& ftbl = g.iface->QNN_INTERFACE_VER_NAME;

    if (ftbl.logCreate) {
        rc = ftbl.logCreate(nullptr, QNN_LOG_LEVEL_ERROR, &g.logger);
        if (rc != QNN_SUCCESS) {
            LOGI("logCreate rc=%d; continuing without logger", (int)rc);
            g.logger = nullptr;
        }
    }

    rc = ftbl.backendCreate(g.logger, nullptr, &g.backend);
    if (rc != QNN_SUCCESS || !g.backend) {
        return fail("backendCreate", rc);
    }
    LOGI("backendCreate OK");

    rc = ftbl.deviceCreate(g.logger, nullptr, &g.device);
    if (rc != QNN_SUCCESS || !g.device) {
        return fail("deviceCreate", rc);
    }
    LOGI("deviceCreate OK");

    rc = ftbl.contextCreate(g.backend, g.device, nullptr, &g.context);
    if (rc != QNN_SUCCESS || !g.context) {
        return fail("contextCreate", rc);
    }
    LOGI("contextCreate OK");

    char buf[256];
    std::snprintf(buf, sizeof(buf),
                  "backendId=%u providers=%u api=%u.%u",
                  (unsigned)g.iface->backendId,
                  (unsigned)count,
                  (unsigned)g.iface->apiVersion.coreApiVersion.major,
                  (unsigned)g.iface->apiVersion.coreApiVersion.minor);
    g.info = buf;
    g.ready = true;

    LOGI("QNN ready: %s", g.info.c_str());
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
    if (!g.ready) return false;

    const auto& ftbl = g.iface->QNN_INTERFACE_VER_NAME;
    const uint32_t n = 16;
    uint32_t dims[1] = {n};

    Qnn_GraphHandle_t graph = nullptr;
    Qnn_ErrorHandle_t rc = ftbl.graphCreate(g.context, "mcjavanpu_smoke", nullptr, &graph);
    if (rc != QNN_SUCCESS || !graph) {
        LOGE("graphCreate rc=%d", (int)rc);
        return false;
    }

    Qnn_Tensor_t a = makeTensor("a", QNN_TENSOR_TYPE_APP_WRITE, QNN_DATATYPE_FLOAT_32, dims, 1);
    Qnn_Tensor_t b = makeTensor("b", QNN_TENSOR_TYPE_APP_WRITE, QNN_DATATYPE_FLOAT_32, dims, 1);
    Qnn_Tensor_t c = makeTensor("c", QNN_TENSOR_TYPE_APP_READ, QNN_DATATYPE_FLOAT_32, dims, 1);

    rc = ftbl.tensorCreateGraphTensor(graph, &a);
    if (rc == QNN_SUCCESS) rc = ftbl.tensorCreateGraphTensor(graph, &b);
    if (rc == QNN_SUCCESS) rc = ftbl.tensorCreateGraphTensor(graph, &c);
    if (rc != QNN_SUCCESS) {
        LOGE("tensorCreateGraphTensor rc=%d", (int)rc);
        return false;
    }

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
        LOGE("graphAddNode rc=%d", (int)rc);
        return false;
    }

    rc = ftbl.graphFinalize(graph, nullptr, nullptr);
    if (rc != QNN_SUCCESS) {
        LOGE("graphFinalize rc=%d", (int)rc);
        return false;
    }

    std::vector<float> av(n), bv(n), cv(n, -999.0f);
    for (uint32_t i = 0; i < n; ++i) {
        av[i] = static_cast<float>(i);
        bv[i] = 2.0f;
    }

    Qnn_Tensor_t ea = a;
    Qnn_Tensor_t eb = b;
    Qnn_Tensor_t ec = c;
    ea.v1.clientBuf.data = av.data();
    ea.v1.clientBuf.dataSize = sizeof(float) * n;
    eb.v1.clientBuf.data = bv.data();
    eb.v1.clientBuf.dataSize = sizeof(float) * n;
    ec.v1.clientBuf.data = cv.data();
    ec.v1.clientBuf.dataSize = sizeof(float) * n;

    Qnn_Tensor_t execIn[2] = {ea, eb};
    Qnn_Tensor_t execOut[1] = {ec};

    rc = ftbl.graphExecute(graph, execIn, 2, execOut, 1, nullptr, nullptr);
    if (rc != QNN_SUCCESS) {
        LOGE("graphExecute rc=%d", (int)rc);
        return false;
    }

    for (uint32_t i = 0; i < n; ++i) {
        if (cv[i] != av[i] + bv[i]) {
            LOGE("smoke mismatch i=%u got=%f expected=%f", i, cv[i], av[i] + bv[i]);
            return false;
        }
    }

    LOGI("smoke test PASS");
    return true;
}

void shutdownRuntime() {
    if (!g.iface) return;
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
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_bslsjdk_mcjavanpu_NpuRuntime_nativeInit(JNIEnv*, jclass) {
    return initQnn() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_bslsjdk_mcjavanpu_NpuRuntime_nativeGetDeviceInfo(JNIEnv* env, jclass) {
    if (!g.error.empty()) return env->NewStringUTF(g.error.c_str());
    return env->NewStringUTF(g.info.empty() ? "unknown" : g.info.c_str());
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
