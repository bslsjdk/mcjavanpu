#include <jni.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>

#define IPC_PORT 38761
#define REPLY_MAX 16384

static int g_fd = -1;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static jstring make_error(JNIEnv *env, const char *prefix, int err) {
    char buf[256];
    snprintf(buf, sizeof(buf), "ERR %s errno=%d %s", prefix, err, strerror(err));
    return (*env)->NewStringUTF(env, buf);
}

static int connect_socket(void) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(IPC_PORT);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        int e = errno;
        close(fd);
        errno = e;
        return -1;
    }
    return fd;
}

static void close_locked(void) {
    if (g_fd >= 0) {
        shutdown(g_fd, SHUT_RDWR);
        close(g_fd);
        g_fd = -1;
    }
}

static int ensure_connected_locked(void) {
    if (g_fd >= 0) return 0;
    g_fd = connect_socket();
    return g_fd >= 0 ? 0 : -1;
}

static int send_all(int fd, const char *buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = send(fd, buf + off, len - off, MSG_NOSIGNAL);
        if (n > 0) {
            off += (size_t)n;
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        return -1;
    }
    return 0;
}

static int recv_line(int fd, char *buf, size_t cap) {
    size_t used = 0;
    while (used + 1 < cap) {
        char c;
        ssize_t n = recv(fd, &c, 1, 0);
        if (n == 1) {
            if (c == '\n') {
                buf[used] = '\0';
                return 0;
            }
            buf[used++] = c;
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        return -1;
    }
    buf[cap - 1] = '\0';
    errno = EMSGSIZE;
    return -1;
}

JNIEXPORT jstring JNICALL
Java_bslsjdk_mcjavanpu_NpuServiceClient_nativeRequest(JNIEnv *env, jclass cls, jstring command) {
    (void)cls;
    if (command == NULL) return (*env)->NewStringUTF(env, "ERR NULL_COMMAND");

    const char *cmd = (*env)->GetStringUTFChars(env, command, NULL);
    if (cmd == NULL) return (*env)->NewStringUTF(env, "ERR UTF_COMMAND");

    size_t cmd_len = strlen(cmd);
    if (cmd_len == 0) {
        (*env)->ReleaseStringUTFChars(env, command, cmd);
        return (*env)->NewStringUTF(env, "ERR EMPTY_COMMAND");
    }
    if (cmd_len > 8192) {
        (*env)->ReleaseStringUTFChars(env, command, cmd);
        return (*env)->NewStringUTF(env, "ERR COMMAND_TOO_LONG");
    }

    char *wire = (char *)malloc(cmd_len + 1);
    if (!wire) {
        (*env)->ReleaseStringUTFChars(env, command, cmd);
        return (*env)->NewStringUTF(env, "ERR OOM");
    }
    memcpy(wire, cmd, cmd_len);
    wire[cmd_len] = '\n';

    char reply[REPLY_MAX];
    int last_err = 0;

    pthread_mutex_lock(&g_lock);
    for (int attempt = 0; attempt < 2; ++attempt) {
        if (ensure_connected_locked() < 0) {
            last_err = errno;
            continue;
        }

        if (send_all(g_fd, wire, cmd_len + 1) == 0 &&
            recv_line(g_fd, reply, sizeof(reply)) == 0) {
            pthread_mutex_unlock(&g_lock);
            free(wire);
            (*env)->ReleaseStringUTFChars(env, command, cmd);
            return (*env)->NewStringUTF(env, reply[0] ? reply : "ERR EMPTY_REPLY");
        }

        last_err = errno;
        close_locked();
    }
    pthread_mutex_unlock(&g_lock);

    free(wire);
    (*env)->ReleaseStringUTFChars(env, command, cmd);
    return make_error(env, "IPC", last_err ? last_err : EIO);
}

JNIEXPORT void JNICALL
Java_bslsjdk_mcjavanpu_NpuServiceClient_nativeClose(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    pthread_mutex_lock(&g_lock);
    close_locked();
    pthread_mutex_unlock(&g_lock);
}
