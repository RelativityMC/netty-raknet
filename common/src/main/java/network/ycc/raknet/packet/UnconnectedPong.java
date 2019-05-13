package network.ycc.raknet.packet;

import io.netty.buffer.ByteBuf;

import network.ycc.raknet.config.Magic;

public class UnconnectedPong extends SimplePacket implements Packet {

    private long clientTime = 0L;
    private long serverId = 0L;
    private Magic magic;
    private String info = "";

    public UnconnectedPong() {
        
    }

    public void decode(ByteBuf buf) {
        clientTime = buf.readLong();
        serverId = buf.readLong();
        magic = Magic.decode(buf);
        info = readString(buf);
    }

    public void encode(ByteBuf buf) {
        buf.writeLong(clientTime);
        buf.writeLong(serverId);
        magic.write(buf);
        writeString(buf, info);
    }

    public long getClientTime() {
        return clientTime;
    }

    public void setClientTime(long clientTime) {
        this.clientTime = clientTime;
    }

    public long getServerId() {
        return serverId;
    }

    public void setServerId(long serverId) {
        this.serverId = serverId;
    }

    public Magic getMagic() {
        return magic;
    }

    public void setMagic(Magic magic) {
        this.magic = magic;
    }

    public String getInfo() {
        return info;
    }

    public void setInfo(String info) {
        this.info = info;
    }

}