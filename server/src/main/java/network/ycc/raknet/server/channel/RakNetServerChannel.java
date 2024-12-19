package network.ycc.raknet.server.channel;

import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import network.ycc.raknet.RakNet;
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

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
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

    public Channel getChildChannel(SocketAddress addr) {
        if (!eventLoop().inEventLoop()) {
            throw new IllegalStateException("Method must be called from the server eventLoop!");
        }

        return childMap.get(addr);
    }

    @Override
    protected void gracefulClose(ChannelPromise promise) {
        final PromiseCombiner combined = new PromiseCombiner();
        final ChannelPromise childrenClosed = newPromise();
        childMap.values().forEach(child -> combined.add(child.applicationChannel.close())); // TODO we can technically close the server while keeping all child connections alive
        combined.finish(childrenClosed);
        childrenClosed.addListener(f -> listener.close(wrapPromise(promise)));
    }

    protected void addDefaultPipeline() {
        listener.config().setReuseAddress(true);
        pipeline()
                .addLast(newServerHandler())
                .addLast(RakNetServer.DefaultDatagramInitializer.INSTANCE);
    }

    protected ChannelHandler newServerHandler() {
        return new ServerHandler();
    }

    protected RakNetChildChannel newChild(InetSocketAddress remoteAddress, InetSocketAddress localAddress, Consumer<Channel> registerChannel) {
        return new RakNetChildChannel(this.ioChannelSupplier, this, remoteAddress, localAddress, registerChannel);
    }

    protected void removeChild(SocketAddress remoteAddress, RakNetChildChannel child) {
        childMap.remove(remoteAddress, child);
    }

    protected void addChild(SocketAddress remoteAddress, RakNetChildChannel child) {
        childMap.put(remoteAddress, child);
    }

    DatagramChannel backingChannel() {
        return this.listener;
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
                if (!(localAddress instanceof InetSocketAddress)) {
                    throw new IllegalArgumentException(
                            "Provided local address is not an InetSocketAddress");
                }
                final Channel existingChild = getChildChannel(remoteAddress);
                if (childMap.size() > config.getMaxConnections() && existingChild == null) {
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
                } else if (existingChild == null) {
                    final RakNetChildChannel child = newChild((InetSocketAddress) remoteAddress, (InetSocketAddress) localAddress, channel -> {
                        pipeline().fireChannelRead(channel).fireChannelReadComplete(); //register
                    });
                    child.closeFuture().addListener(v ->
                            eventLoop().execute(() -> removeChild(remoteAddress, child))
                    );
                    child.config().setOption(RakNet.SERVER_ID, config.getServerId());
                    initChildChannel(child);
//                    pipeline().fireChannelRead(child.applicationChannel).fireChannelReadComplete(); //register
                    addChild(remoteAddress, child);
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
                    //a null recipient means this was locally forwarded, dont process again
                    if (child == null && datagram.recipient() != null) {
                        ctx.fireChannelRead(datagram.retain());
                    } else if (child != null && child.isOpen() && child.config().isAutoRead()) {
                        final ByteBuf retained = datagram.content().retain();
                        child.eventLoop().execute(() ->
                            child.pipeline().fireChannelRead(retained).fireChannelReadComplete());
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

        @SuppressWarnings("unchecked")
        private ChannelFuture initChildChannel(Channel child) {
            try {
                final Class<? extends ChannelHandler> acceptorClass = (Class<? extends ChannelHandler>) Class.forName("io.netty.bootstrap.ServerBootstrap$ServerBootstrapAcceptor");
                final Field childGroupField = acceptorClass.getDeclaredField("childGroup");
                childGroupField.setAccessible(true);
                final EventLoopGroup childGroup = (EventLoopGroup) childGroupField.get(pipeline().get(acceptorClass));
                return childGroup.register(child);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }

}

