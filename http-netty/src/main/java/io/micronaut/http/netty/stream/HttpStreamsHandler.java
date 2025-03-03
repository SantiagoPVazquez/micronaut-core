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
package io.micronaut.http.netty.stream;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.netty.reactive.HandlerPublisher;
import io.micronaut.http.netty.reactive.HandlerSubscriber;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for Http Streams handlers.
 *
 * @param <In>  The input Http Message
 * @param <Out> The output Http Message
 *
 * @author jroper
 * @author Graeme Rocher
 * @since 1.0
 */
@Internal
abstract class HttpStreamsHandler<In extends HttpMessage, Out extends HttpMessage> extends ChannelDuplexHandler {

    public static final String HANDLER_BODY_PUBLISHER = "http-streams-codec-body-publisher";
    private static final Logger LOG = LoggerFactory.getLogger(HttpStreamsHandler.class);

    private final Queue<Outgoing<Out>> outgoing = new LinkedList<>();
    private final Class<In> inClass;
    private final Class<Out> outClass;

    /**
     * The incoming message that is currently being streamed out to a subscriber.
     * <p>
     * This is tracked so that if its subscriber cancels, we can go into a mode where we ignore the rest of the body.
     * Since subscribers may cancel as many times as they like, including well after they've received all their content,
     * we need to track what the current message that's being streamed out is so that we can ignore it if it's not
     * currently being streamed out.
     */
    private In currentlyStreamedMessage;

    /**
     * Ignore the remaining reads for the incoming message.
     * <p>
     * This is used in conjunction with currentlyStreamedMessage, as well as in situations where we have received the
     * full body, but still might be expecting a last http content message.
     */
    private boolean ignoreBodyRead;

    /**
     * Whether a LastHttpContent message needs to be written once the incoming publisher completes.
     * <p>
     * Since the publisher may itself publish a LastHttpContent message, we need to track this fact, because if it
     * doesn't, then we need to write one ourselves.
     */
    private boolean sendLastHttpContent;

    /**
     * Whether a {@link StreamedHttpMessage} is currently being written, and further messages should be held back until
     * complete. Used for HTTP pipelining.
     */
    private boolean outgoingInFlight;

    /**
     * @param inClass  The in class
     * @param outClass The out class
     */
    HttpStreamsHandler(Class<In> inClass, Class<Out> outClass) {
        this.inClass = inClass;
        this.outClass = outClass;
    }

    /**
     * Whether the given incoming message has a body.
     *
     * @param in The incoming message
     * @return Whether the incoming message has body
     */
    protected abstract boolean hasBody(In in);

    /**
     * Create an empty incoming message. This must be of type FullHttpMessage, and is invoked when we've determined
     * that an incoming message can't have a body, so we send it on as a FullHttpMessage.
     *
     * @param in The incoming message
     * @return An empty incoming message
     */
    protected abstract In createEmptyMessage(In in);

    /**
     * Create a streamed incoming message with the given stream.
     *
     * @param in     The incoming message
     * @param stream The publisher for the Http Content
     * @return An streamed incoming message
     */
    protected abstract In createStreamedMessage(In in, Publisher<? extends HttpContent> stream);

    /**
     * Invoked when an incoming message is first received.
     * <p>
     * Overridden by sub classes for state tracking.
     *
     * @param ctx The channel handler context
     */
    protected void receivedInMessage(ChannelHandlerContext ctx) {
    }

    /**
     * Invoked when an incoming message is fully consumed.
     * <p>
     * Overridden by sub classes for state tracking.
     *
     * @param ctx The channel handler context
     */
    protected void consumedInMessage(ChannelHandlerContext ctx) {
    }

    /**
     * Invoked when an outgoing message is first received.
     * <p>
     * Overridden by sub classes for state tracking.
     *
     * @param ctx The channel handler context
     */
    protected void receivedOutMessage(ChannelHandlerContext ctx) {
    }

    /**
     * Invoked when an outgoing message is fully sent.
     * <p>
     * Overridden by sub classes for state tracking.
     *
     * @param ctx The channel handler context
     */
    protected void sentOutMessage(ChannelHandlerContext ctx) {
    }

    /**
     * Subscribe the given subscriber to the given streamed message.
     * <p>
     * Provided so that the client subclass can intercept this to hold off sending the body of an expect 100 continue
     * request.
     *
     * @param msg        The streamed Http message
     * @param subscriber The subscriber for the Http Content
     */
    protected void subscribeSubscriberToStream(StreamedHttpMessage msg, Subscriber<HttpContent> subscriber) {
        msg.subscribe(subscriber);
    }

