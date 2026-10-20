package br.com.waver.reseller.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.waver.reseller.domain.ChecklistItem;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** The shipped activation policy: with catalog and marketplace live, every checklist item gates activation. */
class ActivationPolicyTest {

    @Test
    void catalogAndPricingItemsAreRequiredForActivation() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        EnumSet<ChecklistItem> required = EnumSet.noneOf(ChecklistItem.class);
        Arrays.stream(properties.getProperty("waver.reseller.activation.required-items").split(","))
                .map(String::strip).map(ChecklistItem::valueOf).forEach(required::add);
        assertThat(required).contains(ChecklistItem.CATALOG_ASSIGNED, ChecklistItem.PRICING_RULES_SET);
        assertThat(required).containsExactlyInAnyOrder(ChecklistItem.values());
    }
}
