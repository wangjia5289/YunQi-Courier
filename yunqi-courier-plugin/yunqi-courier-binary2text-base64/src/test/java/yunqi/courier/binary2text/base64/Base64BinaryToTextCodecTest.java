package yunqi.courier.binary2text.base64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class Base64BinaryToTextCodecTest {

    @Test
    void shouldRoundTripBytes() {
        Base64BinaryToTextCodec codec = new Base64BinaryToTextCodec();
        byte[] input = {0, 1, 2, -1};
        assertArrayEquals(input, codec.decode(codec.encode(input)));
    }
}
