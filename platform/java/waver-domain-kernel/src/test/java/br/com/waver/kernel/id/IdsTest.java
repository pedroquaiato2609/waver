package br.com.waver.kernel.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void generatesVersion7RfcVariantIds() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void idsAreOrderedByCreationTime() {
        UUID earlier = Ids.newId(1_700_000_000_000L);
        UUID later = Ids.newId(1_700_000_000_001L);
        assertThat(earlier.toString()).isLessThan(later.toString());
    }

    @Test
    void embedsTheTimestamp() {
        long millis = 1_760_000_000_123L;
        UUID id = Ids.newId(millis);
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(millis);
    }
}
