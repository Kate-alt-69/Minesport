package dev.kastrick.minesport.export;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Writes glTF's little-endian binary payload without retaining it on the heap. */
final class GltfBinaryWriter implements Closeable {
    private final OutputStream output;
    private final byte[] scalar = new byte[4];
    private long size;

    GltfBinaryWriter(File file) throws IOException {
        output = new BufferedOutputStream(new FileOutputStream(file), 64 * 1024);
    }

    long size() {
        return size;
    }

    void writeFloat(float value) throws IOException {
        writeInt(Float.floatToRawIntBits(value));
    }

    void writeInt(int value) throws IOException {
        scalar[0] = (byte) value;
        scalar[1] = (byte) (value >>> 8);
        scalar[2] = (byte) (value >>> 16);
        scalar[3] = (byte) (value >>> 24);
        output.write(scalar);
        size += 4;
    }

    void pad4() throws IOException {
        while ((size & 3) != 0) {
            output.write(0);
            size++;
        }
    }

    void flush() throws IOException {
        output.flush();
    }

    @Override
    public void close() throws IOException {
        output.close();
    }
}
