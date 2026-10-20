package br.com.waver.identityaccess.domain;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Invitation of a person to an organization (RFC-0004: Membership INVITED, expires after 7 days by default).
 * One open invitation per organization and e-mail: inviting again returns the same invitation.
 */
public record Invitation(UUID id, OrgId orgId, OrgType orgType, String email, String firstName, String lastName,
                         String roleKey, String userId, Status status, Instant invitedAt, Instant expiresAt) {

    public enum Status { INVITED, ACCEPTED, EXPIRED, REVOKED }

    public static final Duration DEFAULT_VALIDITY = Duration.ofDays(7);

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Set<String> RESELLER_ROLES = Set.of("RESELLER_OWNER", "RESELLER_SALES", "RESELLER_FINANCE", "RESELLER_SUPPORT");
    private static final Set<String> CUSTOMER_ROLES = Set.of("CUSTOMER_ADMIN", "CUSTOMER_OPERATOR");

    public Invitation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(orgId);
        Objects.requireNonNull(orgType);
        Objects.requireNonNull(email);
        Objects.requireNonNull(roleKey);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(invitedAt);
        Objects.requireNonNull(expiresAt);
    }

    /** A person to invite, validated before anything is created in the identity provider. */
    public record Invitee(String email, String firstName, String lastName, String roleKey) {

        public static Invitee of(OrgType orgType, String email, String firstName, String lastName, String roleKey) {
            String normalizedEmail = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
            if (!EMAIL.matcher(normalizedEmail).matches() || normalizedEmail.length() > 254) {
                throw new DomainException(DomainException.Kind.VALIDATION, "IDENTITY_EMAIL_INVALID", "E-mail address is invalid.");
            }
            String first = name(firstName);
            String last = name(lastName);
            boolean roleFits = switch (orgType) {
                case RESELLER -> RESELLER_ROLES.contains(roleKey);
                case CUSTOMER -> CUSTOMER_ROLES.contains(roleKey);
                case PLATFORM -> false; // WAVER staff accounts are created by WAVER IT, not by invitation (v1)
            };
            if (!roleFits) {
                throw new DomainException(DomainException.Kind.VALIDATION, "IDENTITY_ROLE_NOT_ALLOWED",
                        "Role " + roleKey + " cannot be given in a " + orgType + " organization.");
            }
            return new Invitee(normalizedEmail, first, last, roleKey);
        }

        private static String name(String value) {
            String v = value == null ? "" : value.strip().replaceAll("\\s+", " ");
            if (v.isEmpty() || v.length() > 100) {
                throw new DomainException(DomainException.Kind.VALIDATION, "IDENTITY_NAME_INVALID",
                        "First and last name must have between 1 and 100 characters.");
            }
            return v;
        }
    }

    public static Invitation create(OrgId orgId, OrgType orgType, Invitee invitee, String userId, Instant now) {
        return new Invitation(Ids.newId(), orgId, orgType, invitee.email(), invitee.firstName(), invitee.lastName(),
                invitee.roleKey(), userId, Status.INVITED, now, now.plus(DEFAULT_VALIDITY));
    }

    public boolean isOpen(Instant now) {
        return status == Status.INVITED && now.isBefore(expiresAt);
    }
}
