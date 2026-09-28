package yunqi.courier.compression.lz4;

import net.jpountz.lz4.LZ4Factory;
import yunqi.courier.common.exception.CourierException;
import yunqi.courier.kernel.spi.compression.Compressor;
import yunqi.courier.kernel.spi.core.SPI;

import java.util.Arrays;

@SPI("lz4")
public final class Lz4Compressor implements Compressor {

    private static final int LENGTH_PREFIX = Integer.BYTES;
    private static final LZ4Factory FACTORY = LZ4Factory.fastestInstance();

    @Override
    public byte[] compress(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) {
            return rawBytes == null ? new byte[0] : rawBytes;
        }
        var compressor = FACTORY.fastCompressor();
        byte[] output = new byte[LENGTH_PREFIX + compressor.maxCompressedLength(rawBytes.length)];
        writeInt(output, rawBytes.length);
        int length = compressor.compress(rawBytes, 0, rawBytes.length,
                output, LENGTH_PREFIX, output.length - LENGTH_PREFIX);
        return Arrays.copyOf(output, LENGTH_PREFIX + length);
    }

    @Override
    public byte[] decompress(byte[] compressedBytes, int maxDecompressedLength) {
        if (compressedBytes == null || compressedBytes.length == 0) {
            return compressedBytes == null ? new byte[0] : compressedBytes;
        }
        if (compressedBytes.length < LENGTH_PREFIX) {
            throw new CourierException("Invalid LZ4 payload");
        }
        int restoredLength = readInt(compressedBytes);
        if (restoredLength < 0 || restoredLength > maxDecompressedLength) {
            throw new CourierException("LZ4 decompressed payload exceeds maximum length");
        }
        byte[] restored = new byte[restoredLength];
        try {
            FACTORY.fastDecompressor().decompress(compressedBytes, LENGTH_PREFIX,
                    restored, 0, restoredLength);
            return restored;
        } catch (RuntimeException e) {
            throw new CourierException("LZ4 decompression failed", e);
        }
    }

    private static void writeInt(byte[] bytes, int value) {
        bytes[0] = (byte) (value >>> 24);
        bytes[1] = (byte) (value >>> 16);
        bytes[2] = (byte) (value >>> 8);
        bytes[3] = (byte) value;
    }

    private static int readInt(byte[] bytes) {
        return ((bytes[0] & 0xff) << 24) | ((bytes[1] & 0xff) << 16)
                | ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
    }
}
