package yunqi.courier.kernel.spi.compression;

/** Transforms RPC payload bytes before they are written to the wire. */
public interface Compressor {

    byte[] compress(byte[] rawBytes);

    byte[] decompress(byte[] compressedBytes, int maxDecompressedLength);
}
