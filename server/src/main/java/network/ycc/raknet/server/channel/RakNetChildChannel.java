package network.ycc.raknet.server.channel;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import network.ycc.raknet.RakNet;
import network.ycc.raknet.config.DefaultConfig;
import network.ycc.raknet.pipeline.FlushTickHandler;
import network.ycc.raknet.server.RakNetServer;
import network.ycc.raknet.server.pipeline.ConnectionInitializer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;

public class RakNetChildChannel extends AbstractChannel {

    private static final ChannelMetadata metadata = new ChannelMetadata(false);
    protected final ChannelPromise connectPromise;
    protected final ChannelPromise closePromise;
    protected final RakNet.Config config;
    protected final InetSocketAddress remoteAddress;
    protected final InetSocketAddress localAddress;
    protected final RakNetApplicationChannel applicationChannel;
    protected final Consumer<Channel> registerChannel;

    protected volatile boolean open = true;

    public RakNetChildChannel(Channel parent, InetSocketAddress remoteAddress, InetSocketAddress localAddress, Consumer<Channel> registerChannel) {
        super(parent);
        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
        this.registerChannel = Objects.requireNonNull(registerChannel);
        config = new DefaultConfig(this);
        connectPromise = newPromise();
        closePromise = newPromise();
        config.setMetrics(parent.config().getOption(RakNet.METRICS));
        config.setServerId(parent.config().getOption(RakNet.SERVER_ID));
        this.applicationChannel = new RakNetApplicationChannel(this);
        pipeline().addLast(new WriteHandler());
        addDefaultPipeline();
    }

    protected void addDefaultPipeline() {
        pipeline().addLast(RakNetServer.DefaultChildInitializer.INSTANCE);
        connectPromise.addListener(x2 -> {
            if (!x2.isSuccess()) {
                RakNetChildChannel.this.applicationChannel.close();
                RakNetChildChannel.this.close();
            }
        });
        pipeline().addLast(new ChannelInitializer<RakNetChildChannel>() {
            protected void initChannel(RakNetChildChannel ch) {
                pipeline().replace(ConnectionInitializer.NAME, ConnectionInitializer.NAME,
                        new ConnectionInitializer(connectPromise));
                pipeline().addLast(RakNetApplicationChannel.NAME_SERVER_PARENT_THREADED_READ_HANDLER, new ReadHandler());
            }
        });
    }

    protected void registerApplicationChannelIfNecessary() {
        if (!this.applicationChannel.isRegistered()) {
//            new Throwable().printStackTrace();
            registerChannel.accept(this.applicationChannel);
            while (!this.applicationChannel.isRegistered()) {
                LockSupport.parkNanos(1_000_000);
            }
        }
    }

    public ChannelFuture connectFuture() {
        return connectPromise;
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
    public RakNetServerChannel parent() {
        return (RakNetServerChannel) super.parent();
    }

    protected AbstractUnsafe newUnsafe() {
        return new AbstractUnsafe() {
            public void connect(SocketAddress addr1, SocketAddress addr2, ChannelPromise pr) {
                throw new UnsupportedOperationException();
            }
        };
    }

    protected boolean isCompatible(EventLoop eventloop) {
        return true;
    }

    protected SocketAddress localAddress0() {
        return localAddress;
    }

    protected SocketAddress remoteAddress0() {
        return remoteAddress;
    }

    protected void doBind(SocketAddress addr) {
        throw new UnsupportedOperationException();
    }

    protected void doDisconnect() {
        close();
    }

    protected void doClose() {
        open = false;
    }

    protected void doBeginRead() {
        // NOOP
    }

    protected void doWrite(ChannelOutboundBuffer buffer) {
        throw new UnsupportedOperationException();
    }

    public RakNet.Config config() {
        return config;
    }

    public boolean isOpen() {
        return open;
    }

    public boolean isActive() {
        return isOpen() && parent().isActive() && connectPromise.isSuccess();
    }

    public ChannelMetadata metadata() {
        return metadata;
    }

    public RakNetApplicationChannel getApplicationChannel() {
        return this.applicationChannel;
    }

    protected class WriteHandler extends ChannelOutboundHandlerAdapter {
        protected boolean needsFlush = false;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof ByteBuf) {
                needsFlush = true;
                promise.trySuccess();
                parent().write(new DatagramPacket((ByteBuf) msg, remoteAddress, localAddress))
                        .addListener(RakNet.INTERNAL_WRITE_LISTENER);
            } else {
                ctx.write(msg, promise);
            }
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            if (needsFlush) {
                needsFlush = false;
                parent().flush();
            }
        }

        @Override
        public void read(ChannelHandlerContext ctx) {
            // NOOP
        }
    }

    protected class ReadHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            registerApplicationChannelIfNecessary();
            applicationChannel.pipeline().fireChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            registerApplicationChannelIfNecessary();
            applicationChannel.pipeline().fireChannelInactive();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            registerApplicationChannelIfNecessary();
            applicationChannel.pipeline().fireChannelRead(msg);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            if (!applicationChannel.isRegistered()) return;
            applicationChannel.pipeline().fireChannelReadComplete();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt == FlushTickHandler.FLUSH_CHECK_SIGNAL || evt instanceof FlushTickHandler.MissedFlushes) return;
            registerApplicationChannelIfNecessary();
            applicationChannel.pipeline().fireUserEventTriggered(evt);
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            registerApplicationChannelIfNecessary();
            applicationChannel.pipeline().fireChannelWritabilityChanged();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            registerApplicationChannelIfNecessary();
            applicationChannel.pipeline().fireExceptionCaught(cause);
        }
    }

}
