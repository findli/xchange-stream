package info.bitrich.xchangestream.service.netty;

import info.bitrich.xchangestream.service.exception.NotConnectedException;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketClientExtensionHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class NettyStreamingService<T> {
    private static final Logger LOG = LoggerFactory.getLogger(NettyStreamingService.class);

    private final URI uri;
    private Channel webSocketChannel;
    protected Map<String, ObservableEmitter<T>> channels = new ConcurrentHashMap<>();

    public NettyStreamingService(String apiUrl) {
        try {
            uri = new URI(apiUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Error parsing URI " + apiUrl, e);
        }
    }

    public Completable connect() {
        return connect(1L, TimeUnit.MINUTES);
    }

    public Completable connect(long heartbeatInterval, TimeUnit heartbeatTimeUnit) {
        return Completable.create(completable -> {
            try {
                LOG.info("Connecting to {}", uri.toString());
                String scheme = uri.getScheme() == null ? "ws" : uri.getScheme();

                String host = uri.getHost();
                if (host == null) {
                    throw new IllegalArgumentException("Host cannot be null.");
                }

                final int port;
                if (uri.getPort() == -1) {
                    if ("ws".equalsIgnoreCase(scheme)) {
                        port = 80;
                    } else if ("wss".equalsIgnoreCase(scheme)) {
                        port = 443;
                    } else {
                        port = -1;
                    }
                } else {
                    port = uri.getPort();
                }

                if (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
                    throw new IllegalArgumentException("Only WS(S) is supported.");
                }

                final boolean ssl = "wss".equalsIgnoreCase(scheme);
                final SslContext sslCtx;
                if (ssl) {
                    sslCtx = SslContextBuilder.forClient().build();
                } else {
                    sslCtx = null;
                }

                EventLoopGroup group = new NioEventLoopGroup();

                final WebSocketClientHandler handler = getWebSocketClientHandler(WebSocketClientHandshakerFactory.newHandshaker(
                  uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders()),
                  this::massegeHandler);

                Bootstrap b = new Bootstrap();
                b.group(group)
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ChannelPipeline p = ch.pipeline();
                                if (sslCtx != null) {
                                    p.addLast(sslCtx.newHandler(ch.alloc(), host, port));
                                }
                                p.addLast(
                                        new HttpClientCodec(),
                                        new HttpObjectAggregator(8192),
                                        getWebSocketClientExtensionHandler(),
                                        handler);
                            }
                        });

                b.connect(uri.getHost(), port).addListener((ChannelFuture future) -> {
                    webSocketChannel = future.channel();
                    handler.handshakeFuture().addListener(future1 -> {
                        completable.onComplete();
                        // Schedule Heartbeat
                        webSocketChannel.eventLoop().scheduleAtFixedRate(() -> sendMessage("ping"),
                                heartbeatInterval, heartbeatInterval, heartbeatTimeUnit);

                    });
                });
            } catch (Exception throwable) {
                completable.onError(throwable);
            }
        });
    }

    public Completable onDisconnect() {
        return Completable.create(completable -> {
            LOG.info("register Completable onDisconnect");
            webSocketChannel.closeFuture().addListener(future -> {
                LOG.error("onDisconnect has been invoked == webSocketChannel has been closed.");
                completable.onComplete();
            });
        });
    }

    public Completable disconnect() {
        return Completable.create(completable -> {
            CloseWebSocketFrame closeFrame = new CloseWebSocketFrame();
            webSocketChannel.writeAndFlush(closeFrame).addListener(future -> {
                channels = new ConcurrentHashMap<>();
                completable.onComplete();
            });
        });
    }

    protected abstract String getChannelNameFromMessage(T message) throws IOException;

    public abstract String getSubscribeMessage(String channelName, String... parameters) throws IOException;

    public abstract String getUnsubscribeMessage(String channelName) throws IOException;

    /**
     * Handler that receives incoming messages.
     *
     * @param message Content of the message from the server.
     */
    public abstract void massegeHandler(String message);

    public void sendMessage(String message) {
        LOG.debug("Sending message: {}", message);

        if (webSocketChannel == null || !webSocketChannel.isOpen()) {
            final String errorMsg = "WebSocket is not open! Call connect first.";
            LOG.warn(errorMsg);
            throw new NotConnectedException();
        }

        if (!webSocketChannel.isWritable()) {
            final String errorMsg = "Cannot send data to WebSocket as it is not writable.";
            LOG.warn(errorMsg);
            throw new NotConnectedException();
        }

        WebSocketFrame frame = new TextWebSocketFrame(message);
        webSocketChannel.writeAndFlush(frame);
    }

    public Observable<T> subscribeChannel(String channelName, String... parameters) {
        LOG.info("Subscribing to channel {}", channelName);

        return Observable.<T>create(e -> {
            if (webSocketChannel == null || !webSocketChannel.isOpen()) {
                e.onError(new NotConnectedException());
            }

            channels.put(channelName, e);
            try {
                sendMessage(getSubscribeMessage(channelName, parameters));
            } catch (IOException throwable) {
                e.onError(throwable);
            }
        }).doOnDispose(() -> {
            sendMessage(getUnsubscribeMessage(channelName));
            channels.remove(channelName);
        });
    }

    public Observable<T> sendAndSubscribe(String message, String channelName) {
        LOG.info("Subscribing to channel {}", channelName);

        return Observable.<T>create(e -> {
            if (webSocketChannel == null || !webSocketChannel.isOpen()) {
                e.onError(new NotConnectedException());
            }

            channels.put(channelName, e);

            sendMessage(message);

        }).doOnDispose(() -> {
            sendMessage(getUnsubscribeMessage(channelName));
            channels.remove(channelName);
        });
    }

    public Observable<T> subscribeBatchChannels(String externalChannelName,
                                                List<String> internalChannelNames,
                                                String... parameters) {
        LOG.info("Subscribing to channel {}", Arrays.toString(new List[]{internalChannelNames}));

        return Observable.<T>create(e -> {
            if (webSocketChannel == null || !webSocketChannel.isOpen()) {
                e.onError(new NotConnectedException());
            }
            for (String channelName : internalChannelNames) {
                channels.put(channelName, e);
            }
            try {
                sendMessage(getSubscribeMessage(externalChannelName, parameters));
            } catch (IOException throwable) {
                e.onError(throwable);
            }
        }).doOnDispose(() -> {
            LOG.warn("Unsubscribing from channel {}", externalChannelName);
            sendMessage(getUnsubscribeMessage(externalChannelName));
            for (String channelName : internalChannelNames) {
                channels.remove(channelName);
            }
        });
    }

    protected String getChannel(T message) {
        String channel;
        try {
            channel = getChannelNameFromMessage(message);
        } catch (IOException e) {
            LOG.error("Cannot parse channel from message: {}", message);
            return "";
        }
        return channel;
    }

    protected void handleMessage(T message) {
        String channel = getChannel(message);
        handleChannelMessage(channel, message);
    }

    protected void handleError(T message, Throwable t) {
        String channel = getChannel(message);
        handleChannelError(channel, t);
    }


    protected void handleChannelMessage(String channel, T message) {
        ObservableEmitter<T> emitter = channels.get(channel);
        if (emitter == null) {
            LOG.debug("No subscriber for channel {}.", channel);
            return;
        }

        emitter.onNext(message);
    }

    protected void handleChannelError(String channel, Throwable t) {
        ObservableEmitter<T> emitter = channels.get(channel);
        if (emitter == null) {
            LOG.warn("No subscriber for channel {}.", channel);
            return;
        }

        emitter.onError(t);
    }

    protected WebSocketClientExtensionHandler getWebSocketClientExtensionHandler() {
        return WebSocketClientCompressionHandler.INSTANCE;
    }

    protected WebSocketClientHandler getWebSocketClientHandler(WebSocketClientHandshaker handshaker,
                                                               WebSocketClientHandler.WebSocketMessageHandler handler){
        return new WebSocketClientHandler(handshaker, handler);
    }
}
