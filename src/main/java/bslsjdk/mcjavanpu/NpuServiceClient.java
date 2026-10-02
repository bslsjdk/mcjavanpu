package bslsjdk.mcjavanpu;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.*;

public final class NpuServiceClient {
    private static final String SOCKET = "mcnpu_v1";
    private NpuServiceClient() {}

    public static String request(String command) {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.write(command);
            out.write("\n");
            out.flush();
            String line = in.readLine();
            return line == null ? "ERR EMPTY_REPLY" : line;
        } catch (Throwable t) {
            return "ERR SERVICE_UNAVAILABLE " + t.getClass().getSimpleName();
        }
    }

    public static boolean isAvailable() {
        return request("PING").startsWith("PONG MCNPU/");
    }

    public static String status() {
        return request("STATUS");
    }

    public static String smoke() {
        return request("SMOKE");
    }
}
