package br.com.waver.customer.domain;

import br.com.waver.kernel.error.DomainException;
import java.util.Locale;
import java.util.regex.Pattern;

/** CustomerContact (RFC-0017 §3). Personal data (STD-009): never in events or logs. */
public record CustomerContact(Type type, String name, String email, String phone) {

    public enum Type { ADMIN, FINANCIAL, TECHNICAL, LEGAL }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ()-]{8,20}$");

    public static CustomerContact of(Type type, String name, String email, String phone) {
        String n = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (n.length() < 2 || n.length() > 120) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CUSTOMER_CONTACT_NAME_INVALID",
                    "Contact name must have between 2 and 120 characters.");
        }
        String e = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(e).matches()) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CUSTOMER_CONTACT_EMAIL_INVALID", "Contact e-mail is invalid.");
        }
        String p = phone == null || phone.isBlank() ? null : phone.strip();
        if (p != null && !PHONE.matcher(p).matches()) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CUSTOMER_CONTACT_PHONE_INVALID", "Contact phone is invalid.");
        }
        return new CustomerContact(type, n, e, p);
    }
}
