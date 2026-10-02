#include <jni.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stddef.h>

static const char *SOCKET_NAME = "mcnpu_ipc_v1";

static jstring make_error(JNIEnv *env, const char *prefix) {
    char buf[256];
    snprintf(buf, sizeof(buf), "ERR %s errno=%d %s", prefix, errno, strerror(errno));
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_bslsjdk_mcjavanpu_NpuServiceClient_nativeRequest(JNIEnv *env, jclass cls, jstring command) {
    (void)cls;
    if (command == NULL) return (*env)->NewStringUTF(env, "ERR NULL_COMMAND");

    const char *cmd = (*env)->GetStringUTFChars(env, command, NULL);
    if (cmd == NULL) return (*env)->NewStringUTF(env, "ERR UTF_COMMAND");

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        (*env)->ReleaseStringUTFChars(env, command, cmd);
        return make_error(env, "SOCKET");
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;

    size_t name_len = strlen(SOCKET_NAME);
    if (name_len + 1 > sizeof(addr.sun_path)) {
        close(fd);
        (*env)->ReleaseStringUTFChars(env, command, cmd);
        return (*env)->NewStringUTF(env, "ERR SOCKET_NAME_TOO_LONG");
    }

    memcpy(addr.sun_path + 1, SOCKET_NAME, name_len);
    socklen_t addr_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_len);

    if (connect(fd, (struct sockaddr *)&addr, addr_len) < 0) {
        jstring result = make_error(env, "CONNECT");
        close(fd);
        (*env)->ReleaseStringUTFChars(env, command, cmd);
        return result;
    }

    size_t cmd_len = strlen(cmd);
    if (cmd_len > 8192) {
        close(fd);
        (*env)->ReleaseStringUTFChars(env, command, cmd);
        return (*env)->NewStringUTF(env, "ERR COMMAND_TOO_LONG");
    }

    char *wire = (char *)malloc(cmd_len + 1);
    if (!wire) {
        close(fd);
        (*env)->ReleaseStringUTFChars(env, command, cmd);
        return (*env)->NewStringUTF(env, "ERR OOM");
    }

    memcpy(wire, cmd, cmd_len);
    wire[cmd_len] = '\n';

    size_t sent = 0;
    while (sent < cmd_len + 1) {
        ssize_t n = send(fd, wire + sent, cmd_len + 1 - sent, 0);
        if (n <= 0) {
            free(wire);
            jstring result = make_error(env, "SEND");
            close(fd);
            (*env)->ReleaseStringUTFChars(env, command, cmd);
            return result;
        }
        sent += (size_t)n;
    }
    free(wire);

    char reply[16384];
    size_t used = 0;
    while (used + 1 < sizeof(reply)) {
        ssize_t n = recv(fd, reply + used, sizeof(reply) - used - 1, 0);
        if (n <= 0) break;
        used += (size_t)n;
        if (memchr(reply, '\n', used) != NULL) break;
    }

    reply[used] = '\0';
    close(fd);
    (*env)->ReleaseStringUTFChars(env, command, cmd);

    char *newline = strchr(reply, '\n');
    if (newline) *newline = '\0';
    if (reply[0] == '\0') return (*env)->NewStringUTF(env, "ERR EMPTY_REPLY");
    return (*env)->NewStringUTF(env, reply);
}
