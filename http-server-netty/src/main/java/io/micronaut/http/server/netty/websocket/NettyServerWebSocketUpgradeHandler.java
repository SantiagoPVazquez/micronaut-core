/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.server.netty.websocket;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.execution.ExecutionFlow;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.netty.NettyHttpHeaders;
import io.micronaut.http.netty.channel.ChannelPipelineCustomizer;
import io.micronaut.http.netty.websocket.WebSocketSessionRepository;
import io.micronaut.http.server.RequestLifecycle;
import io.micronaut.http.server.RouteExecutor;
import io.micronaut.http.server.netty.NettyEmbeddedServices;
import io.micronaut.http.server.netty.NettyHttpRequest;
import io.micronaut.web.router.RouteMatch;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;
import io.micronaut.websocket.context.WebSocketBean;
import io.micronaut.websocket.context.WebSocketBeanRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AsciiString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handles WebSocket upgrade requests.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
public class NettyServerWebSocketUpgradeHandler extends SimpleChannelInboundHandler<NettyHttpRequest<?>> {

    public static final String ID = ChannelPipelineCustomizer.HANDLER_WEBSOCKET_UPGRADE;
    public static final String SCHEME_WEBSOCKET = "ws://";
    public static final String SCHEME_SECURE_WEBSOCKET = "wss://";

    public static final String COMPRESSION_HANDLER = "WebSocketServerCompressionHandler";

    private static final Logger LOG = LoggerFactory.getLogger(NettyServerWebSocketUpgradeHandler.class);
    private static final AsciiString WEB_SOCKET_HEADER_VALUE = AsciiString.cached("websocket");

    private final Router router;
    private final WebSocketBeanRegistry webSocketBeanRegistry;
    private final WebSocketSessionRepository webSocketSessionRepository;
    private final RouteExecutor routeExecutor;
    private final NettyEmbeddedServices nettyEmbeddedServices;
    private WebSocketServerHandshaker handshaker;
    private boolean cancelUpgrade = false;

    /**
     * Default constructor.
     *
     * @param embeddedServices The embedded server services
     * @param webSocketSessionRepository The websocket session repository
     */
    public NettyServerWebSocketUpgradeHandler(NettyEmbeddedServices embeddedServices,
                                              WebSocketSessionRepository webSocketSessionRepository) {
        this.router = embeddedServices.getRouter();
        this.webSocketBeanRegistry = WebSocketBeanRegistry.forServer(embeddedServices.getApplicationContext());
        this.webSocketSessionRepository = webSocketSessionRepository;
        this.routeExecutor = embeddedServices.getRouteExecutor();
        this.nettyEmbeddedServices = embeddedServices;
    }

    @Override
    public boolean acceptInboundMessage(Object msg) {
        return msg instanceof NettyHttpRequest && isWebSocketUpgrade((NettyHttpRequest<?>) msg);
    }

