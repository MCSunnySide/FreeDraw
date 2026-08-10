package com.github.squi2rel.freedraw.bukkit.network;

import java.util.Arrays;

/**
 * Minimal growable byte buffer that mirrors the Netty {@code ByteBuf} operations
 * used by the Fabric mod's protocol code. Keeping the plugin free of a hard
 * Netty dependency makes it build against the plain Bukkit/Paper API.
 *
 * All reads/writes are big-endian, matching Netty's default order.
 */
public class PacketBuf {
    private byte[] data;
    private int readerIndex;
    private int writerIndex;

    private PacketBuf(byte[] data, int writerIndex) {
        this.data = data;
        this.readerIndex = 0;
        this.writerIndex = writerIndex;
    }

    /** Wrap an incoming packet's raw bytes for reading. */
    public static PacketBuf wrapped(byte[] data) {
        return new PacketBuf(data, data.length);
    }

    /** Create a growable buffer for writing. */
    public static PacketBuf buffer() {
        return new PacketBuf(new byte[32], 0);
    }

    private void ensureWritable(int bytes) {
        int needed = writerIndex + bytes;
        if (needed > data.length) {
            int capacity = Math.max(data.length * 2, needed);
            data = Arrays.copyOf(data, capacity);
        }
    }

    public int readerIndex() {
        return readerIndex;
    }

    public void readerIndex(int index) {
        this.readerIndex = index;
    }

    public int readableBytes() {
        return writerIndex - readerIndex;
    }

    public byte readByte() {
        return data[readerIndex++];
    }

    public int readUnsignedByte() {
        return data[readerIndex++] & 0xFF;
    }

    public void writeByte(int value) {
        ensureWritable(1);
        data[writerIndex++] = (byte) value;
    }

    public short readShort() {
        int a = readUnsignedByte();
        int b = readUnsignedByte();
        return (short) ((a << 8) | b);
    }

    public int readUnsignedShort() {
        return readShort() & 0xFFFF;
    }

    public void writeShort(int value) {
        writeByte(value >>> 8);
        writeByte(value);
    }

    public int readInt() {
        int a = readUnsignedByte();
        int b = readUnsignedByte();
        int c = readUnsignedByte();
        int d = readUnsignedByte();
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    public void writeInt(int value) {
        writeByte(value >>> 24);
        writeByte(value >>> 16);
        writeByte(value >>> 8);
        writeByte(value);
    }

    public long readLong() {
        long a = readUnsignedByte();
        long b = readUnsignedByte();
        long c = readUnsignedByte();
        long d = readUnsignedByte();
        long e = readUnsignedByte();
        long f = readUnsignedByte();
        long g = readUnsignedByte();
        long h = readUnsignedByte();
        return (a << 56) | (b << 48) | (c << 40) | (d << 32) | (e << 24) | (f << 16) | (g << 8) | h;
    }

    public void writeLong(long value) {
        writeByte((int) (value >>> 56));
        writeByte((int) (value >>> 48));
        writeByte((int) (value >>> 40));
        writeByte((int) (value >>> 32));
        writeByte((int) (value >>> 24));
        writeByte((int) (value >>> 16));
        writeByte((int) (value >>> 8));
        writeByte((int) value);
    }

    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    public void writeFloat(float value) {
        writeInt(Float.floatToIntBits(value));
    }

    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    public void writeDouble(double value) {
        writeLong(Double.doubleToLongBits(value));
    }

    public boolean readBoolean() {
        return readByte() != 0;
    }

    public void writeBoolean(boolean value) {
        writeByte(value ? 1 : 0);
    }

    public byte[] readBytes(int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, readerIndex, out, 0, length);
        readerIndex += length;
        return out;
    }

    public void writeBytes(byte[] bytes) {
        ensureWritable(bytes.length);
        System.arraycopy(bytes, 0, data, writerIndex, bytes.length);
        writerIndex += bytes.length;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(data, writerIndex);
    }
}
