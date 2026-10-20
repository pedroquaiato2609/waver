package br.com.waver.customer.bootstrap;

import br.com.waver.customer.application.CustomerService;
import br.com.waver.customer.domain.Customers;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication(scanBasePackages = "br.com.waver.customer")
public class CustomerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerServiceApplication.class, args);
    }

    @Bean
    CustomerService customerService(Customers customers, CustomerService.Events events, CustomerService.TenantDirectory tenants,
                                    PlatformTransactionManager transactions) {
        return new CustomerService(customers, events, tenants, new TransactionTemplate(transactions), Clock.systemUTC());
    }
}
