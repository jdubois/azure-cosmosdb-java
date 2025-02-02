/*
 * The MIT License (MIT)
 * Copyright (c) 2018 Microsoft Corporation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd;

import com.google.common.base.Strings;
import com.microsoft.azure.cosmosdb.BridgeInternal;
import com.microsoft.azure.cosmosdb.DocumentClientException;
import com.microsoft.azure.cosmosdb.Error;
import com.microsoft.azure.cosmosdb.internal.InternalServerErrorException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.ConflictException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.ForbiddenException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.GoneException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.LockedException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.MethodNotAllowedException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.PartitionKeyRangeGoneException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.PreconditionFailedException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.RequestEntityTooLargeException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.RequestRateTooLargeException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.RequestTimeoutException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.RetryWithException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.ServiceUnavailableException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.StoreResponse;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.UnauthorizedException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd.RntbdConstants.RntbdResponseHeader;
import com.microsoft.azure.cosmosdb.rx.internal.BadRequestException;
import com.microsoft.azure.cosmosdb.rx.internal.InvalidPartitionException;
import com.microsoft.azure.cosmosdb.rx.internal.NotFoundException;
import com.microsoft.azure.cosmosdb.rx.internal.PartitionIsMigratingException;
import com.microsoft.azure.cosmosdb.rx.internal.PartitionKeyRangeIsSplittingException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.CoalescingBufferQueue;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.Timeout;
import io.netty.util.concurrent.EventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;
import static com.microsoft.azure.cosmosdb.internal.HttpConstants.StatusCodes;
import static com.microsoft.azure.cosmosdb.internal.HttpConstants.SubStatusCodes;
import static com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd.RntbdReporter.reportIssue;
import static com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd.RntbdReporter.reportIssueUnless;

public final class RntbdRequestManager implements ChannelHandler, ChannelInboundHandler, ChannelOutboundHandler {

    // region Fields

    private static final Logger logger = LoggerFactory.getLogger(RntbdRequestManager.class);

    private final CompletableFuture<RntbdContext> contextFuture = new CompletableFuture<>();
    private final CompletableFuture<RntbdContextRequest> contextRequestFuture = new CompletableFuture<>();
    private final ConcurrentHashMap<Long, RntbdRequestRecord> pendingRequests;

    private boolean closingExceptionally = false;
    private ChannelHandlerContext context;
    private RntbdRequestRecord pendingRequest;
    private CoalescingBufferQueue pendingWrites;

    // endregion

    public RntbdRequestManager(int capacity) {
        checkState(capacity > 0);
        this.pendingRequests = new ConcurrentHashMap<>(capacity);
    }

    // region ChannelHandler methods

    /**
     * Gets called after the {@link ChannelHandler} was added to the actual context and it's ready to handle events.
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext context) {
        this.traceOperation(context, "handlerAdded");
    }

    /**
     * Gets called after the {@link ChannelHandler} was removed from the actual context and it doesn't handle events
     * anymore.
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     */
    @Override
    public void handlerRemoved(final ChannelHandlerContext context) {
        this.traceOperation(context, "handlerRemoved");
    }

    // endregion

    // region ChannelInboundHandler methods

    /**
     * The {@link Channel} of the {@link ChannelHandlerContext} is now active
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     */
    @Override
    public void channelActive(final ChannelHandlerContext context) {
        this.traceOperation(this.context, "channelActive");
        context.fireChannelActive();
    }

    /**
     * Completes all pending requests exceptionally when a channel reaches the end of its lifetime
     * <p>
     * This method will only be called after the channel is closed.
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     */
    @Override
    public void channelInactive(final ChannelHandlerContext context) {
        this.traceOperation(this.context, "channelInactive");
        context.fireChannelInactive();
    }

    @Override
    public void channelRead(final ChannelHandlerContext context, final Object message) {

        this.traceOperation(context, "channelRead");

        if (message instanceof RntbdResponse) {
            try {
                this.messageReceived(context, (RntbdResponse)message);
            } catch (Throwable throwable) {
                reportIssue(logger, context, "{} ", message, throwable);
                this.exceptionCaught(context, throwable);
            } finally {
                ReferenceCountUtil.release(message);
            }
            this.traceOperation(context, "channelReadComplete");
            return;
        }

        final String reason = Strings.lenientFormat("expected message of type %s, not %s: %s", RntbdResponse.class, message.getClass(), message);
        final IllegalStateException error = new IllegalStateException(reason);
        reportIssue(logger, context, "", error);

        this.exceptionCaught(context, error);
    }

    /**
     * Invoked when the last message read by the current read operation has been consumed
     * <p>
     * If {@link ChannelOption#AUTO_READ} is off, no further attempt to read an inbound data from the current
     * {@link Channel} will be made until {@link ChannelHandlerContext#read} is called. This leaves time
     * for outbound messages to be written.
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     */
    @Override
    public void channelReadComplete(final ChannelHandlerContext context) {
        this.traceOperation(context, "channelReadComplete");
        context.fireChannelReadComplete();
    }

    /**
     * Constructs a {@link CoalescingBufferQueue} for buffering encoded requests until we have an {@link RntbdRequest}
     * <p>
     * This method then calls {@link ChannelHandlerContext#fireChannelRegistered()} to forward to the next
     * {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     * <p>
     * Sub-classes may override this method to change behavior.
     *
     * @param context the {@link ChannelHandlerContext} for which the bind operation is made
     */
    @Override
    public void channelRegistered(final ChannelHandlerContext context) {

        this.traceOperation(context, "channelRegistered");

        if (!(this.context == null && this.pendingWrites == null)) {
            throw new IllegalStateException();
        }

        this.pendingWrites = new CoalescingBufferQueue(context.channel());
        this.context = context;

        context.fireChannelRegistered();
    }

    /**
     * The {@link Channel} of the {@link ChannelHandlerContext} was unregistered from its {@link EventLoop}
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     */
    @Override
    public void channelUnregistered(final ChannelHandlerContext context) {

        this.traceOperation(context, "channelUnregistered");

        if (this.context == null || this.pendingWrites == null) {
            throw new IllegalStateException();
        }

        this.completeAllPendingRequestsExceptionally(context, ClosedWithPendingRequestsException.INSTANCE);
        this.pendingWrites = null;
        this.context = null;

        context.fireChannelUnregistered();
    }

    /**
     * Gets called once the writable state of a {@link Channel} changed. You can check the state with
     * {@link Channel#isWritable()}.
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     */
    @Override
    public void channelWritabilityChanged(final ChannelHandlerContext context) {
        this.traceOperation(context, "channelWritabilityChanged");
        context.fireChannelWritabilityChanged();
    }

    /**
     * Processes {@link ChannelHandlerContext#fireExceptionCaught(Throwable)} in the {@link ChannelPipeline}
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     * @param cause   Exception caught
     */
    @Override
    @SuppressWarnings("deprecation")
    public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {

        // TODO: DANOBLE: replace RntbdRequestManager.exceptionCaught with read/write listeners
        //  Notes:
        //    ChannelInboundHandler.exceptionCaught is deprecated and--today, prior to deprecation--only catches read--
        //    i.e., inbound--exceptions.
        //    Replacements:
        //    * read listener: unclear as there is no obvious replacement
        //    * write listener: implemented by RntbdTransportClient.DefaultEndpoint.doWrite
        //  Links:
        //  https://msdata.visualstudio.com/CosmosDB/_workitems/edit/373213

        this.traceOperation(context, "exceptionCaught", cause);

        if (!this.closingExceptionally) {

            reportIssueUnless(cause != ClosedWithPendingRequestsException.INSTANCE, logger, context,
                "expected an exception other than ", ClosedWithPendingRequestsException.INSTANCE);

            this.completeAllPendingRequestsExceptionally(context, cause);
            context.pipeline().flush().close();
        }
    }

    /**
     * Processes inbound events triggered by channel handlers in the {@link RntbdClientChannelHandler} pipeline
     * <p>
     * All but inbound request management events are ignored.
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     * @param event   An object representing a user event
     */
    @Override
    public void userEventTriggered(final ChannelHandlerContext context, final Object event) {

        this.traceOperation(context, "userEventTriggered", event);

        if (event instanceof RntbdContext) {
            try {
                this.completeRntbdContextFuture(context, (RntbdContext)event);
            } catch (Throwable error) {
                reportIssue(logger, context, "{}: ", event, error);
                this.exceptionCaught(context, error);
            }
            return;
        }

        context.fireUserEventTriggered(event);
    }

    // endregion

    // region ChannelOutboundHandler methods

    /**
     * Called once a bind operation is made.
     *
     * @param context      the {@link ChannelHandlerContext} for which the bind operation is made
     * @param localAddress the {@link SocketAddress} to which it should bound
     * @param promise      the {@link ChannelPromise} to notify once the operation completes
     */
    @Override
    public void bind(final ChannelHandlerContext context, final SocketAddress localAddress, final ChannelPromise promise) {
        this.traceOperation(context, "bind");
        context.bind(localAddress, promise);
    }

    /**
     * Called once a close operation is made.
     *
     * @param context the {@link ChannelHandlerContext} for which the close operation is made
     * @param promise the {@link ChannelPromise} to notify once the operation completes
     */
    @Override
    public void close(final ChannelHandlerContext context, final ChannelPromise promise) {

        this.traceOperation(context, "close");

        this.completeAllPendingRequestsExceptionally(context, ClosedWithPendingRequestsException.INSTANCE);
        final SslHandler sslHandler = context.pipeline().get(SslHandler.class);

        if (sslHandler != null) {
            // Netty 4.1.36.Final: SslHandler.closeOutbound must be called before closing the pipeline
            // This ensures that all SSL engine and ByteBuf resources are released
            // This is something that does not occur in the call to ChannelPipeline.close that follows
            sslHandler.closeOutbound();
        }

        context.close(promise);
    }

    /**
     * Called once a connect operation is made.
     *
     * @param context       the {@link ChannelHandlerContext} for which the connect operation is made
     * @param remoteAddress the {@link SocketAddress} to which it should connect
     * @param localAddress  the {@link SocketAddress} which is used as source on connect
     * @param promise       the {@link ChannelPromise} to notify once the operation completes
     */
    @Override
    public void connect(
        final ChannelHandlerContext context, final SocketAddress remoteAddress, final SocketAddress localAddress,
        final ChannelPromise promise
    ) {
        this.traceOperation(context, "connect");
        context.connect(remoteAddress, localAddress, promise);
    }

    /**
     * Called once a deregister operation is made from the current registered {@link EventLoop}.
     *
     * @param context the {@link ChannelHandlerContext} for which the close operation is made
     * @param promise the {@link ChannelPromise} to notify once the operation completes
     */
    @Override
    public void deregister(final ChannelHandlerContext context, final ChannelPromise promise) {
        this.traceOperation(context, "deregister");
        context.deregister(promise);
    }

    /**
     * Called once a disconnect operation is made.
     *
     * @param context the {@link ChannelHandlerContext} for which the disconnect operation is made
     * @param promise the {@link ChannelPromise} to notify once the operation completes
     */
    @Override
    public void disconnect(final ChannelHandlerContext context, final ChannelPromise promise) {
        this.traceOperation(context, "disconnect");
        context.disconnect(promise);
    }

    /**
     * Called once a flush operation is made
     * <p>
     * The flush operation will try to flush out all previous written messages that are pending.
     *
     * @param context the {@link ChannelHandlerContext} for which the flush operation is made
     */
    @Override
    public void flush(final ChannelHandlerContext context) {
        this.traceOperation(context, "flush");
        context.flush();
    }

    /**
     * Intercepts {@link ChannelHandlerContext#read}
     *
     * @param context the {@link ChannelHandlerContext} for which the read operation is made
     */
    @Override
    public void read(final ChannelHandlerContext context) {
        this.traceOperation(context, "read");
        context.read();
    }

    /**
     * Called once a write operation is made
     * <p>
     * The write operation will send messages through the {@link ChannelPipeline} which are then ready to be flushed
     * to the actual {@link Channel}. This will occur when {@link Channel#flush} is called.
     *
     * @param context the {@link ChannelHandlerContext} for which the write operation is made
     * @param message the message to write
     * @param promise the {@link ChannelPromise} to notify once the operation completes
     */
    @Override
    public void write(final ChannelHandlerContext context, final Object message, final ChannelPromise promise) {

        // TODO: DANOBLE: Ensure that all write errors are reported with a root cause of type EncoderException

        this.traceOperation(context, "write", message);

        if (message instanceof RntbdRequestRecord) {
            context.write(this.addPendingRequestRecord(context, (RntbdRequestRecord)message), promise);
        } else {
            final String reason = Strings.lenientFormat("Expected message of type %s, not %s", RntbdRequestArgs.class, message.getClass());
            this.exceptionCaught(context, new IllegalStateException(reason));
        }
    }

    // endregion

    // region Private and package private methods

    CompletableFuture<RntbdContextRequest> getRntbdContextRequestFuture() {
        return this.contextRequestFuture;
    }

    boolean hasRntbdContext() {
        return this.contextFuture.getNow(null) != null;
    }

    boolean isServiceable(int capacity) {
        final int pendingRequestLimit = this.hasRntbdContext() ? capacity : Math.min(capacity, 30);
        return this.pendingRequests.size() < pendingRequestLimit;
    }

    void pendWrite(final ByteBuf out, final ChannelPromise promise) {
        this.pendingWrites.add(out, promise);
    }

    private RntbdRequestArgs addPendingRequestRecord(final ChannelHandlerContext context, final RntbdRequestRecord record) {

        this.pendingRequest = this.pendingRequests.compute(record.getTransportRequestId(), (id, current) -> {

            reportIssueUnless(current == null, logger, context, "id: {}, current: {}, request: {}", id, current, record);

            final Timeout pendingRequestTimeout = record.newTimeout(timeout -> {

                // We don't wish to complete on the timeout thread, but rather on a thread doled out by our executor

                EventExecutor executor = context.executor();

                if (executor.inEventLoop()) {
                    record.expire();
                } else {
                    executor.next().execute(record::expire);
                }
            });

            record.whenComplete((response, error) -> {
                this.pendingRequests.remove(id);
                pendingRequestTimeout.cancel();
            });

            return record;
        });

        return this.pendingRequest.getArgs();
    }

    private Optional<RntbdContext> getRntbdContext() {
        return Optional.of(this.contextFuture.getNow(null));
    }

    private void completeAllPendingRequestsExceptionally(final ChannelHandlerContext context, final Throwable throwable) {

        if (this.closingExceptionally) {

            reportIssueUnless(throwable == ClosedWithPendingRequestsException.INSTANCE, logger, context,
                "throwable: ", throwable);

            reportIssueUnless(this.pendingRequests.isEmpty() && this.pendingWrites.isEmpty(), logger, context,
                "pendingRequests: {}, pendingWrites: {}", this.pendingRequests.isEmpty(),
                this.pendingWrites.isEmpty());

            return;
        }

        this.closingExceptionally = true;

        if (!this.pendingWrites.isEmpty()) {
            this.pendingWrites.releaseAndFailAll(context, ClosedWithPendingRequestsException.INSTANCE);
        }

        if (!this.pendingRequests.isEmpty()) {

            if (!this.contextRequestFuture.isDone()) {
                this.contextRequestFuture.completeExceptionally(throwable);
            }

            if (!this.contextFuture.isDone()) {
                this.contextFuture.completeExceptionally(throwable);
            }

            final int count = this.pendingRequests.size();
            Exception contextRequestException = null;
            String phrase = null;

            if (this.contextRequestFuture.isCompletedExceptionally()) {

                try {
                    this.contextRequestFuture.get();
                } catch (final CancellationException error) {
                    phrase = "RNTBD context request write cancelled";
                    contextRequestException = error;
                } catch (final Exception error) {
                    phrase = "RNTBD context request write failed";
                    contextRequestException = error;
                } catch (final Throwable error) {
                    phrase = "RNTBD context request write failed";
                    contextRequestException = new ChannelException(error);
                }

            } else if (this.contextFuture.isCompletedExceptionally()) {

                try {
                    this.contextFuture.get();
                } catch (final CancellationException error) {
                    phrase = "RNTBD context request read cancelled";
                    contextRequestException = error;
                } catch (final Exception error) {
                    phrase = "RNTBD context request read failed";
                    contextRequestException = error;
                } catch (final Throwable error) {
                    phrase = "RNTBD context request read failed";
                    contextRequestException = new ChannelException(error);
                }

            } else {

                phrase = "closed exceptionally";
            }

            final String message = Strings.lenientFormat("%s %s with %s pending requests", context, phrase, count);
            final Exception cause;

            if (throwable == ClosedWithPendingRequestsException.INSTANCE) {

                cause = contextRequestException == null
                    ? ClosedWithPendingRequestsException.INSTANCE
                    : contextRequestException;

            } else {

                cause = throwable instanceof Exception
                    ? (Exception)throwable
                    : new ChannelException(throwable);
            }

            for (RntbdRequestRecord record : this.pendingRequests.values()) {

                final Map<String, String> requestHeaders = record.getArgs().getServiceRequest().getHeaders();
                final String requestUri = record.getArgs().getPhysicalAddress().toString();

                final GoneException error = new GoneException(message, cause, (Map<String, String>)null, requestUri);
                BridgeInternal.setRequestHeaders(error, requestHeaders);

                record.completeExceptionally(error);
            }
        }
    }

    private void completeRntbdContextFuture(final ChannelHandlerContext context, final RntbdContext value) {

        this.contextFuture.complete(value);

        final RntbdContextNegotiator negotiator = context.channel().pipeline().get(RntbdContextNegotiator.class);
        negotiator.removeInboundHandler();
        negotiator.removeOutboundHandler();

        if (!this.pendingWrites.isEmpty()) {
            this.pendingWrites.writeAndRemoveAll(context);
        }
    }

    /**
     * This method is called for each incoming message of type {@link StoreResponse} to complete a request
     *
     * @param context  {@link ChannelHandlerContext} encode to which this {@link RntbdRequestManager} belongs
     * @param response the message encode handle
     */
    private void messageReceived(final ChannelHandlerContext context, final RntbdResponse response) {

        final Long transportRequestId = response.getTransportRequestId();

        if (transportRequestId == null) {
            reportIssue(logger, context, "{} ignored because there is no transport request identifier, response");
            return;
        }

        final RntbdRequestRecord pendingRequest = this.pendingRequests.get(transportRequestId);

        if (pendingRequest == null) {
            reportIssue(logger, context, "{} ignored because there is no matching pending request", response);
            return;
        }

        final HttpResponseStatus status = response.getStatus();
        final UUID activityId = response.getActivityId();

        if (HttpResponseStatus.OK.code() <= status.code() && status.code() < HttpResponseStatus.MULTIPLE_CHOICES.code()) {

            final StoreResponse storeResponse = response.toStoreResponse(this.contextFuture.getNow(null));
            pendingRequest.complete(storeResponse);

        } else {

            // Map response to a DocumentClientException

            final DocumentClientException cause;

            // ..Fetch required header values

            final long lsn = response.getHeader(RntbdResponseHeader.LSN);
            final String partitionKeyRangeId = response.getHeader(RntbdResponseHeader.PartitionKeyRangeId);

            // ..Create Error instance

            final Error error = response.hasPayload() ?
                BridgeInternal.createError(RntbdObjectMapper.readTree(response)) :
                new Error(Integer.toString(status.code()), status.reasonPhrase(), status.codeClass().name());

            // ..Map RNTBD response headers to HTTP response headers

            final Map<String, String> responseHeaders = response.getHeaders().asMap(
                this.getRntbdContext().orElseThrow(IllegalStateException::new), activityId
            );

            // ..Create DocumentClientException based on status and sub-status codes

            switch (status.code()) {

                case StatusCodes.BADREQUEST:
                    cause = new BadRequestException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.CONFLICT:
                    cause = new ConflictException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.FORBIDDEN:
                    cause = new ForbiddenException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.GONE:

                    final int subStatusCode = Math.toIntExact(response.getHeader(RntbdResponseHeader.SubStatus));

                    switch (subStatusCode) {
                        case SubStatusCodes.COMPLETING_SPLIT:
                            cause = new PartitionKeyRangeIsSplittingException(error, lsn, partitionKeyRangeId, responseHeaders);
                            break;
                        case SubStatusCodes.COMPLETING_PARTITION_MIGRATION:
                            cause = new PartitionIsMigratingException(error, lsn, partitionKeyRangeId, responseHeaders);
                            break;
                        case SubStatusCodes.NAME_CACHE_IS_STALE:
                            cause = new InvalidPartitionException(error, lsn, partitionKeyRangeId, responseHeaders);
                            break;
                        case SubStatusCodes.PARTITION_KEY_RANGE_GONE:
                            cause = new PartitionKeyRangeGoneException(error, lsn, partitionKeyRangeId, responseHeaders);
                            break;
                        default:
                            cause = new GoneException(error, lsn, partitionKeyRangeId, responseHeaders);
                            break;
                    }
                    break;

                case StatusCodes.INTERNAL_SERVER_ERROR:
                    cause = new InternalServerErrorException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.LOCKED:
                    cause = new LockedException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.METHOD_NOT_ALLOWED:
                    cause = new MethodNotAllowedException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.NOTFOUND:
                    cause = new NotFoundException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.PRECONDITION_FAILED:
                    cause = new PreconditionFailedException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.REQUEST_ENTITY_TOO_LARGE:
                    cause = new RequestEntityTooLargeException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.REQUEST_TIMEOUT:
                    cause = new RequestTimeoutException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.RETRY_WITH:
                    cause = new RetryWithException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.SERVICE_UNAVAILABLE:
                    cause = new ServiceUnavailableException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.TOO_MANY_REQUESTS:
                    cause = new RequestRateTooLargeException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.UNAUTHORIZED:
                    cause = new UnauthorizedException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                default:
                    cause = new DocumentClientException(status.code(), error, responseHeaders);
                    break;
            }

            logger.trace("\n  {}\n  [transportRequestId: {}, activityId: {}, statusCode: {}, subStatusCode: {}]\n  ",
                context.channel(), transportRequestId, cause.getActivityId(), cause.getStatusCode(),
                cause.getSubStatusCode(), cause);

            pendingRequest.completeExceptionally(cause);
        }
    }

    private void traceOperation(final ChannelHandlerContext context, final String operationName, final Object... args) {

        if (logger.isTraceEnabled()) {

            final long birthTime;
            final BigDecimal lifetime;

            if (this.pendingRequest == null) {
                birthTime = System.nanoTime();
                lifetime = BigDecimal.ZERO;
            } else {
                birthTime = this.pendingRequest.getBirthTime();
                lifetime = BigDecimal.valueOf(this.pendingRequest.getLifetime().toNanos(), 6);
            }

            logger.trace("{},{},\"{}({})\",\"{}\",\"{}\"", birthTime, lifetime, operationName, Stream.of(args).map(arg ->
                    arg == null ? "null" : arg.toString()).collect(Collectors.joining(",")
                ), this.pendingRequest, context
            );
        }
    }

    // endregion

    // region Types

    private static class ClosedWithPendingRequestsException extends RuntimeException {

        static ClosedWithPendingRequestsException INSTANCE = new ClosedWithPendingRequestsException();

        // TODO: DANOBLE: Consider revising strategy for closing an RntbdTransportClient with pending requests
        //  One possibility:
        //  A channel associated with an RntbdTransportClient will not be closed immediately, if there are any pending
        //  requests on it. Instead it will be scheduled to close after the request timeout interval (default: 60s) has
        //  elapsed.
        //  Algorithm:
        //  When the RntbdTransportClient is closed, it closes each of its RntbdServiceEndpoint instances. In turn each
        //  RntbdServiceEndpoint closes its RntbdClientChannelPool. The RntbdClientChannelPool.close method should
        //  schedule closure of any channel with pending requests for later; when the request timeout interval has
        //  elapsed or--ideally--when all pending requests have completed.
        //  Links:
        //  https://msdata.visualstudio.com/CosmosDB/_workitems/edit/388987

        private ClosedWithPendingRequestsException() {
            super(null, null, /* enableSuppression */ false, /* writableStackTrace */ false);
        }
    }

    // endregion
}
