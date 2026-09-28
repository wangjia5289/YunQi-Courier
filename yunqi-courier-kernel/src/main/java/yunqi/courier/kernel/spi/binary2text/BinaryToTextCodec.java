package yunqi.courier.kernel.spi.binary2text;

/** Represents binary data in a text-safe form for metadata or diagnostics. */
public interface BinaryToTextCodec {

    String encode(byte[] bytes);

    byte[] decode(String text);
}
