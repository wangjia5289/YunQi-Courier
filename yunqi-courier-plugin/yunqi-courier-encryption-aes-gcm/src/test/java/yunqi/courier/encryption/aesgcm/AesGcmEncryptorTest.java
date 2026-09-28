package yunqi.courier.encryption.aesgcm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AesGcmEncryptorTest {

    @Test
    void shouldRoundTripWithRandomNonceAndRejectTampering() {
        AesGcmEncryptor encryptor = new AesGcmEncryptor();
        encryptor.configure(Base64.getEncoder().encodeToString(new byte[16]));
        byte[] input = "confidential".getBytes(StandardCharsets.UTF_8);

        byte[] first = encryptor.encrypt(input);
        byte[] second = encryptor.encrypt(input);
        assertNotEquals(Base64.getEncoder().encodeToString(first), Base64.getEncoder().encodeToString(second));
        assertArrayEquals(input, encryptor.decrypt(first));
        first[first.length - 1] ^= 1;
        assertThrows(RuntimeException.class, () -> encryptor.decrypt(first));
    }
}
