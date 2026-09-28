package yunqi.courier.common.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceMetadataTest {

    @Test
    void rejectsServiceKeyDelimitersInGroupAndVersion() {
        ServiceMetadata metadata = new ServiceMetadata();

        assertThrows(IllegalArgumentException.class, () -> metadata.setGroup("tenant:blue"));
        assertThrows(IllegalArgumentException.class, () -> metadata.setVersion("1.0\0preview"));
        assertThrows(IllegalArgumentException.class, () -> metadata.setGroup("  "));
    }
}
