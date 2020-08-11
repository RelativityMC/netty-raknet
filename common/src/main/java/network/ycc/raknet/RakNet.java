package network.ycc.raknet;

import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket;
import network.ycc.raknet.packet.Packet;
import network.ycc.raknet.pipeline.DisconnectHandler;
import network.ycc.raknet.pipeline.FrameJoiner;
import network.ycc.raknet.pipeline.FrameOrderIn;
import network.ycc.raknet.pipeline.FrameOrderOut;
import network.ycc.raknet.pipeline.FrameSplitter;
import network.ycc.raknet.pipeline.FramedPacketCodec;
import network.ycc.raknet.pipeline.PingHandler;
import network.ycc.raknet.pipeline.PongHandler;
import network.ycc.raknet.pipeline.ReliabilityHandler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.util.AttributeKey;

import java.nio.channels.ClosedChannelException;

public class RakNet {

    public static final AttributeKey<Boolean> WRITABLE = AttributeKey.valueOf("RN_WRITABLE");

    public static final ChannelOption<Long> SERVER_ID = ChannelOption.valueOf("RN_SERVER_ID");
    public static final ChannelOption<Long> CLIENT_ID = ChannelOption.valueOf("RN_CLIENT_ID");
    public static final ChannelOption<MetricsLogger> METRICS = ChannelOption.valueOf("RN_METRICS");
    public static final ChannelOption<Integer> MTU = ChannelOption.valueOf("RN_MTU");
    public static final ChannelOption<Long> RTT = ChannelOption.valueOf("RN_RTT");
    public static final ChannelOption<Integer> PROTOCOL_VERSION = ChannelOption.valueOf("RN_PROTOCOL_VERSION");
    public static final ChannelOption<Magic> MAGIC = ChannelOption.valueOf("RN_MAGIC");
    public static final ChannelOption<Long> RETRY_DELAY_NANOS = ChannelOption.valueOf("RN_RETRY_DELAY_NANOS");
    public static final ChannelOption<Integer> MAX_CONNECTIONS = ChannelOption.valueOf("RN_MAX_CONNECTIONS");

    public static final ChannelFutureListener INTERNAL_WRITE_LISTENER = future -> {
        if (!future.isSuccess() && !(future.cause() instanceof ClosedChannelException)) {
            future.channel().pipeline().fireExceptionCaught(future.cause());
            future.channel().close();
        }
    };

    public static Config config(ChannelHandlerContext ctx) {
        return config(ctx.channel());
    }

    public static Config config(Channel channel) {
        return (Config) channel.config();
    }

    public static MetricsLogger metrics(ChannelHandlerContext ctx) {
        return config(ctx).getMetrics();
    }

    /**
     * Channel specific metrics logging interface.
     */
    public interface MetricsLogger {
        default void packetsIn(int delta) {}
        default void framesIn(int delta) {}
        default void frameError(int delta) {}
        default void bytesIn(int delta) {}
        default void packetsOut(int delta) {}
        default void framesOut(int delta) {}
        default void bytesOut(int delta) {}
        default void bytesRecalled(int delta) {}
        default void bytesACKd(int delta) {}
        default void bytesNACKd(int delta) {}
        default void acksSent(int delta) {}
        default void nacksSent(int delta) {}
        default void measureRTTns(long n) {}
        default void measureRTTnsStdDev(long n) {}
        default void measureBurstTokens(int n) {}
    }

    public interface Config extends ChannelConfig {
        MetricsLogger getMetrics();

        void setMetrics(MetricsLogger metrics);

        /**
         * @return Server ID used during handshake.
         */
        long getServerId();
        void setServerId(long serverId);

        /**
         * @return Client ID used during handshake.
         */
        long getClientId();
        void setClientId(long clientId);

        /**
         * @return MTU in bytes, negotiated during handshake.
         */
        int getMTU();
        void setMTU(int mtu);

        /**
         * @return Offset used while calculating retry period.
         */
        long getRetryDelayNanos();
        void setRetryDelayNanos(long retryDelayNanos);

        long getRTTNanos();
        void setRTTNanos(long rtt);
        long getRTTStdDevNanos();
        void updateRTTNanos(long rttSample);

        int getMaxPendingFrameSets();
        void setMaxPendingFrameSets(int maxPendingFrameSets);

        int getDefaultPendingFrameSets();
        void setDefaultPendingFrameSets(int defaultPendingFrameSets);

        int getMaxQueuedBytes();
        void setMaxQueuedBytes(int maxQueuedBytes);

        Magic getMagic();
        void setMagic(Magic magic);

        Codec getCodec();
        void setCodec(Codec codec);

        int[] getProtocolVersions();
        void setprotocolVersions(int[] protocolVersions);
        boolean containsProtocolVersion(int protocolVersion);
        int getProtocolVersion();
        void setProtocolVersion(int protocolVersion);

        int getMaxConnections();
        void setMaxConnections(int maxConnections);
    }

    public interface Codec {
        FrameData encode(FramedPacket packet, ByteBufAllocator alloc);
        void encode(Packet packet, ByteBuf out);
        ByteBuf produceEncoded(Packet packet, ByteBufAllocator alloc);
        Packet decode(ByteBuf in);
        FramedPacket decode(FrameData data);
        int packetIdFor(Class<? extends Packet> type);
    }

    public interface Magic {
        void write(ByteBuf buf);
        void read(ByteBuf buf);
        void verify(Magic other);

        class MagicMismatchException extends CorruptedFrameException {
            public static final long serialVersionUID = 590681756L;

            public MagicMismatchException() {
                super("Incorrect RakNet magic value");
            }

            @Override
            public synchronized Throwable fillInStackTrace() {
                return this;
            }
        }
    }

    public static class ReliableFrameHandling extends ChannelInitializer<Channel> {
        public static final ReliableFrameHandling INSTANCE = new ReliableFrameHandling();

        protected void initChannel(Channel channel) {
            channel.pipeline()
                    .addLast(ReliabilityHandler.NAME,   new ReliabilityHandler())
                    .addLast(FrameJoiner.NAME,          new FrameJoiner())
                    .addLast(FrameSplitter.NAME,        new FrameSplitter())
                    .addLast(FrameOrderIn.NAME,         new FrameOrderIn())
                    .addLast(FrameOrderOut.NAME,        new FrameOrderOut())
                    .addLast(FramedPacketCodec.NAME,    new FramedPacketCodec());
        }
    }

    public static class PacketHandling extends ChannelInitializer<Channel> {
        public static final PacketHandling INSTANCE = new PacketHandling();

        protected void initChannel(Channel channel) {
            channel.pipeline()
                    .addLast(DisconnectHandler.NAME,    DisconnectHandler.INSTANCE)
                    .addLast(PingHandler.NAME,          PingHandler.INSTANCE)
                    .addLast(PongHandler.NAME,          PongHandler.INSTANCE);
        }
    }

}
