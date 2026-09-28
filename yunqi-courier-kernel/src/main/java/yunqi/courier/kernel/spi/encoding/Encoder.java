package yunqi.courier.kernel.spi.encoding;

/** Converts text to bytes and back using a named character encoding. */
public interface Encoder {

    byte[] encode(String text);

    String decode(byte[] bytes);
}
