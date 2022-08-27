package network.ycc.raknet.server.channel;

import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import network.ycc.raknet.RakNet;

import java.net.SocketAddress;

public class RakNetApplicationChannel extends AbstractChannel {

    public static final String NAME_SERVER_THREADED_WRITE_HANDLER = "rn-server-threaded-write-handler";
    public static final String NAME_SERVER_PARENT_THREADED_READ_HANDLER = "rn-server-parent-threaded-read-handler";

    protected RakNetApplicationChannel(RakNetChildChannel parent) {
        super(parent);
        pipeline().addLast(NAME_SERVER_THREADED_WRITE_HANDLER, new WriteHandler());
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
    public RakNetChildChannel parent() {
        return (RakNetChildChannel) super.parent();
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
        if (this.isRegistered()) {
            final ChannelFuture close = super.close();
            final ChannelPromise promise = newPromise();
            close.addListener(future -> parent().close().addListener(future1 -> {
                if (future1.isSuccess()) promise.setSuccess();
                else promise.setFailure(future1.cause());
            }));
            return promise;
        } else {
            return parent().close();
        }
    }

    protected class WriteHandler extends ChannelOutboundHandlerAdapter {

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

}
