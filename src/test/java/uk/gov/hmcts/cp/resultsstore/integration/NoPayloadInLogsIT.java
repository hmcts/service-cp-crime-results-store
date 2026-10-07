package uk.gov.hmcts.cp.resultsstore.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.resultsstore.adapter.publicevents.HearingResultedEventListener;
import uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException;
import uk.gov.hmcts.cp.resultsstore.config.PublicEventsConfig;
import uk.gov.hmcts.cp.resultsstore.persistence.JdbcReceiptStore;
import uk.gov.hmcts.cp.resultsstore.persistence.JdbcShareStore;
import uk.gov.hmcts.cp.resultsstore.support.EmbeddedBrokerSupport;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;
import uk.gov.hmcts.cp.resultsstore.support.ProgressionStub;
import uk.gov.hmcts.cp.resultsstore.support.SampleShares;

/**
 * Logs and metrics across a full intake run on the embedded broker and Postgres (FR-038 to FR-041,
 * SC-010, US7): a marker planted in every message never reaches a captured log line, through the
 * stored, duplicate, extraction-failure, unreadable and no-identity paths and two failure paths, a
 * lock timeout and a database error that quotes the failing row, which also run the listener
 * container's error handler. The counters move as the contract says, and the Prometheus endpoint
 * exposes them. With enrichment on against a progression stub, a marker in progression's answer (a
 * malformed or HTML 200, an HTML 500, a 403) reaches no log line either, nor does the system user id
 * (specs/002-enrichment FR-032, SC-008).
 */
