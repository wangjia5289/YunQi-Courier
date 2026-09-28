package yunqi.courier.binary2text.base64url;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class Base64UrlBinaryToTextCodecTest {

    @Test
    void shouldUseUrlSafeAlphabetWithoutPadding() {
        Base64UrlBinaryToTextCodec codec = new Base64UrlBinaryToTextCodec();
        byte[] input = {(byte) 0xfb, (byte) 0xff};
        String encoded = codec.encode(input);
        assertFalse(encoded.contains("+") || encoded.contains("/") || encoded.contains("="));
        assertArrayEquals(input, codec.decode(encoded));
    }
}
