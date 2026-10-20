package br.com.waver.catalog.bootstrap;

import br.com.waver.catalog.application.CatalogService;
import br.com.waver.catalog.domain.PriceBooks;
import br.com.waver.catalog.domain.PricingPolicies;
import br.com.waver.catalog.domain.Products;
import br.com.waver.catalog.domain.ResellerTiers;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication(scanBasePackages = "br.com.waver.catalog")
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }

    @Bean
    CatalogService catalogService(Products products, PriceBooks books, PricingPolicies policies, ResellerTiers tiers,
                                  CatalogService.Events events, PlatformTransactionManager transactions) {
        return new CatalogService(products, books, policies, tiers, events, new TransactionTemplate(transactions), Clock.systemUTC());
    }
}
