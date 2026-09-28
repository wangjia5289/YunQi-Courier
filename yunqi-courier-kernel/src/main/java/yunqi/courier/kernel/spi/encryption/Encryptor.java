package yunqi.courier.kernel.spi.encryption;

/** Provides authenticated encryption for RPC payload bytes. */
public interface Encryptor {

    /** Supplies runtime key material when the plugin is configured by bootstrap. */
    default void configure(String key) {
    }

    byte[] encrypt(byte[] rawBytes);

    byte[] decrypt(byte[] encryptedBytes);
}
