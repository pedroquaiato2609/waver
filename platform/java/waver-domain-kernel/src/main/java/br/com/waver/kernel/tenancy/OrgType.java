package br.com.waver.kernel.tenancy;

/** Organization types of the B2B2B hierarchy: PLATFORM (Grupo WAVER) → RESELLER → CUSTOMER. */
public enum OrgType {
    PLATFORM,
    RESELLER,
    CUSTOMER;

    /** Which organization type may own (be the parent of) an organization of this type. */
    public boolean canBeChildOf(OrgType parent) {
        return switch (this) {
            case PLATFORM -> false;
            case RESELLER -> parent == PLATFORM;
            case CUSTOMER -> parent == RESELLER || parent == PLATFORM;
        };
    }
}
