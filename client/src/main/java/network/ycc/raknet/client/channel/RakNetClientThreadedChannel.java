package network.ycc.raknet.client.channel;

import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.DatagramChannel;
import network.ycc.raknet.RakNet;

import java.net.SocketAddress;
import java.util.function.Supplier;

public class RakNetClientThreadedChannel extends AbstractChannel {

    public static final String NAME_CLIENT_THREADED_WRITE_HANDLER = "rn-client-threaded-write-handler";
    public static final String NAME_CLIENT_PARENT_THREADED_READ_HANDLER = "rn-client-parent-threaded-read-handler";

    private EventLoop providedEventLoop = null;
    private EventLoop pendingEventLoop = null;

    public RakNetClientThreadedChannel() {
        super(new RakNetClientChannel());
        setupDefaultPipeline();
    }

    public RakNetClientThreadedChannel(Class<? extends DatagramChannel> ioChannelType) {
        super(new RakNetClientChannel(ioChannelType));
        setupDefaultPipeline();
    }

    public RakNetClientThreadedChannel(Supplier<? extends DatagramChannel> ioChannelSupplier) {
        super(new RakNetClientChannel(ioChannelSupplier));
        setupDefaultPipeline();
    }

    public void setProvidedEventLoop(EventLoop providedEventLoop) {
        this.providedEventLoop = providedEventLoop;
    }

    private void setupDefaultPipeline() {
        pipeline().addLast(NAME_CLIENT_THREADED_WRITE_HANDLER, new WriteHandler());
        parent().pipeline().addLast(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
                channel.pipeline().addLast(NAME_CLIENT_PARENT_THREADED_READ_HANDLER, new ParentReadHandler());
            }
        });
    }

    @Override
    public boolean isWritable() {
        final Boolean result = attr(RakNet.WRITABLE).get();
        return (result == null || result) && parent().isWritable();
    }

    @Override
    public long bytesBeforeUnwritable() {
        return parent().bytesBeforeUnwritable();
    }

    @Override
    public long bytesBeforeWritable() {
        return parent().bytesBeforeWritable();
    }

    @Override
    public RakNetClientChannel parent() {
        return (RakNetClientChannel) super.parent();
    }

    protected AbstractUnsafe newUnsafe() {
        return new AbstractUnsafe() {
            public void connect(SocketAddress addr1, SocketAddress addr2, ChannelPromise pr) {
                throw new UnsupportedOperationException();
            }
        };
    }

    protected boolean isCompatible(EventLoop eventloop) {
        this.pendingEventLoop = eventloop;
        return true;
    }

    protected SocketAddress localAddress0() {
        return parent().localAddress();
    }

    protected SocketAddress remoteAddress0() {
        return parent().remoteAddress();
    }

    protected void doBind(SocketAddress addr) {
        throw new UnsupportedOperationException();
    }

    protected void doDisconnect() {
        close();
        parent().close();
    }

    protected void doClose() {
        close();
        parent().close();
    }

    protected void doBeginRead() {
        // NOOP
    }

    protected void doWrite(ChannelOutboundBuffer buffer) {
        throw new UnsupportedOperationException();
    }

    public RakNet.Config config() {
        return parent().config();
    }

    public boolean isOpen() {
        return parent().isOpen();
    }

    public boolean isActive() {
        return isOpen() && parent().isActive();
    }

    public ChannelMetadata metadata() {
        return parent().metadata();
    }

    @Override
    public ChannelFuture close() {
        final ChannelFuture close = super.close();
        final ChannelPromise promise = newPromise();
        close.addListener(future -> parent().close().addListener(future1 -> {
            if (future1.isSuccess()) promise.setSuccess();
            else promise.setFailure(future1.cause());
        }));
        return promise;
    }

    protected class WriteHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) throws Exception {
            registerParent();
            final ChannelFuture future = parent().bind(localAddress);
            future.addListener(RakNet.INTERNAL_WRITE_LISTENER);
            future.addListener(future1 -> {
                if (future1.isSuccess()) promise.trySuccess();
                else promise.tryFailure(future1.cause());
            });
        }

        @Override
        public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
            registerParent();
            final ChannelFuture future = parent().connect(remoteAddress, localAddress);
            future.addListener(RakNet.INTERNAL_WRITE_LISTENER);
            future.addListener(future1 -> {
                if (future1.isSuccess()) promise.trySuccess();
                else promise.tryFailure(future1.cause());
            });
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            final ChannelFuture future = parent().write(msg);
            future.addListener(RakNet.INTERNAL_WRITE_LISTENER);
            future.addListener(future1 -> {
                if (future1.isSuccess()) promise.trySuccess();
                else promise.tryFailure(future1.cause());
            });
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            parent().flush();
        }

        @Override
        public void read(ChannelHandlerContext ctx) {
            // NOOP
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
            parent().close().addListener(future -> {
                if (future.isSuccess()) promise.trySuccess();
                else promise.tryFailure(future.cause());
            });
        }
    }

    private void registerParent() {
        if (!parent().isRegistered()) {
            EventLoop loop = this.providedEventLoop != null ? this.providedEventLoop : this.pendingEventLoop;
            if (loop == null)
                throw new IllegalStateException("channel not registered");
            loop.register(parent());
        }
    }

    protected class ParentReadHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            RakNetClientThreadedChannel.this.pipeline().fireChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            RakNetClientThreadedChannel.this.pipeline().fireChannelInactive();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            RakNetClientThreadedChannel.this.pipeline().fireChannelRead(msg);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            RakNetClientThreadedChannel.this.pipeline().fireChannelReadComplete();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            RakNetClientThreadedChannel.this.pipeline().fireUserEventTriggered(evt);
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            RakNetClientThreadedChannel.this.pipeline().fireChannelWritabilityChanged();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            RakNetClientThreadedChannel.this.pipeline().fireExceptionCaught(cause);
        }

    }

}
