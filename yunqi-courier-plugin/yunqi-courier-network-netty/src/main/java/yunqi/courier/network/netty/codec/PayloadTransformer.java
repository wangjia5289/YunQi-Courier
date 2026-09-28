package yunqi.courier.network.netty.codec;

import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.kernel.spi.compression.Compressor;
import yunqi.courier.kernel.spi.core.ServiceProviderLoader;
import yunqi.courier.kernel.spi.encryption.Encryptor;

/** Applies the configured payload transforms in a fixed, reversible order. */
public final class PayloadTransformer {

    private static final PayloadTransformer IDENTITY = new PayloadTransformer(null, null, 0);

    private final Compressor compressor;
    private final Encryptor encryptor;
    private final int maxFrameLength;

    private PayloadTransformer(Compressor compressor, Encryptor encryptor, int maxFrameLength) {
        this.compressor = compressor;
        this.encryptor = encryptor;
        this.maxFrameLength = maxFrameLength;
    }

    public static PayloadTransformer fromConfig(CourierConfig config) {
        Compressor compressor = null;
        if (!CourierConfigNames.NONE.equals(config.getCompression())) {
            compressor = ServiceProviderLoader.newInstance(Compressor.class, config.getCompression());
        }
        Encryptor encryptor = null;
        if (!CourierConfigNames.NONE.equals(config.getEncryption())) {
            encryptor = ServiceProviderLoader.newInstance(Encryptor.class, config.getEncryption());
            encryptor.configure(config.getEncryptionKey());
        }
        if (compressor == null && encryptor == null) {
            return IDENTITY;
        }
        return new PayloadTransformer(compressor, encryptor, config.getMaxFrameLength());
    }

    public byte[] encode(byte[] payload) {
        byte[] transformed = payload == null ? new byte[0] : payload;
        if (compressor != null) {
            transformed = compressor.compress(transformed);
        }
        if (encryptor != null) {
            transformed = encryptor.encrypt(transformed);
        }
        ensureWireLength(transformed);
        return transformed;
    }

    public byte[] decode(byte[] payload) {
        byte[] transformed = payload == null ? new byte[0] : payload;
        if (encryptor != null) {
            transformed = encryptor.decrypt(transformed);
        }
        if (compressor != null) {
            transformed = compressor.decompress(transformed, maxFrameLength);
        }
        ensureWireLength(transformed);
        return transformed;
    }

    private void ensureWireLength(byte[] payload) {
        if (maxFrameLength > 0 && payload.length > maxFrameLength) {
            throw new IllegalArgumentException("Transformed payload exceeds maximum frame length");
        }
    }

    private static final class CourierConfigNames {
        private static final String NONE = "none";
    }
}
