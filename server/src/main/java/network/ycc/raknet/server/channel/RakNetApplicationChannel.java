package network.ycc.raknet.server.channel;

import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import network.ycc.raknet.RakNet;

import java.net.SocketAddress;

public class RakNetApplicationChannel extends AbstractChannel {

    protected RakNetApplicationChannel(RakNetChildChannel parent) {
        super(parent);
        pipeline().addLast(new WriteHandler());
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
        parent().close();
        close();
    }

    protected void doClose() {
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
        return isOpen() && parent().isActive() && parent().connectPromise.isSuccess();
    }

    public ChannelMetadata metadata() {
        return parent().metadata();
    }

    protected class WriteHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            parent().write(msg, promise).addListener(RakNet.INTERNAL_WRITE_LISTENER);
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
