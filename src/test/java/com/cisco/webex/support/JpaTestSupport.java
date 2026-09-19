package com.cisco.webex.support;

import com.cisco.webex.domain.messaging.MailboxRepository;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides an isolated Spring Data JPA context for tests that need a real
 * {@link MailboxRepository} bean without booting the full application. Each context
 * gets its own uniquely-named in-memory H2 database.
 */
public final class JpaTestSupport {

    private JpaTestSupport() {
    }

    @Configuration
    @EnableJpaRepositories(basePackageClasses = MailboxRepository.class)
    @EnableTransactionManagement
    static class JpaTestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            String dbName = "testdb-" + System.nanoTime();
            ds.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            ds.setPassword("");
            return ds;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(dataSource);
            emf.setPackagesToScan("com.cisco.webex.domain.messaging");
            JpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
            emf.setJpaVendorAdapter(vendorAdapter);
            Map<String, Object> props = new HashMap<>();
            props.put("hibernate.hbm2ddl.auto", "create-drop");
            emf.setJpaPropertyMap(props);
            return emf;
        }

        @Bean
        JpaTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
    }

    public static AnnotationConfigApplicationContext createContext() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(JpaTestConfig.class);
        ctx.refresh();
        return ctx;
    }

    public static MailboxRepository mailbox(AnnotationConfigApplicationContext ctx) {
        return ctx.getBean(MailboxRepository.class);
    }
}
