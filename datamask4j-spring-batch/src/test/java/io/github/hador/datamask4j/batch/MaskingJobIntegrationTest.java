package io.github.hador.datamask4j.batch;

import io.github.hador.datamask4j.annotations.Mask;
import io.github.hador.datamask4j.annotations.RecordMasker;
import io.github.hador.datamask4j.crypto.HmacPseudonymizer;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.hador.datamask4j.annotations.MaskType.*;
import static org.junit.jupiter.api.Assertions.*;

public class MaskingJobIntegrationTest {
    private static final byte[] KEY = new byte[32]; // Synthetic test key only.
    private static final List<Customer> SOURCE = List.of(
            new Customer("C-1", "one@example.test"), new Customer("C-2", "two@example.test"),
            new Customer("C-3", "three@example.test"), new Customer("C-4", "four@example.test"));

    public record Customer(@Mask(strategy = HMAC, scope = "customer:v1") String id,
                           @Mask(strategy = REDACT) String email) {}

    @Test
    void shouldCommitOnlyMaskedItemsInSuccessfulJob() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(BatchConfiguration.class)) {
            var execution = context.getBean(JobOperator.class).start(context.getBean(Job.class),
                    new JobParametersBuilder().addString("fixture", "success").toJobParameters());
            assertEquals(BatchStatus.COMPLETED, execution.getStatus());
            assertEquals(expectedRows(4), actualRows(context));
        }
    }

    @Test
    void shouldRollbackFailedChunkAndRestartWithoutDuplicates() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(BatchConfiguration.class)) {
            var operator = context.getBean(JobOperator.class);
            var control = context.getBean(FailureControl.class);
            control.failThird.set(true);

            var failed = operator.start(context.getBean(Job.class),
                    new JobParametersBuilder().addString("fixture", "restart").toJobParameters());
            assertEquals(BatchStatus.FAILED, failed.getStatus());
            assertTrue(control.failureInjected.get(), "Failure must occur after JDBC insert");
            assertEquals(expectedRows(2), actualRows(context));

            var restarted = operator.restart(failed);
            assertEquals(BatchStatus.COMPLETED, restarted.getStatus());
            assertEquals(expectedRows(4), actualRows(context));
        }
    }

    private static String token(String value) {
        return new HmacPseudonymizer(KEY, "customer:v1").transform(value);
    }

    private static List<Customer> expectedRows(int count) {
        return SOURCE.subList(0, count).stream()
                .map(customer -> new Customer(token(customer.id()), "[REDACTED]"))
                .sorted(Comparator.comparing(Customer::id)).toList();
    }

    private static List<Customer> actualRows(AnnotationConfigApplicationContext context) {
        return new JdbcTemplate(context.getBean(DataSource.class)).query(
                "SELECT id, email FROM masked_customer",
                (rows, index) -> new Customer(rows.getString(1), rows.getString(2)))
                .stream().sorted(Comparator.comparing(Customer::id)).toList();
    }

    static class FailureControl {
        final AtomicBoolean failThird = new AtomicBoolean();
        final AtomicBoolean failureInjected = new AtomicBoolean();
    }

    static class FixtureReader extends AbstractItemCountingItemStreamItemReader<Customer> {
        private int position;

        FixtureReader() {
            setName("syntheticCustomerReader");
            setSaveState(true);
        }

        @Override
        protected void doOpen() {
            position = 0;
        }

        @Override
        protected Customer doRead() {
            return position < SOURCE.size() ? SOURCE.get(position++) : null;
        }

        @Override
        protected void doClose() {
            position = 0;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableBatchProcessing
    @EnableJdbcJobRepository
    static class BatchConfiguration {
        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase dataSource() {
            var database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                    .setType(EmbeddedDatabaseType.H2)
                    .addScript("classpath:org/springframework/batch/core/schema-h2.sql")
                    .build();
            new JdbcTemplate(database).execute("CREATE TABLE masked_customer (id VARCHAR(47) PRIMARY KEY, email VARCHAR(100) NOT NULL)");
            return database;
        }

        @Bean
        JdbcTransactionManager transactionManager(DataSource dataSource) {
            return new JdbcTransactionManager(dataSource);
        }

        @Bean
        FailureControl failureControl() {
            return new FailureControl();
        }

        @Bean
        Step maskStep(JobRepository repository, JdbcTransactionManager transactionManager,
                      DataSource dataSource, FailureControl control) {
            var jdbc = new JdbcTemplate(dataSource);
            return new StepBuilder("maskStep", repository)
                    .<Customer, Customer>chunk(2)
                    .transactionManager(transactionManager)
                    .reader(new FixtureReader())
                    .processor(new RecordMaskingItemProcessor<Customer>(new RecordMasker(KEY)))
                    .writer(chunk -> {
                        for (Customer customer : chunk) {
                            jdbc.update("INSERT INTO masked_customer(id, email) VALUES (?, ?)",
                                    customer.id(), customer.email());
                            if (token("C-3").equals(customer.id()) && control.failThird.compareAndSet(true, false)) {
                                control.failureInjected.set(true);
                                throw new IllegalStateException("Deliberate synthetic writer failure");
                            }
                        }
                    })
                    .build();
        }

        @Bean
        Job maskJob(JobRepository repository, Step maskStep) {
            return new JobBuilder("maskJob", repository).start(maskStep).build();
        }
    }
}
