package com.sf2studio.sf2;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** {@link Source} backed by a FileChannel (works with Android file descriptors too). */
public final class ChannelSource implements Source, Closeable {
    private final FileChannel ch;
    private final long size;

    public ChannelSource(FileChannel ch) throws IOException {
        this.ch = ch;
        this.size = ch.size();
    }

    @Override
    public long length() {
        return size;
    }

    @Override
    public synchronized void readFully(long pos, byte[] b, int off, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b, off, len);
        while (bb.hasRemaining()) {
            int n = ch.read(bb, pos + (bb.position() - off));
            if (n < 0) throw new EOFException("Unexpected end of file");
        }
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }
}
