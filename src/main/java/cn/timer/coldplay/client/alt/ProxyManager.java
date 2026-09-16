package cn.timer.coldplay.client.alt;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import net.lenni0451.commons.httpclient.HttpClient;
import net.lenni0451.commons.httpclient.HttpResponse;
import net.lenni0451.commons.httpclient.exceptions.HttpRequestException;
import net.lenni0451.commons.httpclient.executor.ExecutorType;
import net.lenni0451.commons.httpclient.proxy.ProxyType;
import net.lenni0451.commons.httpclient.requests.impl.GetRequest;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.raphimc.minecraftauth.MinecraftAuth;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class ProxyManager {
    private static final int TIMEOUT_MILLIS = 10_000;
    private volatile ProxySnapshot current = ProxySnapshot.EMPTY;

    ProxySnapshot snapshot() {
        return current;
    }

    void update(ProxySnapshot snapshot) {
        ProxySnapshot value = snapshot == null ? ProxySnapshot.EMPTY : snapshot;
        current = new ProxySnapshot(false, value.serverEnabled, ProxyProtocol.SOCKS5,
                value.address, value.port, value.username, value.password);
    }

    HttpClient authClient(ProxySnapshot snapshot) {
        HttpClient client = MinecraftAuth.createHttpClient("ColdPlay/1.0");
        client.setConnectTimeout(TIMEOUT_MILLIS).setReadTimeout(TIMEOUT_MILLIS);
        if (!snapshot.authEnabled) {
            return client;
        }

        client.setExecutor(value -> ExecutorType.REACTOR_NETTY.makeExecutor(value));
        ProxyEndpoint endpoint = snapshot.endpoint();
        ProxyType type = snapshot.protocol == ProxyProtocol.HTTP ? ProxyType.HTTP : ProxyType.SOCKS5;
        if (snapshot.username.isEmpty()) {
            client.setProxyHandler(new net.lenni0451.commons.httpclient.proxy.ProxyHandler(
                    type, endpoint.host(), endpoint.port()));
        } else {
            client.setProxyHandler(new net.lenni0451.commons.httpclient.proxy.ProxyHandler(
                    type, endpoint.host(), endpoint.port(), snapshot.username, snapshot.password));
        }
        return client;
    }

    ProxyHandler serverHandler() {
        ProxySnapshot snapshot = current;
        if (!snapshot.serverEnabled) {
            return null;
        }
        return serverHandler(snapshot);
    }

    ActionResult test(ProxySnapshot snapshot, String savedServer) {
        if (!snapshot.enabled()) {
            return ActionResult.failure(AltError.MALFORMED_PROXY);
        }
        try {
            snapshot.endpoint();
            if (snapshot.authEnabled) {
                HttpResponse response = authClient(snapshot).execute(
                        new GetRequest("https://api.minecraftservices.com/minecraft/profile"));
                int status = response.getStatusCode();
                if (status == 407) {
                    return ActionResult.failure(AltError.PROXY_AUTH_FAILED);
                }
                if (status != 401 && status != 403) {
                    if (status == 408 || status == 429 || status >= 500) {
                        return ActionResult.failure(AltError.SERVICES_UNAVAILABLE);
                    }
                    return ActionResult.failure(AltError.PROXY_CONNECTION_FAILED);
                }
            }
            if (snapshot.serverEnabled) {
                if (savedServer == null || savedServer.isBlank()) {
                    testEndpoint(snapshot.endpoint());
                    return ActionResult.success(snapshot.authEnabled
                            ? "Auth route works; server proxy endpoint is reachable."
                            : "Server proxy endpoint is reachable; save a server to test a full tunnel.");
                }
                testTunnel(snapshot, ServerAddress.parseString(savedServer));
            }
            return ActionResult.success("SOCKS5 server proxy is working.");
        } catch (IllegalArgumentException exception) {
            AltManager.logFailure("ALT_PROXY_CONFIG", 0, exception);
            return ActionResult.failure(AltError.MALFORMED_PROXY);
        } catch (HttpRequestException exception) {
            int status = exception.getResponse() == null ? 0 : exception.getResponse().getStatusCode();
            AltError error = status == 407 ? AltError.PROXY_AUTH_FAILED : AltError.PROXY_CONNECTION_FAILED;
            AltManager.logFailure("ALT_PROXY_TEST", status, exception);
            return ActionResult.failure(error);
        } catch (Exception exception) {
            AltError error = hasCause(exception, ProxyConnectException.class)
                    && isProxyAuthFailure(exception) ? AltError.PROXY_AUTH_FAILED
                    : AltError.PROXY_CONNECTION_FAILED;
            AltManager.logFailure("ALT_PROXY_TEST", 0, exception);
            return ActionResult.failure(error);
        }
    }

    private ProxyHandler serverHandler(ProxySnapshot snapshot) {
        ProxyEndpoint endpoint = snapshot.endpoint();
        SocketAddress address = new InetSocketAddress(endpoint.host(), endpoint.port());
        ProxyHandler handler = snapshot.username.isEmpty()
                ? new Socks5ProxyHandler(address)
                : new Socks5ProxyHandler(address, snapshot.username, snapshot.password);
        handler.setConnectTimeoutMillis(TIMEOUT_MILLIS);
        return handler;
    }

    private static void testEndpoint(ProxyEndpoint endpoint) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), TIMEOUT_MILLIS);
        }
    }

    private void testTunnel(ProxySnapshot snapshot, ServerAddress target) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Channel[] channel = new Channel[1];
        try {
            ProxyHandler proxyHandler = serverHandler(snapshot);
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, TIMEOUT_MILLIS)
                    .handler(new ChannelInitializer<>() {
                        @Override
                        protected void initChannel(Channel channel) {
                            channel.pipeline().addFirst("coldplay_proxy_test", proxyHandler);
                        }
                    });
            channel[0] = bootstrap.connect(InetSocketAddress.createUnresolved(target.getHost(), target.getPort()))
                    .sync().channel();
            proxyHandler.connectFuture().await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (!proxyHandler.connectFuture().isSuccess()) {
                Throwable cause = proxyHandler.connectFuture().cause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw new IOException("proxy tunnel failed");
            }
        } finally {
            if (channel[0] != null) {
                channel[0].close().awaitUninterruptibly(TIMEOUT_MILLIS);
            }
            group.shutdownGracefully().awaitUninterruptibly(TIMEOUT_MILLIS);
        }
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProxyAuthFailure(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof ProxyConnectException && current.getMessage() != null) {
                String message = current.getMessage().toLowerCase(Locale.ROOT);
                if (message.contains("407") || message.contains("auth")) {
                    return true;
                }
            }
        }
        return false;
    }
}