    /**
     * Invoked every time a read of the incoming body is requested by the subscriber.
     * <p>
     * Provided so that the server subclass can intercept this to send a 100 continue response.
     *
     * @param ctx The channel handler context
     */
    protected void bodyRequested(ChannelHandlerContext ctx) {
    }

    /**
     * @return Whether this is the client stream handler.
     */
    protected abstract boolean isClient();

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {

        if (isValidInMessage(msg)) {

            receivedInMessage(ctx);
            final In inMsg = inClass.cast(msg);

            if (inMsg instanceof FullHttpMessage) {
                FullHttpMessage fullMessage = (FullHttpMessage) inMsg;
                if (!(fullMessage instanceof FullHttpRequest) || fullMessage.content().readableBytes() == 0) {
                    // Forward as is
                    ctx.fireChannelRead(inMsg);
                } else {
                    // create streamed message with just the data from the request
                    ctx.fireChannelRead(createStreamedMessage(inMsg, Flux.just(fullMessage)));
                }
                consumedInMessage(ctx);
            } else if (!hasBody(inMsg)) {

                // Wrap in empty message
                ctx.fireChannelRead(createEmptyMessage(inMsg));
                consumedInMessage(ctx);

                // There will be a LastHttpContent message coming after this, ignore it
                ignoreBodyRead = true;

            } else {

                currentlyStreamedMessage = inMsg;
                // It has a body, stream it
                HandlerPublisher<? extends HttpContent> publisher = new HandlerPublisher<HttpContent>(ctx.executor(), HttpContent.class) {
                    @Override
                    protected void cancelled() {
                        if (ctx.executor().inEventLoop()) {
                            handleCancelled(ctx, inMsg);
                        } else {
                            ctx.executor().execute(() -> handleCancelled(ctx, inMsg));
                        }
                    }

                    @Override
                    protected void requestDemand() {
                        bodyRequested(ctx);
                        super.requestDemand();
                    }
                };

                ctx.channel().pipeline().addAfter(ctx.name(), HANDLER_BODY_PUBLISHER, publisher);
                ctx.fireChannelRead(createStreamedMessage(inMsg, publisher));
            }
        } else if (msg instanceof HttpContent) {
            handleReadHttpContent(ctx, (HttpContent) msg);
        }
    }

    private void handleCancelled(ChannelHandlerContext ctx, In msg) {
        if (currentlyStreamedMessage == msg) {
            ignoreBodyRead = true;
            // Need to do a read in case the subscriber ignored a read completed.
            if (LOG.isTraceEnabled()) {
                LOG.trace("Calling ctx.read() for cancelled subscription");
            }
            ctx.read();
            if (isClient()) {
                ctx.fireChannelWritabilityChanged();
            }
        }
    }

