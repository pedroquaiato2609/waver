package br.com.waver.kernel.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.waver.kernel.error.DomainException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class TaxIdTest {

    @Test
    void acceptsNumericCnpjWithPunctuation() {
        TaxId id = TaxId.of(TaxId.Type.CNPJ, "11.222.333/0001-81");
        assertThat(id.value()).isEqualTo("11222333000181");
        assertThat(id.formatted()).isEqualTo("11.222.333/0001-81");
    }

    @Test
    void acceptsAlphanumericCnpj() {
        // Example published by the Brazilian Federal Revenue for the alphanumeric CNPJ.
        TaxId id = TaxId.of(TaxId.Type.CNPJ, "12.abc.345/01de-35");
        assertThat(id.value()).isEqualTo("12ABC34501DE35");
    }

    @ParameterizedTest
    @ValueSource(strings = {"11.222.333/0001-82", "00.000.000/0000-00", "123", "12ABC34501DE3A"})
    void rejectsInvalidCnpj(String value) {
        assertThatThrownBy(() -> TaxId.of(TaxId.Type.CNPJ, value))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("TAX_ID_INVALID");
    }

    @Test
    void validatesCpf() {
        assertThat(TaxId.of(TaxId.Type.CPF, "529.982.247-25").formatted()).isEqualTo("529.982.247-25");
        assertThatThrownBy(() -> TaxId.of(TaxId.Type.CPF, "529.982.247-24")).isInstanceOf(DomainException.class);
    }
}
