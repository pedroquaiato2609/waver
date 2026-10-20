package br.com.waver.kernel.tenancy;

import java.util.Objects;
import java.util.UUID;

/** Identifier of an Organization (PLATFORM, RESELLER or CUSTOMER). */
public record OrgId(UUID value) {

    public OrgId {
        Objects.requireNonNull(value, "value");
    }

    public static OrgId of(String value) {
        return new OrgId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
