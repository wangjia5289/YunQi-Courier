package yunqi.courier.compression.lz4;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Lz4CompressorTest {

    @Test
    void shouldRoundTripAndHonorExpansionLimit() {
        Lz4Compressor compressor = new Lz4Compressor();
        byte[] input = "重复数据重复数据重复数据".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(input, compressor.decompress(compressor.compress(input), input.length));
        assertThrows(RuntimeException.class, () -> compressor.decompress(compressor.compress(input), 1));
    }
}
