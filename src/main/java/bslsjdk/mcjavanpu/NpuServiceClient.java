package bslsjdk.mcjavanpu;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import java.io.*;
import java.lang.reflect.Method;

public final class NpuServiceClient {
    private static final String SOCKET_NAME = "mcnpu_ipc_v1";
    private static LocalSocket socket;
    private static BufferedWriter out;
    private static BufferedReader in;

    private NpuServiceClient() {}

    private static void connect() throws IOException {
        close();
        socket = new LocalSocket();
        socket.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
        socket.setSoTimeout(3000);
        verifyPeer();
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8));
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void verifyPeer() throws IOException {
        try {
            android.net.Credentials peer = socket.getPeerCredentials();
            int actualUid = peer.getUid();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getMethod("currentApplication");
            Object app = currentApplication.invoke(null);
            if (!(app instanceof android.app.Application)) {
                throw new IOException("cannot resolve Android application context");
            }
            android.content.pm.ApplicationInfo info =
                    ((android.app.Application) app).getPackageManager()
                            .getApplicationInfo("bslsjdk.mcnpu", 0);
            if (actualUid != info.uid) {
                throw new IOException("unexpected MCNPU peer uid=" + actualUid + " expected=" + info.uid);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("MCNPU peer verification failed: " + e.getClass().getSimpleName(), e);
        }
    }

    public static synchronized String request(String command) {
        try {
            if (socket == null || !socket.isConnected()) connect();
            out.write(command);
            out.write("\n");
            out.flush();
            String line = in.readLine();
            if (line == null) throw new EOFException("service closed IPC");
            if (command.equals("PING") && !line.startsWith("PONG MCNPU/")) {
                close();
                return "ERR INVALID_SERVICE_REPLY";
            }
            return line;
        } catch (IOException first) {
            close();
            try {
                connect();
                out.write(command);
                out.write("\n");
                out.flush();
                String line = in.readLine();
                if (line == null) return "ERR EMPTY_REPLY";
                if (command.equals("PING") && !line.startsWith("PONG MCNPU/"))
                    return "ERR INVALID_SERVICE_REPLY";
                return line;
            } catch (IOException second) {
                close();
                return "ERR SERVICE_UNAVAILABLE " + second.getClass().getSimpleName();
            } catch (RuntimeException second) {
                close();
                return "ERR SERVICE_RUNTIME " + second.getClass().getSimpleName();
            }
        } catch (RuntimeException first) {
            close();
            return "ERR SERVICE_RUNTIME " + first.getClass().getSimpleName();
        }
    }

    public static synchronized void close() {
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        socket = null;
        out = null;
        in = null;
    }

    public static boolean isAvailable() { return request("PING").startsWith("PONG MCNPU/"); }
    public static String status() { return request("STATUS"); }
    public static String smoke() { return request("SMOKE"); }
    public static String capabilities() { return request("CAPABILITIES"); }

    public static String add(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length || a.length > 1024)
            return "ERR ADD_SIZE";
        StringBuilder sa = new StringBuilder(), sb = new StringBuilder();
        for (int i=0;i<a.length;i++) {
            if (!Float.isFinite(a[i]) || !Float.isFinite(b[i])) return "ERR ADD_NON_FINITE";
            if (i > 0) { sa.append(','); sb.append(','); }
            sa.append(Float.toString(a[i]));
            sb.append(Float.toString(b[i]));
        }
        return request("EXEC_ADD " + sa + "|" + sb);
    }
}