@SpringBootTest(properties = {"resultsstore.publicevents.enabled=true", "resultsstore.intake.store.lock-timeout=1s"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("no payload in logs")
class NoPayloadInLogsIT {

    private static final String MARKER = "PAYLOAD-MARKER-5c8e1d";

    private static final String DAY = "2026-10-02";

    private static final int MAX_DELIVERY_ATTEMPTS = 2;

    private static final Duration WITHIN = Duration.ofSeconds(30);

    private static final String MARKER_FREE_SHARE = "test_marker_free_lja_code_ck";

    /** The store's synthetic system user, which no log line may hold. */
    private static final String SYSTEM_USER_ID = "7e57c0de-0000-4000-8000-000000000003";

    /** Started once for the JVM: the Spring context outlives this class and closes its listener later. */
    private static EmbeddedBrokerSupport broker;

    /** Started once for the JVM, before the context. */
    private static ProgressionStub progression;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcReceiptStore receiptStore;

    @Autowired
    private JdbcShareStore shareStore;

    private final ListAppender<ILoggingEvent> everyLine = new ListAppender<>();

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @DynamicPropertySource
    static void brokerAndStore(final DynamicPropertyRegistry registry) throws Exception {
        startTheBroker();
        registry.add("spring.artemis.broker-url", broker::url);
        PostgresTestSupport.register(registry);
        registry.add("resultsstore.enrichment.enabled", () -> "true");
        registry.add("resultsstore.progression.base-url", progression::baseUrl);
        registry.add("resultsstore.progression.system-user-id", () -> SYSTEM_USER_ID);
    }

    @BeforeEach
    void emptyTablesAwaitTheSubscriptionAndCaptureEveryLine() {
        jdbc.sql("TRUNCATE event_receipt, share_defendant, hearing_share_payload, hearing_share, hearing_day_head")
                .update();
        await().atMost(WITHIN).until(() -> broker.consumers() >= 1 && broker.inFlight() == 0);
        everyLine.start();
        root.addAppender(everyLine);
    }

    @AfterEach
    void stopCapturing() {
        root.detachAppender(everyLine);
        everyLine.stop();
    }

    @Test
    void full_intake_run_should_count_each_path_and_log_ids_but_never_the_message_text() throws SQLException {
        final double receivedBefore = count("resultsstore.intake.received");
        final UUID hearingId = UUID.randomUUID();
        final String share = SampleShares.share(hearingId, DAY, "2026-10-02T14:19:50.706Z", "false", MARKER);

        broker.publish(SampleShares.HEARING_RESULTED, share);
        awaitReceipts("STORED", 1);
        broker.publish(SampleShares.HEARING_RESULTED, share);
        awaitReceipts("DUPLICATE", 1);

        broker.publish(SampleShares.HEARING_RESULTED, SampleShares.share(UUID.randomUUID(), DAY,
                "2026-10-02T15:00:00.000Z", "false", MARKER).replace(SampleShares.COURT_CENTRE.toString(), MARKER));
        awaitReceipts("STORED", 2);

        broker.publish(SampleShares.HEARING_RESULTED, "not json " + MARKER);
        awaitReceipts("UNREADABLE", 1);
        broker.publish(SampleShares.HEARING_RESULTED,
                "{\"hearing\":{\"id\":\"" + UUID.randomUUID() + "\"},\"note\":\"" + MARKER + "\"}");
        awaitReceipts("NO_IDENTITY", 1);

        lockTimeoutUntilDeadLettered();
        rowQuotingFailureUntilDeadLettered();

        assertThat(count("resultsstore.intake.received") - receivedBefore).isEqualTo(9.0);
        assertThat(count("resultsstore.intake.stored", "order", "in_order")).isEqualTo(2.0);
        assertThat(count("resultsstore.intake.duplicate")).isEqualTo(1.0);
        assertThat(count("resultsstore.intake.not.share", "status", "unreadable", "reason", "not_json"))
                .isEqualTo(1.0);
        assertThat(count("resultsstore.intake.not.share", "status", "no_identity", "reason", "missing_hearing_day"))
                .isEqualTo(1.0);
        assertThat(count("resultsstore.extraction.failed", "stage", "intake", "kind", "invalid_uuid"))
                .isEqualTo(1.0);
        assertThat(count("resultsstore.intake.failed", "stage", "store", "cause", "lock_timeout"))
                .isEqualTo(MAX_DELIVERY_ATTEMPTS);
        assertThat(count("resultsstore.intake.failed", "stage", "store", "cause", "database"))
                .isEqualTo(MAX_DELIVERY_ATTEMPTS);
        assertThat(Optional.ofNullable(registry.find("resultsstore.intake.lag").tag("order", "in_order").timer())
                .map(Timer::count).orElse(0L)).isEqualTo(2L);

        final List<ILoggingEvent> lines = captured();
        assertThat(lines.stream().map(ILoggingEvent::getLoggerName).toList())
                .contains(PublicEventsConfig.class.getName(), HearingResultedEventListener.class.getName());
        // Named by logger and level only, so a failure never prints the text it found.
        assertThat(lines.stream().filter(line -> everythingIn(line).contains(MARKER))
                .map(line -> line.getLoggerName() + " " + line.getLevel()).toList())
                .as("loggers whose lines carry message text").isEmpty();
    }

    @Test
    void progression_answers_carrying_a_marker_should_log_neither_it_nor_the_system_user() {
        final List<String> bodies = List.of(
                "200|application/json|{\"courtApplication\": \"" + MARKER + "\" trailing",
                "200|text/html|<html><body>" + MARKER + "</body></html>",
                "500|text/html|<html><body>" + MARKER + "</body></html>",
                "403|application/json|{\"error\": \"" + MARKER + " access denied\"}");
        final double malformedBefore =
                count("resultsstore.intake.failed", "stage", "enrich", "cause", "progression_malformed");
        for (final String answer : bodies) {
            final String[] parts = answer.split("\\|", 3);
            final UUID applicationId = UUID.randomUUID();
            progression.answer(applicationId, aResponse().withStatus(Integer.parseInt(parts[0]))
                    .withHeader("Content-Type", parts[1]).withBody(parts[2]));
            final long deadBefore = broker.deadLetters();
            broker.publish(SampleShares.HEARING_RESULTED, SampleShares.shareWithApplication(UUID.randomUUID(), DAY,
                    "2026-10-02T18:00:00.000Z", applicationId.toString()));
            await().atMost(WITHIN).until(() -> broker.deadLetters() == deadBefore + 1);
            assertThat(progression.requestsFor(applicationId)).hasSize(MAX_DELIVERY_ATTEMPTS);
        }

        assertThat(count("resultsstore.intake.failed", "stage", "enrich", "cause", "progression_malformed")
                - malformedBefore).isEqualTo(2.0 * MAX_DELIVERY_ATTEMPTS);
        final List<ILoggingEvent> lines = captured();
        assertThat(lines.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("Progression lookup")).toList()).isNotEmpty();
        // Named by logger and level only, so a failure never prints the text it found.
        assertThat(lines.stream()
                .filter(line -> everythingIn(line).contains(MARKER) || everythingIn(line).contains(SYSTEM_USER_ID))
                .map(line -> line.getLoggerName() + " " + line.getLevel()).toList())
                .as("loggers whose lines carry a progression body or the system user").isEmpty();
    }

    /**
     * Serving a payload (the working copy, a share whose text held an escaped NUL among them) and the arrived
     * text on its own route (an enriched share among them, so the two bodies differ) logs no payload content at
     * any level: the root logger is at DEBUG while the payloads are fetched.
     */
    @Test
    void serving_a_payload_should_log_no_payload_marker_at_any_level() throws Exception {
        final List<UUID> shares = new ArrayList<>();
        for (final String note : List.of(MARKER, MARKER + " a\\u0000b")) {
            final String text = SampleShares.share(UUID.randomUUID(), DAY, "2026-10-02T19:00:00.000Z", "false", note);
            final String messageId = "ID:serve-" + shares.size();
            receiptStore.recordArrival(SampleShares.arrival(messageId, text));
            shareStore.store(SampleShares.request(messageId, text));
            shares.add(SampleShares.read(text).identity().shareId());
        }
        final String enriched = SampleShares.shareWithApplication(UUID.randomUUID(), DAY, "2026-10-02T19:00:00.000Z",
                UUID.randomUUID().toString()).replace("\"note\":\"\"", "\"note\":\"" + MARKER + "\"");
        receiptStore.recordArrival(SampleShares.arrival("ID:serve-enriched", enriched));
        shareStore.store(SampleShares.enrichedRequest("ID:serve-enriched", enriched, SampleShares.finalised(MARKER)));
        shares.add(SampleShares.read(enriched).identity().shareId());
        final Level level = root.getLevel();
        root.setLevel(Level.DEBUG);
        try {
            for (final UUID shareId : shares) {
                for (final String route : List.of("/payload", "/payload/arrived")) {
                    final String body = mockMvc.perform(get("/results-store/v1/shares/" + shareId + route))
                            .andReturn().getResponse().getContentAsString();
                    assertThat(body.contains(MARKER)).as("the payload is served on " + route).isTrue();
                }
            }
        } finally {
            root.setLevel(level);
        }

        final List<ILoggingEvent> lines = captured();
        assertThat(lines.stream().filter(line -> line.getLevel() == Level.DEBUG).count())
                .as("lines at DEBUG").isPositive();
        // Named by logger and level only, so a failure never prints the text it found.
        assertThat(lines.stream().filter(line -> everythingIn(line).contains(MARKER))
                .map(line -> line.getLoggerName() + " " + line.getLevel()).toList())
                .as("loggers whose lines carry payload text").isEmpty();
    }

    @Test
    void prometheus_endpoint_should_expose_the_intake_metrics() throws Exception {
        final String scrape = mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse()
                .getContentAsString();

        assertThat(scrape)
                .contains("resultsstore_intake_received_total")
                .contains("resultsstore_intake_stored_total{order=\"out_of_order\"}")
                .contains("resultsstore_intake_lag_seconds_count{order=\"in_order\"}")
                .contains("resultsstore_sweep_rows_total{outcome=\"failed_again\"}")
                .contains("resultsstore_extraction_failed_total{kind=\"unexpected\",stage=\"sweep\"}");
    }

    /** The day row held by another connection past the lock timeout on every delivery. */
    private void lockTimeoutUntilDeadLettered() throws SQLException {
        final UUID held = UUID.randomUUID();
        jdbc.sql("INSERT INTO hearing_day_head (hearing_id, hearing_day) VALUES (:hearingId, :day)")
                .param("hearingId", held).param("day", LocalDate.parse(DAY)).update();
        final long deadBefore = broker.deadLetters();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT 1 FROM hearing_day_head WHERE hearing_id = ? AND hearing_day = ? FOR UPDATE")) {
                lock.setObject(1, held);
                lock.setObject(2, LocalDate.parse(DAY));
                lock.executeQuery().close();
            }
            broker.publish(SampleShares.HEARING_RESULTED,
                    SampleShares.share(held, DAY, "2026-10-02T16:00:00.000Z", "false", MARKER));
            await().atMost(WITHIN).until(() -> broker.deadLetters() == deadBefore + 1);
            holder.rollback();
        }
    }

    /**
     * A database error whose detail quotes the failing row: a test-only CHECK on the share table refuses
     * an LJA code equal to the marker, and PostgreSQL's {@code Failing row contains (…)} detail then
     * holds the marker on every delivery. {@code NOT VALID}: rows already stored are not checked.
     */
    private void rowQuotingFailureUntilDeadLettered() {
        jdbc.sql("ALTER TABLE hearing_share ADD CONSTRAINT " + MARKER_FREE_SHARE
                + " CHECK (lja_code IS DISTINCT FROM '" + MARKER + "') NOT VALID").update();
        try {
            final String text = SampleShares.share(UUID.randomUUID(), DAY, "2026-10-02T17:00:00.000Z", "false",
                    MARKER).replace("\"ljaCode\":\"2577\"", "\"ljaCode\":\"" + MARKER + "\"");
            // The error this path meets does carry the text: shown here, through the wired store, and
            // never logged below.
            receiptStore.recordArrival(SampleShares.arrival("ID:direct", text));
            assertThatThrownBy(() -> shareStore.store(SampleShares.request("ID:direct", text)))
                    .isInstanceOf(RetryableIntakeException.class)
                    .hasStackTraceContaining("Failing row contains").hasStackTraceContaining(MARKER);
            final long deadBefore = broker.deadLetters();
            broker.publish(SampleShares.HEARING_RESULTED, text);
            await().atMost(WITHIN).until(() -> broker.deadLetters() == deadBefore + 1);
        } finally {
            jdbc.sql("ALTER TABLE hearing_share DROP CONSTRAINT " + MARKER_FREE_SHARE).update();
        }
    }

    private void awaitReceipts(final String status, final int count) {
        await().atMost(WITHIN).until(() -> jdbc.sql("SELECT count(*) FROM event_receipt WHERE status = :status")
                .param("status", status).query(Integer.class).single() == count);
    }

    /** Lines from other tests' threads are captured too; all of them are held to the rule. */
    private List<ILoggingEvent> captured() {
        synchronized (everyLine.list) {
            return List.copyOf(everyLine.list);
        }
    }

    /** The line, its arguments, its logging context and its whole throwable chain, as one string. */
    private static String everythingIn(final ILoggingEvent line) {
        final List<String> parts = new ArrayList<>();
        parts.add(line.getFormattedMessage());
        parts.add(String.valueOf(line.getMDCPropertyMap()));
        if (line.getArgumentArray() != null) {
            Arrays.stream(line.getArgumentArray()).map(String::valueOf).forEach(parts::add);
        }
        IThrowableProxy proxy = line.getThrowableProxy();
        for (int depth = 0; proxy != null && depth < 32; depth++) {
            parts.add(proxy.getClassName() + ": " + proxy.getMessage());
            Arrays.stream(proxy.getSuppressed()).map(IThrowableProxy::getMessage).forEach(parts::add);
            proxy = proxy.getCause();
        }
        return String.join("\n", parts);
    }

    private double count(final String name, final String... tags) {
        return Optional.ofNullable(registry.find(name).tags(tags).counter()).map(Counter::count).orElse(0.0);
    }

    private static synchronized void startTheBroker() throws Exception {
        if (broker == null) {
            broker = EmbeddedBrokerSupport.start("no-payload-in-logs-broker", MAX_DELIVERY_ATTEMPTS);
        }
        if (progression == null) {
            progression = ProgressionStub.start();
        }
    }
}
