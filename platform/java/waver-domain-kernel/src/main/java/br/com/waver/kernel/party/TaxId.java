package br.com.waver.kernel.party;

import br.com.waver.kernel.error.DomainException;
import java.util.Locale;
import java.util.Objects;

/**
 * Tax identifier of a company or person (RFC-0017: {@code taxIdType} + {@code taxId}). Stored normalized:
 * no punctuation, upper case. CNPJ accepts the alphanumeric format in force since July 2026 (12 letters or digits
 * plus 2 numeric check digits, RFB Normative Instruction 2,229/2024) as well as the numeric one.
 */
public record TaxId(Type type, String value) {

    public enum Type { CNPJ, CPF, FOREIGN }

    private static final int[] CNPJ_WEIGHTS_1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] CNPJ_WEIGHTS_2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    public TaxId {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }

    /** Parses user input (with or without punctuation) and validates the check digits. */
    public static TaxId of(Type type, String raw) {
        if (type == null) {
            throw invalid("Tax ID type is required.");
        }
        String input = raw == null ? "" : raw.strip();
        String normalized = switch (type) {
            case CNPJ, CPF -> input.replaceAll("[.\\-/\\s]", "").toUpperCase(Locale.ROOT);
            case FOREIGN -> input.replaceAll("\\s+", " ");
        };
        boolean valid = switch (type) {
            case CNPJ -> isValidCnpj(normalized);
            case CPF -> isValidCpf(normalized);
            case FOREIGN -> normalized.length() >= 3 && normalized.length() <= 40;
        };
        if (!valid) {
            throw invalid(switch (type) {
                case CNPJ -> "CNPJ is invalid.";
                case CPF -> "CPF is invalid.";
                case FOREIGN -> "Foreign tax ID must have between 3 and 40 characters.";
            });
        }
        return new TaxId(type, normalized);
    }

    /** CNPJ for display: AA.AAA.AAA/AAAA-DD. */
    public String formatted() {
        return switch (type) {
            case CNPJ -> value.substring(0, 2) + "." + value.substring(2, 5) + "." + value.substring(5, 8) + "/"
                    + value.substring(8, 12) + "-" + value.substring(12);
            case CPF -> value.substring(0, 3) + "." + value.substring(3, 6) + "." + value.substring(6, 9) + "-" + value.substring(9);
            case FOREIGN -> value;
        };
    }

    static boolean isValidCnpj(String v) {
        if (!v.matches("[0-9A-Z]{12}[0-9]{2}") || v.chars().distinct().count() == 1) {
            return false;
        }
        return checkDigit(v, CNPJ_WEIGHTS_1) == v.charAt(12) - '0' && checkDigit(v, CNPJ_WEIGHTS_2) == v.charAt(13) - '0';
    }

    /** Character value is its ASCII code minus 48, so digits keep their value and 'A' is 17 (RFB rule). */
    private static int checkDigit(String v, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (v.charAt(i) - '0') * weights[i];
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    static boolean isValidCpf(String v) {
        if (!v.matches("[0-9]{11}") || v.chars().distinct().count() == 1) {
            return false;
        }
        for (int digit = 9; digit <= 10; digit++) {
            int sum = 0;
            for (int i = 0; i < digit; i++) {
                sum += (v.charAt(i) - '0') * (digit + 1 - i);
            }
            int expected = (sum * 10) % 11 % 10;
            if (expected != v.charAt(digit) - '0') {
                return false;
            }
        }
        return true;
    }

    private static DomainException invalid(String message) {
        return new DomainException(DomainException.Kind.VALIDATION, "TAX_ID_INVALID", message);
    }
}
