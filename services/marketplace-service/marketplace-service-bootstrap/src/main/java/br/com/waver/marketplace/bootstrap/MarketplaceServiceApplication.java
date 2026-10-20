package br.com.waver.marketplace.bootstrap;

import br.com.waver.marketplace.application.MarketplaceService;
import br.com.waver.marketplace.domain.Listings;
import br.com.waver.marketplace.domain.ResellerTiers;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication(scanBasePackages = "br.com.waver.marketplace")
public class MarketplaceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketplaceServiceApplication.class, args);
    }

    @Bean
    MarketplaceService marketplaceService(Listings listings, ResellerTiers tiers, MarketplaceService.CatalogDirectory catalog,
                                          MarketplaceService.Events events, PlatformTransactionManager transactions) {
        return new MarketplaceService(listings, tiers, catalog, events, new TransactionTemplate(transactions), Clock.systemUTC());
    }
}
