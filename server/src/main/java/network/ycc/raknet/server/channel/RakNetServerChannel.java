package network.ycc.raknet.server.channel;

import network.ycc.raknet.channel.DatagramChannelProxy;
import network.ycc.raknet.packet.NoFreeConnections;
import network.ycc.raknet.packet.Packet;
import network.ycc.raknet.server.RakNetServer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.PromiseCombiner;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class RakNetServerChannel extends DatagramChannelProxy implements ServerChannel {

    protected final Map<SocketAddress, RakNetChildChannel> childMap = new HashMap<>();

    public RakNetServerChannel() {
        this(NioDatagramChannel.class);
    }

    public RakNetServerChannel(Supplier<? extends DatagramChannel> ioChannelSupplier) {
        super(ioChannelSupplier);
        addDefaultPipeline();
    }

    public RakNetServerChannel(Class<? extends DatagramChannel> ioChannelType) {
        super(ioChannelType);
        addDefaultPipeline();
    }

    @Override
    protected void gracefulClose(ChannelPromise promise) {
        final PromiseCombiner combined = new PromiseCombiner(eventLoop());
        final ChannelPromise childrenClosed = newPromise();
        childMap.values().forEach(child -> combined.add(child.close()));
        combined.finish(childrenClosed);
        childrenClosed.addListener(f -> listener.close(wrapPromise(promise)));
    }

    protected void addDefaultPipeline() {
        pipeline()
                .addLast(newServerHandler())
                .addLast(RakNetServer.DefaultDatagramInitializer.INSTANCE);
    }

    protected ChannelHandler newServerHandler() {
        return new ServerHandler();
    }

    protected RakNetChildChannel newChild(InetSocketAddress remoteAddress) {
        return new RakNetChildChannel(this, remoteAddress);
    }

    protected class ServerHandler extends ChannelDuplexHandler {
        @Override
        public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
                SocketAddress localAddress, ChannelPromise promise) {
            try {
                if (localAddress != null && !RakNetServerChannel.this.localAddress().equals(localAddress)) {
                    throw new IllegalArgumentException(
                            "Bound localAddress does not match provided " + localAddress);
                }
                if (!(remoteAddress instanceof InetSocketAddress)) {
                    throw new IllegalArgumentException(
                            "Provided remote address is not an InetSocketAddress");
                }
                if (childMap.size() > config.getMaxConnections()
                        && !childMap.containsKey(remoteAddress)) {
                    final Packet packet = new NoFreeConnections(
                            config.getMagic(), config.getServerId());
                    final ByteBuf buf = ctx.alloc().ioBuffer(packet.sizeHint());
                    try {
                        config.getCodec().encode(packet, buf);
                        ctx.writeAndFlush(new DatagramPacket(buf.retain(),
                                (InetSocketAddress) remoteAddress));
                    } finally {
                        ReferenceCountUtil.safeRelease(packet);
                        buf.release();
                    }
                    promise.tryFailure(new IllegalStateException("Too many connections"));
                } else if (!childMap.containsKey(remoteAddress)) {
                    final RakNetChildChannel child = newChild((InetSocketAddress) remoteAddress);
                    child.closeFuture().addListener(v ->
                            eventLoop().execute(() -> childMap.remove(remoteAddress, child))
                    );
                    child.config.setServerId(config.getServerId());
                    pipeline().fireChannelRead(child).fireChannelReadComplete(); //register
                    childMap.put(remoteAddress, child);
                    //TODO: tie promise to connection sequence?
                    promise.trySuccess();
                } else {
                    promise.trySuccess(); //already connected
                }
            } catch (Exception e) {
                promise.tryFailure(e);
                throw e;
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof DatagramPacket) {
                final DatagramPacket datagram = (DatagramPacket) msg;
                try {
                    final Channel child = childMap.get(datagram.sender());
                    if (child == null) {
                        ctx.fireChannelRead(datagram.retain());
                    } else if (child.isOpen() && child.config().isAutoRead()) {
                        child.pipeline()
                                .fireChannelRead(datagram.content().retain())
                                .fireChannelReadComplete();
                    }
                } finally {
                    datagram.release();
                }
            } else {
                ctx.fireChannelRead(msg);
            }
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            childMap.values().forEach(ch -> ch.pipeline().fireChannelWritabilityChanged());
            ctx.fireChannelWritabilityChanged();
        }
    }

}

