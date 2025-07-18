package network.ycc.raknet;

import network.ycc.raknet.server.channel.RakNetChildChannel;
import network.ycc.raknet.server.channel.RakNetServerChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import network.ycc.raknet.client.RakNetClient;
import network.ycc.raknet.config.DefaultMagic;
import network.ycc.raknet.packet.FrameSet;
import network.ycc.raknet.packet.InvalidVersion;
import network.ycc.raknet.server.RakNetServer;
import network.ycc.raknet.utils.EmptyInit;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.CorruptedFrameException;

import java.net.InetSocketAddress;
import java.nio.channels.UnsupportedAddressTypeException;
import java.util.concurrent.ExecutionException;

public class BehaviorTest {
    final EventLoopGroup ioGroup = new NioEventLoopGroup();
    final EventLoopGroup childGroup = new NioEventLoopGroup();
    final int port = 31747;
    final InetSocketAddress localhost = new InetSocketAddress("localhost", port);
    final RakNet.Magic badMagic = new DefaultMagic(new byte[16]);

    @Test
    public void connectIPv6() throws Throwable {
        try {
            final InetSocketAddress localhostIPv6 = new InetSocketAddress("::1", port);

            tryConnect(localhostIPv6);
        } catch (UnsupportedAddressTypeException e) {
            System.out.println("No IPv6 support, skipping.");
        }
    }

    @Test
    public void connectIPv4() throws Throwable {
        try {
            tryConnect(localhost);
        } catch (UnsupportedAddressTypeException e) {
            System.out.println("No IPv4 support, skipping.");
        }
    }

    private void tryConnect(InetSocketAddress localhost) throws InterruptedException, ExecutionException {
        final Channel serverChannel = new ServerBootstrap()
                .group(ioGroup, childGroup)
                .channel(RakNetServer.CHANNEL)
                .childHandler(new EmptyInit())
                .bind(localhost).sync().channel();

        final Channel clientChannel = new Bootstrap()
                .group(ioGroup)
                .channel(RakNetClient.THREADED_CHANNEL)
                .handler(new EmptyInit())
                .connect(localhost).sync().channel();

        final RakNetChildChannel childChannel = serverChannel.eventLoop().submit(() -> (RakNetChildChannel) ((RakNetServerChannel) serverChannel).getChildChannel(clientChannel.localAddress())).get();
        childChannel.connectFuture().sync();

        try {
            Assertions.assertTrue(clientChannel.isActive());
            Assertions.assertTrue(childChannel.isActive());
//            Assertions.assertTrue(childChannel.getApplicationChannel().isActive()); // TODO wait for application channel to be active
        } finally {
            serverChannel.close().sync();
            clientChannel.close().sync();
        }
    }

    @Test()
    public void badMagicClient() throws Throwable {
        final Channel serverChannel = new ServerBootstrap()
                .group(ioGroup, childGroup)
                .channel(RakNetServer.CHANNEL)
                .childHandler(new EmptyInit())
                .bind(localhost).sync().channel();

        final ChannelFuture clientConnect = new Bootstrap()
                .group(ioGroup)
                .channel(RakNetClient.THREADED_CHANNEL)
                .option(RakNet.MAGIC, badMagic)
                .handler(new EmptyInit())
                .connect(localhost);

        final Channel clientChannel = clientConnect.channel();

        Assertions.assertThrows(IllegalStateException.class, () -> {
            try {
                clientConnect.sync();
            } finally {
                serverChannel.close().sync();
                clientChannel.close().sync();
            }
        });
    }

    /*@Test(expected = IllegalStateException.class)
    public void badMagicServer() throws Throwable {
        final Channel serverChannel = new ServerBootstrap()
                .group(ioGroup, childGroup)
                .channel(RakNetServer.CHANNEL)
                .option(RakNet.MAGIC, badMagic)
                .childHandler(new EmptyInit())
                .bind(localhost).sync().channel();

        final ChannelFuture clientConnect = new Bootstrap()
                .group(ioGroup)
                .channel(RakNetClient.CHANNEL)
                .handler(new EmptyInit())
                .connect(localhost);

        final Channel clientChannel = clientConnect.channel();

        try {
            clientConnect.sync();
        } finally {
            serverChannel.close().sync();
            clientChannel.close().sync();
        }
    }*/

    @Test()
    public void badConnect() throws Throwable {
        final Channel serverChannel = new Bootstrap()
                .group(ioGroup)
                .channel(NioDatagramChannel.class)
                .handler(new EmptyInit())
                .bind(localhost).sync().channel();

        final ChannelFuture clientConnect = new Bootstrap()
                .group(ioGroup)
                .channel(RakNetClient.THREADED_CHANNEL)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .handler(new EmptyInit())
                .connect(localhost);

        final Channel clientChannel = clientConnect.channel();

        Assertions.assertThrows(ConnectTimeoutException.class, () -> {
            try {
                clientConnect.sync();
            } finally {
                serverChannel.close().sync();
                clientChannel.close().sync();
            }
        });
    }

    public void badVersionClient() throws Throwable {
        final Channel serverChannel = new ServerBootstrap()
                .group(ioGroup, childGroup)
                .channel(RakNetServer.CHANNEL)
                .childHandler(new EmptyInit())
                .bind(localhost).sync().channel();

        final ChannelFuture clientConnect = new Bootstrap()
                .group(ioGroup)
                .channel(RakNetClient.THREADED_CHANNEL)
                .option(RakNet.PROTOCOL_VERSION, 1)
                .handler(new EmptyInit())
                .connect(localhost);

        final Channel clientChannel = clientConnect.channel();

        Assertions.assertThrows(InvalidVersion.InvalidVersionException.class, () -> {
            try {
                clientConnect.sync();
            } finally {
                serverChannel.close().sync();
                clientChannel.close().sync();
            }
        });
    }

    public void badVersionServer() throws Throwable {
        final Channel serverChannel = new ServerBootstrap()
                .group(ioGroup, childGroup)
                .channel(RakNetServer.CHANNEL)
                .option(RakNet.PROTOCOL_VERSION, 1)
                .childHandler(new EmptyInit())
                .bind(localhost).sync().channel();

        final ChannelFuture clientConnect = new Bootstrap()
                .group(ioGroup)
                .channel(RakNetClient.THREADED_CHANNEL)
                .handler(new EmptyInit())
                .connect(localhost);

        final Channel clientChannel = clientConnect.channel();

        Assertions.assertThrows(InvalidVersion.InvalidVersionException.class, () -> {
            try {
                clientConnect.sync();
            } finally {
                serverChannel.close().sync();
                clientChannel.close().sync();
            }
        });
    }

    @Test()
    public void corruptFrameTest() {
        Assertions.assertThrows(CorruptedFrameException.class, () -> {
            FrameSet.read(Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9}));
        });
    }
}