    private void handleReadHttpContent(ChannelHandlerContext ctx, HttpContent content) {
        if (!ignoreBodyRead) {
            ChannelHandler bodyPublisher = ctx.pipeline().get(HANDLER_BODY_PUBLISHER);
            if (bodyPublisher != null) {
                ctx.fireChannelRead(content);
                if (content instanceof LastHttpContent) {
                    currentlyStreamedMessage = null;
                    removeHandlerIfActive(ctx, HANDLER_BODY_PUBLISHER);
                    consumedInMessage(ctx);
                }
            } else {
                ReferenceCountUtil.release(content, content.refCnt());
            }
        } else {
            ReferenceCountUtil.release(content, content.refCnt());
            if (content instanceof LastHttpContent) {
                ignoreBodyRead = false;
                if (currentlyStreamedMessage != null) {
                    removeHandlerIfActive(ctx, HANDLER_BODY_PUBLISHER);
                }
                currentlyStreamedMessage = null;
            }
            ctx.read();
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        if (ignoreBodyRead) {
            ctx.read();
        } else {
            ctx.fireChannelReadComplete();
        }
    }

    @Override
    public void write(final ChannelHandlerContext ctx, Object msg, final ChannelPromise promise) throws Exception {
        if (isValidOutMessage(msg)) {

            receivedOutMessage(ctx);
            outgoing.add(new Outgoing<>((Out) msg, promise));
            proceedWriteOutgoing(ctx);

        } else if (msg instanceof LastHttpContent) {

            sendLastHttpContent = false;
            ctx.write(msg, promise);
        } else {

            ctx.write(msg, promise);
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        proceedWriteOutgoing(ctx);
    }

    private void proceedWriteOutgoing(ChannelHandlerContext ctx) {
        while (!outgoingInFlight && ctx.channel().isWritable() && !outgoing.isEmpty()) {
            Outgoing<Out> out = outgoing.remove();
            unbufferedWrite(ctx, out.message, out.promise);
        }
    }

    /**
     * @param ctx The channel handler context
     * @param message The message
     * @param promise The promise
     */
    protected void unbufferedWrite(final ChannelHandlerContext ctx, final Out message, ChannelPromise promise) {

        if (message instanceof FullHttpMessage) {
            // Forward as is
            ctx.writeAndFlush(message, promise);
            sentOutMessage(ctx);
        } else if (message instanceof StreamedHttpMessage) {
            outgoingInFlight = true;

            StreamedHttpMessage streamed = (StreamedHttpMessage) message;
            HandlerSubscriber<HttpContent> subscriber = new HandlerSubscriber<HttpContent>(ctx.executor()) {
                AtomicBoolean messageWritten = new AtomicBoolean();

                @Override
                public void onNext(HttpContent httpContent) {
                    if (messageWritten.compareAndSet(false, true)) {
                        ChannelPromise messageWritePromise = ctx.newPromise();
                        //if oncomplete gets called before the message is written the promise
                        //set to lastWriteFuture shouldn't complete until the first content is written
                        lastWriteFuture = messageWritePromise;
                        ctx.writeAndFlush(message).addListener(f -> onNext(httpContent, messageWritePromise));
                    } else {
                        super.onNext(httpContent);
                    }
                }

                @Override
                protected void error(Throwable error) {
                    try {
                        if (LOG.isErrorEnabled()) {
                            LOG.error("Error occurred writing stream response: " + error.getMessage(), error);
                        }
                        HttpResponseStatus responseStatus;
                        if (error instanceof HttpStatusException) {
                            responseStatus = HttpResponseStatus.valueOf(((HttpStatusException) error).getStatus().getCode(), error.getMessage());
                        } else {
                            responseStatus = HttpResponseStatus.INTERNAL_SERVER_ERROR;
                        }
                        ctx.writeAndFlush(new DefaultHttpResponse(HttpVersion.HTTP_1_1, responseStatus))
                                .addListener(ChannelFutureListener.CLOSE);
                    } finally {
                        ctx.read();
                    }
                }

                @Override
                protected void complete() {
                    if (messageWritten.compareAndSet(false, true)) {
                        ctx.writeAndFlush(message).addListener(future -> doOnComplete());
                    } else {
                        doOnComplete();
                    }
                }

                private void doOnComplete() {
                    if (ctx.executor().inEventLoop()) {
                        completeBody(ctx, promise);
                    } else {
                        ctx.executor().execute(() -> completeBody(ctx, promise));
                    }
                }
            };

            sendLastHttpContent = true;

            ctx.pipeline().addAfter(ctx.name(), ctx.name() + "-body-subscriber", subscriber);
            subscribeSubscriberToStream(streamed, subscriber);
        }

    }

    private void completeBody(final ChannelHandlerContext ctx, ChannelPromise promise) {
        removeHandlerIfActive(ctx, ctx.name() + "-body-subscriber");

        if (sendLastHttpContent) {
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT, promise).addListener((f) -> {
                sentOutMessage(ctx);
                ctx.read();
                outgoingInFlight = false;
                proceedWriteOutgoing(ctx);
            });
        } else {
            promise.setSuccess();
            sentOutMessage(ctx);
            ctx.read();
            outgoingInFlight = false;
            proceedWriteOutgoing(ctx);
        }
    }

    /**
     * Most operations we want to do even if the channel is not active, because if it's not, then we want to encounter
     * the error that occurs when that operation happens and so that it can be passed up to the user. However, removing
     * handlers should only be done if the channel is active, because the error that is encountered when they aren't
     * makes no sense to the user (NoSuchElementException).
     */
    private void removeHandlerIfActive(ChannelHandlerContext ctx, String name) {
        if (ctx.channel().isActive()) {
            ChannelPipeline pipeline = ctx.pipeline();
            ChannelHandler handler = pipeline.get(name);
            if (handler != null) {
                pipeline.remove(name);
            }
        }
    }

    /**
     * @param msg The message
     * @return True if the handler should write the message
     */
    protected boolean isValidOutMessage(Object msg) {
        return outClass.isInstance(msg);
    }

    /**
     * @param msg The message
     * @return True if the handler should read the message
     */
    protected boolean isValidInMessage(Object msg) {
        return inClass.isInstance(msg);
    }

    /**
     * The outgoing class.
     *
     * @param <O> The message type
     */
    static class Outgoing<O extends HttpMessage> {
        final O message;
        final ChannelPromise promise;

        /**
         * @param message The output message
         * @param promise The channel promise
         */
        Outgoing(O message, ChannelPromise promise) {
            this.message = message;
            this.promise = promise;
        }
    }

}
