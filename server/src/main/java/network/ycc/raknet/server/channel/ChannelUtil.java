package network.ycc.raknet.server.channel;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.util.AttributeKey;

import java.util.Map;

public class ChannelUtil {

    static void applyChannelParameters(Channel channel, RakNetServerChannel.ChannelParameters parameters) {
        setChannelOptions(channel, parameters.childOptions);
        for (Map.Entry<AttributeKey<?>, Object> e: parameters.childAttrs) {
            channel.attr((AttributeKey<Object>) e.getKey()).set(e.getValue());
        }
    }

    static void setChannelOptions(
            Channel channel, Map.Entry<ChannelOption<?>, Object>[] options) {
        for (Map.Entry<ChannelOption<?>, Object> e: options) {
            setChannelOption(channel, e.getKey(), e.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private static void setChannelOption(
            Channel channel, ChannelOption<?> option, Object value) {
        try {
            if (!channel.config().setOption((ChannelOption<Object>) option, value)) {
                System.out.println(String.format("Unknown channel option '%s' for channel '%s'", option, channel));
            }
        } catch (Throwable t) {
            System.err.println(String.format("Failed to set channel option '%s' with value '%s' for channel '%s'", option, value, channel));
            t.printStackTrace();
        }
    }

}
