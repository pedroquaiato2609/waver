package br.com.waver.kernel.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 generator (RFC 9562): 48-bit Unix epoch milliseconds, version 7, 74 random bits.
 * Time-ordered, so new rows land at the end of B-tree indexes (CONVENTIONS: all IDs are UUIDv7).
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        return newId(System.currentTimeMillis());
    }

    static UUID newId(long epochMillis) {
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        long msb = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16;
        msb |= 0x7000L;                                        // version 7
        msb |= ((random[0] & 0x0FL) << 8) | (random[1] & 0xFFL); // rand_a (12 bits)

        long lsb = 0x8000_0000_0000_0000L;                     // variant 10
        lsb |= (random[2] & 0x3FL) << 56;
        for (int i = 3; i < 10; i++) {
            lsb |= (random[i] & 0xFFL) << (8 * (9 - i));
        }
        return new UUID(msb, lsb);
    }
}