    private boolean isWebSocketUpgrade(@NonNull NettyHttpRequest<?> request) {
        HttpHeaders headers = request.getNativeRequest().headers();
        if (headers.containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true)) {
            return headers.containsValue(HttpHeaderNames.UPGRADE, WEB_SOCKET_HEADER_VALUE, true);
        }
        return false;
    }

    @Override
    protected final void channelRead0(ChannelHandlerContext ctx, NettyHttpRequest<?> msg) {
        ServerRequestContext.set(msg);

        Optional<UriRouteMatch<Object, Object>> optionalRoute = router.find(HttpMethod.GET, msg.getPath(), msg)
            .filter(rm -> rm.isAnnotationPresent(OnMessage.class) || rm.isAnnotationPresent(OnOpen.class))
            .findFirst();

        WebsocketRequestLifecycle requestLifecycle = new WebsocketRequestLifecycle(routeExecutor, msg, optionalRoute.orElse(null));
        ExecutionFlow<MutableHttpResponse<?>> responseFlow = ExecutionFlow.async(ctx.channel().eventLoop(), requestLifecycle::handle);
        responseFlow.onComplete((response, throwable) -> {
            if (response != null) {
                writeResponse(ctx, msg, requestLifecycle.shouldProceedNormally, response);
            }
        });
    }

    private void writeResponse(ChannelHandlerContext ctx, NettyHttpRequest<?> msg, boolean shouldProceedNormally, MutableHttpResponse<?> actualResponse) {
        if (cancelUpgrade) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Cancelling websocket upgrade, handler was removed while request was processing");
            }
            return;
        }

        if (shouldProceedNormally) {
            UriRouteMatch<Object, Object> routeMatch = actualResponse.getAttribute(HttpAttributes.ROUTE_MATCH, UriRouteMatch.class)
                .orElseThrow(() -> new IllegalStateException("Route match is required!"));
            //Adding new handler to the existing pipeline to handle WebSocket Messages
            WebSocketBean<?> webSocketBean = webSocketBeanRegistry.getWebSocket(routeMatch.getTarget().getClass());

            handleHandshake(ctx, msg, webSocketBean, actualResponse);

            ChannelPipeline pipeline = ctx.pipeline();

            try {
                // re-configure the pipeline
                NettyServerWebSocketHandler webSocketHandler = new NettyServerWebSocketHandler(
                    nettyEmbeddedServices,
                    webSocketSessionRepository,
                    handshaker,
                    webSocketBean,
                    msg,
                    routeMatch,
                    ctx,
                    routeExecutor.getCoroutineHelper().orElse(null));
                pipeline.addBefore(ctx.name(), NettyServerWebSocketHandler.ID, webSocketHandler);

                pipeline.remove(ChannelPipelineCustomizer.HANDLER_HTTP_STREAM);
                pipeline.remove(NettyServerWebSocketUpgradeHandler.this);
                ChannelHandler accessLoggerHandler = pipeline.get(ChannelPipelineCustomizer.HANDLER_ACCESS_LOGGER);
                if (accessLoggerHandler != null) {
                    pipeline.remove(accessLoggerHandler);
                }

            } catch (Throwable e) {
                if (LOG.isErrorEnabled()) {
                    LOG.error("Error opening WebSocket: " + e.getMessage(), e);
                }
                ctx.writeAndFlush(new CloseWebSocketFrame(CloseReason.INTERNAL_ERROR.getCode(), CloseReason.INTERNAL_ERROR.getReason()));
            }
        } else {
            ctx.writeAndFlush(actualResponse);
        }
    }

    /**
     * Do the handshaking for WebSocket request.
     *
     * @param ctx           The channel handler context
     * @param req           The request
     * @param webSocketBean The web socket bean
     * @param response      The response
     * @return The channel future
     **/
    protected ChannelFuture handleHandshake(ChannelHandlerContext ctx, NettyHttpRequest req, WebSocketBean<?> webSocketBean, MutableHttpResponse<?> response) {
        int maxFramePayloadLength = webSocketBean.messageMethod()
                .map(m -> m.intValue(OnMessage.class, "maxPayloadLength")
                .orElse(65536)).orElse(65536);
        String subprotocols = webSocketBean.getBeanDefinition().stringValue(ServerWebSocket.class, "subprotocols")
                                           .filter(s -> !StringUtils.isEmpty(s))
                                           .orElse(null);
        WebSocketServerHandshakerFactory wsFactory =
                new WebSocketServerHandshakerFactory(
                        getWebSocketURL(ctx, req),
                        subprotocols,
                        true,
                        maxFramePayloadLength
                );
        handshaker = wsFactory.newHandshaker(req.getNativeRequest());
        MutableHttpHeaders headers = response.getHeaders();
        io.netty.handler.codec.http.HttpHeaders nettyHeaders;
        if (headers instanceof NettyHttpHeaders) {
            nettyHeaders = ((NettyHttpHeaders) headers).getNettyHeaders();
        } else {
            nettyHeaders = new DefaultHttpHeaders();
            for (Map.Entry<String, List<String>> entry : headers) {
                nettyHeaders.add(entry.getKey(), entry.getValue());
            }
        }
        Channel channel = ctx.channel();
        if (handshaker == null) {
            return WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(channel);
        } else {
            return handshaker.handshake(
                    channel,
                    req.getNativeRequest(),
                    nettyHeaders,
                    channel.newPromise()
            );
        }
    }

    /**
     * Obtains the web socket URL.
     *
     * @param ctx The context
     * @param req The request
     * @return The socket URL
     */
    protected String getWebSocketURL(ChannelHandlerContext ctx, HttpRequest req) {
        boolean isSecure = ctx.pipeline().get(SslHandler.class) != null;
        return (isSecure ? SCHEME_SECURE_WEBSOCKET : SCHEME_WEBSOCKET) + req.getHeaders().get(HttpHeaderNames.HOST) + req.getUri();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        super.handlerRemoved(ctx);
        cancelUpgrade = true;
    }

    @Override
    public void channelInactive(@NonNull ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        cancelUpgrade = true;
    }

    private static final class WebsocketRequestLifecycle extends RequestLifecycle {
        @Nullable
        final RouteMatch<?> route;

        boolean shouldProceedNormally;

        WebsocketRequestLifecycle(RouteExecutor routeExecutor, HttpRequest<?> request, @Nullable RouteMatch<?> route) {
            super(routeExecutor, request);
            this.route = route;
        }

        ExecutionFlow<MutableHttpResponse<?>> handle() {
            MutableHttpResponse<?> proceed = HttpResponse.ok();

            if (route != null) {
                request().setAttribute(HttpAttributes.ROUTE_MATCH, route);
                request().setAttribute(HttpAttributes.ROUTE_INFO, route);
                proceed.setAttribute(HttpAttributes.ROUTE_MATCH, route);
                proceed.setAttribute(HttpAttributes.ROUTE_INFO, route);
            }

            ExecutionFlow<MutableHttpResponse<?>> response;
            if (route != null) {
                response = runWithFilters(() -> ExecutionFlow.just(proceed));
            } else {
                response = onError(new HttpStatusException(HttpStatus.NOT_FOUND, "WebSocket Not Found"))
                    .putInContext(ServerRequestContext.KEY, request());
            }
            return response.map(r -> {
                if (r == proceed) {
                    shouldProceedNormally = true;
                }
                return r;
            });
        }
    }
}
