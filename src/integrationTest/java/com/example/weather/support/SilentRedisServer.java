package com.example.weather.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-process TCP server on {@code 127.0.0.1} (random port, one per JVM) that accepts
 * connections and never reads or writes: a Redis that hangs (AC-21). No container involved.
 */
public final class SilentRedisServer {

    private static final SilentRedisServer INSTANCE = start();

    private final ServerSocket server;
    private final List<Socket> held = new CopyOnWriteArrayList<>();

    private SilentRedisServer(ServerSocket server) {
        this.server = server;
    }

    public static SilentRedisServer instance() {
        return INSTANCE;
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Number of connections accepted so far (held open, never answered). */
    public int accepted() {
        return held.size();
    }

    private static SilentRedisServer start() {
        try {
            ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            SilentRedisServer silent = new SilentRedisServer(socket);
            Thread acceptor = new Thread(silent::acceptForever, "silent-redis");
            acceptor.setDaemon(true);
            acceptor.start();
            Runtime.getRuntime().addShutdownHook(new Thread(silent::close, "silent-redis-stop"));
            return silent;
        } catch (IOException e) {
            throw new UncheckedIOException("could not start SilentRedisServer", e);
        }
    }

    private void acceptForever() {
        while (!server.isClosed()) {
            try {
                held.add(server.accept());
            } catch (IOException e) {
                return;
            }
        }
    }

    private void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // shutting down
        }
        for (Socket s : held) {
            try {
                s.close();
            } catch (IOException ignored) {
                // shutting down
            }
        }
    }
}
