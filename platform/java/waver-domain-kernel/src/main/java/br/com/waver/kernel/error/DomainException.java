package br.com.waver.kernel.error;

/**
 * Business rule violation with a stable machine code (`<CONTEXT>_<REASON>`, STD-008).
 * Mapped to RFC 9457 problem+json by the platform starter.
 */
public class DomainException extends RuntimeException {

    public enum Kind { VALIDATION, BUSINESS_RULE, NOT_FOUND, CONFLICT, FORBIDDEN }

    private final String code;
    private final Kind kind;

    public DomainException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public String code() {
        return code;
    }

    public Kind kind() {
        return kind;
    }
}
