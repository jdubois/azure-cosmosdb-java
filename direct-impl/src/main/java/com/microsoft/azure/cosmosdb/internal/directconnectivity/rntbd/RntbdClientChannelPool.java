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
 *
 */

package com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkState;
import static com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd.RntbdReporter.reportIssue;
import static com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd.RntbdReporter.reportIssueUnless;

@JsonSerialize(using = RntbdClientChannelPool.JsonSerializer.class)
public final class RntbdClientChannelPool extends FixedChannelPool {

    // region Fields

    private static final Logger logger = LoggerFactory.getLogger(RntbdClientChannelPool.class);
    private static final AtomicReference<Field> pendingAcquireCount = new AtomicReference<>();

    private final AtomicInteger availableChannelCount;
    private final AtomicBoolean closed;
    private final int maxChannels;
    private final int maxRequestsPerChannel;

    // endregion

    // region Methods

    /**
     * Initializes a newly created {@link RntbdClientChannelPool} object
     *
     * @param bootstrap the {@link Bootstrap} that is used for connections
     * @param config    the {@link RntbdEndpoint.Config} that is used for the channel pool instance created
     */
    RntbdClientChannelPool(final Bootstrap bootstrap, final RntbdEndpoint.Config config) {

        super(bootstrap, new RntbdClientChannelHandler(config), ChannelHealthChecker.ACTIVE, null,
            -1L, config.getMaxChannelsPerEndpoint(), Integer.MAX_VALUE, true
        );

        this.maxRequestsPerChannel = config.getMaxRequestsPerChannel();
        this.maxChannels = config.getMaxChannelsPerEndpoint();
        this.availableChannelCount = new AtomicInteger();
        this.closed = new AtomicBoolean();
    }

    @Override
    public Future<Channel> acquire(Promise<Channel> promise) {
        this.throwIfClosed();
        return super.acquire(promise);
    }

    @Override
    public Future<Void> release(Channel channel, Promise<Void> promise) {
        this.throwIfClosed();
        return super.release(channel, promise);
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.availableChannelCount.set(0);
            super.close();
        }
    }

    public int availableChannelCount() {
        return this.availableChannelCount.get();
    }

    public int maxChannels() {
        return this.maxChannels;
    }

    public int maxRequestsPerChannel() {
        return this.maxRequestsPerChannel;
    }

    public int pendingAcquisitionCount() {

        Field field = pendingAcquireCount.get();

        if (field == null) {
            synchronized (pendingAcquireCount) {
                field = pendingAcquireCount.get();
                if (field == null) {
                    field = FieldUtils.getDeclaredField(FixedChannelPool.class, "pendingAcquireCount", true);
                    pendingAcquireCount.set(field);
                }
            }
        }

        try {
            return (int)FieldUtils.readField(field, this);
        } catch (IllegalAccessException error) {
            reportIssue(logger, this, "could not access field due to ", error);
        }

        return -1;
    }

    /**
     * Poll a {@link Channel} out of internal storage to reuse it
     * <p>
     * Maintainers: Implementations of this method must be thread-safe.
     *
     * @return a value of {@code null}, if no {@link Channel} is ready to be reused
     */
    @Override
    protected synchronized Channel pollChannel() {

        final Channel first = super.pollChannel();

        if (first == null) {
            return null;
        }

        if (this.closed.get()) {
            return first;  // because we're being called following a call to close (from super.close)
        }

        if (this.isInactiveOrServiceableChannel(first)) {
            return this.decrementAvailableChannelCountAndAccept(first);
        }

        super.offerChannel(first);  // because we need a non-null sentinel to stop the search for a channel

        for (Channel next = super.pollChannel(); next != first; super.offerChannel(next), next = super.pollChannel()) {
            if (this.isInactiveOrServiceableChannel(next)) {
                return this.decrementAvailableChannelCountAndAccept(next);
            }
        }

        super.offerChannel(first);  // because we choose not to check any channel more than once in a single call
        return null;
    }

    /**
     * Offer a {@link Channel} back to the internal storage
     * <p>
     * Maintainers: Implementations of this method must be thread-safe.
     *
     * @param channel the {@link Channel} to return to internal storage
     * @return {@code true}, if the {@link Channel} could be added to internal storage; otherwise {@code false}
     */
    @Override
    protected boolean offerChannel(final Channel channel) {
        if (super.offerChannel(channel)) {
            this.availableChannelCount.incrementAndGet();
            return true;
        }
        return false;
    }

    public SocketAddress remoteAddress() {
        return this.bootstrap().config().remoteAddress();
    }

    @Override
    public String toString() {
        return "RntbdClientChannelPool(" + RntbdObjectMapper.toJson(this) + ")";
    }

    // endregion

    // region Privates

    private Channel decrementAvailableChannelCountAndAccept(final Channel first) {
        this.availableChannelCount.decrementAndGet();
        return first;
    }

    private boolean isInactiveOrServiceableChannel(final Channel channel) {

        if (!channel.isActive()) {
            return true;
        }

        final RntbdRequestManager requestManager = channel.pipeline().get(RntbdRequestManager.class);

        if (requestManager == null) {
            reportIssueUnless(!channel.isActive(), logger, this, "{} active with no request manager", channel);
            return true; // inactive
        }

        return requestManager.isServiceable(this.maxRequestsPerChannel);
    }

    private void throwIfClosed() {
        checkState(!this.closed.get(), "%s is closed", this);
    }

    // endregion

    // region Types

    static final class JsonSerializer extends StdSerializer<RntbdClientChannelPool> {

        public JsonSerializer() {
            this(null);
        }

        public JsonSerializer(Class<RntbdClientChannelPool> type) {
            super(type);
        }

        @Override
        public void serialize(RntbdClientChannelPool value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            generator.writeStringField("remoteAddress", value.remoteAddress().toString());
            generator.writeNumberField("maxChannels", value.maxChannels());
            generator.writeNumberField("maxRequestsPerChannel", value.maxRequestsPerChannel());
            generator.writeObjectFieldStart("state");
            generator.writeBooleanField("isClosed", value.closed.get());
            generator.writeNumberField("acquiredChannelCount", value.acquiredChannelCount());
            generator.writeNumberField("availableChannelCount", value.availableChannelCount());
            generator.writeNumberField("pendingAcquisitionCount", value.pendingAcquisitionCount());
            generator.writeEndObject();
            generator.writeEndObject();
        }
    }
}
