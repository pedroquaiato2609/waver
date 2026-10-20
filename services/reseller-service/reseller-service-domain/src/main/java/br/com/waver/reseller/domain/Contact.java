package br.com.waver.reseller.domain;

import br.com.waver.kernel.error.DomainException;
import java.util.Locale;
import java.util.regex.Pattern;

/** A reseller contact (RFC-0017 ResellerContact). Name, e-mail and phone are personal data (STD-009). */
public record Contact(Type type, String name, String email, String phone) {

    public enum Type { OWNER, COMMERCIAL, FINANCIAL, TECHNICAL, LEGAL }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ()-]{8,20}$");

    public static Contact of(Type type, String name, String email, String phone) {
        String n = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (n.length() < 2 || n.length() > 120) {
            throw new DomainException(DomainException.Kind.VALIDATION, "RESELLER_CONTACT_NAME_INVALID",
                    "Contact name must have between 2 and 120 characters.");
        }
        String e = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(e).matches()) {
            throw new DomainException(DomainException.Kind.VALIDATION, "RESELLER_CONTACT_EMAIL_INVALID", "Contact e-mail is invalid.");
        }
        String p = phone == null || phone.isBlank() ? null : phone.strip();
        if (p != null && !PHONE.matcher(p).matches()) {
            throw new DomainException(DomainException.Kind.VALIDATION, "RESELLER_CONTACT_PHONE_INVALID", "Contact phone is invalid.");
        }
        return new Contact(type, n, e, p);
    }

    /** First and last name for the identity provider: the first word, and the rest (or the first word again). */
    public String firstName() {
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(0, space);
    }

    public String lastName() {
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(space + 1);
    }
}
