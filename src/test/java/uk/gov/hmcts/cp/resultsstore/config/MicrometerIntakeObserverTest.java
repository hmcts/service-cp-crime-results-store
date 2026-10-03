package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.hmcts.cp.resultsstore.application.IntakeObserver;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionFailureKind;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionStage;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.domain.NonShareReason;
import uk.gov.hmcts.cp.resultsstore.domain.SweepRowOutcome;

/**
 * The metrics of contracts/metrics.md on a {@link SimpleMeterRegistry} (FR-038 to FR-040, SC-010):
 * every counter and the lag timer with exactly its tag sets, each event moving its own meter only, and
 * no tag value outside the contract's lists or shaped like an id or a date.
 */
@DisplayName("Micrometer intake observer")
class MicrometerIntakeObserverTest {

    private static final String RECEIVED = "resultsstore.intake.received";

    private static final String STORED = "resultsstore.intake.stored";

    private static final String NOT_SHARE = "resultsstore.intake.not.share";

    private static final String DUPLICATE = "resultsstore.intake.duplicate";

    private static final String ALREADY_SETTLED = "resultsstore.intake.already.settled";

    private static final String FAILED = "resultsstore.intake.failed";

    private static final String MESSAGE_ID_MISSING = "resultsstore.intake.message.id.missing";

    private static final String PARSED_COPY_SKIPPED = "resultsstore.intake.parsed.copy.skipped";

    private static final String EXTRACTION_FAILED = "resultsstore.extraction.failed";

    private static final String SWEEP_ROWS = "resultsstore.sweep.rows";

    private static final String LAG = "resultsstore.intake.lag";

    /** Every tag key the contract uses, with its allowed values. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "order", Set.of("in_order", "out_of_order"),
            "status", Set.of("unreadable", "no_identity"),
            "reason", Set.of("not_text_message", "nul_character", "not_json", "not_object", "missing_hearing_id",
                    "invalid_hearing_id", "missing_hearing_day", "invalid_hearing_day", "missing_shared_time",
                    "invalid_shared_time"),
            "stage", Set.of("receipt", "store", "intake", "sweep"),
            "cause", Set.of("lock_timeout", "statement_timeout", "database", "other"),
            "kind", Set.of("missing", "wrong_type", "invalid_uuid", "unstorable_text", "unexpected"),
            "outcome", Set.of("fixed", "failed_again", "skipped", "error"));

    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}");

    private static final Pattern DATE_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}|\\d{2}:\\d{2}");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final MicrometerIntakeObserver observer = new MicrometerIntakeObserver(registry);

    @Test
    void every_meter_should_be_registered_at_start_with_exactly_its_tag_sets() {
        final Map<String, Set<Map<String, String>>> registered = registry.getMeters().stream()
                .collect(Collectors.groupingBy(meter -> meter.getId().getName(),
                        Collectors.mapping(MicrometerIntakeObserverTest::tags, Collectors.toSet())));

        assertThat(registered).containsOnlyKeys(RECEIVED, STORED, NOT_SHARE, DUPLICATE, ALREADY_SETTLED, FAILED,
                MESSAGE_ID_MISSING, PARSED_COPY_SKIPPED, EXTRACTION_FAILED, SWEEP_ROWS, LAG);
        for (final String untagged : List.of(RECEIVED, DUPLICATE, ALREADY_SETTLED, MESSAGE_ID_MISSING,
                PARSED_COPY_SKIPPED)) {
            assertThat(registered.get(untagged)).as(untagged).containsExactly(Map.of());
        }
        final Set<Map<String, String>> orders = Set.of(Map.of("order", "in_order"), Map.of("order", "out_of_order"));
        assertThat(registered.get(STORED)).isEqualTo(orders);
        assertThat(registered.get(LAG)).isEqualTo(orders);
        assertThat(registered.get(NOT_SHARE)).isEqualTo(Arrays.stream(NonShareReason.values())
                .map(reason -> Map.of("status", reason.statusTag(), "reason", reason.tag()))
                .collect(Collectors.toSet()));
        assertThat(registered.get(NOT_SHARE)).contains(
                Map.of("status", "unreadable", "reason", "not_json"),
                Map.of("status", "no_identity", "reason", "missing_shared_time"));
        assertThat(registered.get(FAILED)).hasSize(8).contains(Map.of("stage", "store", "cause", "lock_timeout"),
                Map.of("stage", "receipt", "cause", "other"));
        assertThat(registered.get(EXTRACTION_FAILED)).hasSize(10)
                .contains(Map.of("stage", "intake", "kind", "invalid_uuid"), Map.of("stage", "sweep", "kind",
                        "unexpected"));
        assertThat(registered.get(SWEEP_ROWS)).containsExactlyInAnyOrder(Map.of("outcome", "fixed"),
                Map.of("outcome", "failed_again"), Map.of("outcome", "skipped"), Map.of("outcome", "error"));
        assertThat(registry.find(LAG).timers()).hasSize(2);
        assertThat(registry.find(STORED).counters()).hasSize(2);
    }

    static Stream<Arguments> events() {
        return Stream.of(
                event("received", IntakeObserver::received, RECEIVED),
                event("message id missing", IntakeObserver::messageIdMissing, MESSAGE_ID_MISSING),
                event("not a share", o -> o.notShare(NonShareReason.INVALID_SHARED_TIME), NOT_SHARE,
                        "status", "no_identity", "reason", "invalid_shared_time"),
                event("unreadable", o -> o.notShare(NonShareReason.NUL_CHARACTER), NOT_SHARE,
                        "status", "unreadable", "reason", "nul_character"),
                event("already settled", IntakeObserver::alreadySettled, ALREADY_SETTLED),
                event("stored in order", o -> o.stored(false, Duration.ofSeconds(1)), STORED, "order", "in_order"),
                event("stored out of order", o -> o.stored(true, Duration.ofSeconds(1)), STORED,
                        "order", "out_of_order"),
                event("duplicate", IntakeObserver::duplicate, DUPLICATE),
                event("parsed copy skipped", IntakeObserver::parsedCopySkipped, PARSED_COPY_SKIPPED),
                event("extraction failed at intake",
                        o -> o.extractionFailed(ExtractionStage.INTAKE, ExtractionFailureKind.WRONG_TYPE),
                        EXTRACTION_FAILED, "stage", "intake", "kind", "wrong_type"),
                event("extraction failed in the sweep",
                        o -> o.extractionFailed(ExtractionStage.SWEEP, ExtractionFailureKind.UNSTORABLE_TEXT),
                        EXTRACTION_FAILED, "stage", "sweep", "kind", "unstorable_text"),
                event("intake failed", o -> o.intakeFailed(IntakeStage.STORE, IntakeFailureCause.STATEMENT_TIMEOUT),
                        FAILED, "stage", "store", "cause", "statement_timeout"),
                event("receipt failed", o -> o.intakeFailed(IntakeStage.RECEIPT, IntakeFailureCause.DATABASE),
                        FAILED, "stage", "receipt", "cause", "database"),
                event("sweep row", o -> o.sweepRow(SweepRowOutcome.FAILED_AGAIN), SWEEP_ROWS,
                        "outcome", "failed_again"));
    }

    private static Arguments event(final String name, final Consumer<IntakeObserver> event, final String meter,
            final String... tags) {
        return Arguments.of(name, event, meter, Arrays.asList(tags));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("events")
    void event_should_move_its_own_counter_by_one_and_no_other(final String name,
            final Consumer<IntakeObserver> event, final String meter, final List<String> tags) {
        event.accept(observer);

        final Counter moved = registry.get(meter).tags(tags.toArray(String[]::new)).counter();
        assertThat(moved.count()).isEqualTo(1.0);
        final double others = registry.getMeters().stream().filter(Counter.class::isInstance)
                .map(Counter.class::cast)
                .filter(counter -> !counter.getId().equals(moved.getId()))
                .mapToDouble(Counter::count).sum();
        assertThat(others).isZero();
    }

    @Test
    void stored_share_should_record_its_lag_under_its_order() {
        observer.stored(true, Duration.ofMillis(1500));
        observer.stored(false, Duration.ZERO);

        final Timer outOfOrder = registry.get(LAG).tag("order", "out_of_order").timer();
        final Timer inOrder = registry.get(LAG).tag("order", "in_order").timer();
        assertThat(outOfOrder.count()).isEqualTo(1);
        assertThat(outOfOrder.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1500.0);
        assertThat(inOrder.count()).isEqualTo(1);
        assertThat(inOrder.totalTime(TimeUnit.MILLISECONDS)).isZero();
    }

    @Test
    void negative_lag_should_be_recorded_as_zero_not_dropped() {
        observer.stored(false, Duration.ofSeconds(-3));

        final Timer inOrder = registry.get(LAG).tag("order", "in_order").timer();
        assertThat(inOrder.count()).isEqualTo(1);
        assertThat(inOrder.totalTime(TimeUnit.MILLISECONDS)).isZero();
    }

    @Test
    void lag_beyond_the_nanosecond_range_should_be_recorded_not_thrown() {
        // A sharedTime of year 1600 is accepted (any four-digit year); its lag is past 292 years of nanoseconds.
        final Duration fourHundredYears = Duration.ofDays(365L * 400);

        observer.stored(false, fourHundredYears);

        final Timer inOrder = registry.get(LAG).tag("order", "in_order").timer();
        assertThat(inOrder.count()).isEqualTo(1);
        // The timer keeps nanoseconds and saturates there (about 292 years) rather than throwing.
        assertThat(inOrder.totalTime(TimeUnit.DAYS)).isGreaterThan(365.0 * 290);
    }

    @Test
    void no_tag_in_the_registry_should_hold_a_value_off_the_lists_an_id_or_a_date() {
        everyEventWithEveryValue();

        final List<Tag> tags = registry.getMeters().stream().map(Meter::getId)
                .flatMap(id -> id.getTags().stream()).toList();
        assertThat(tags).isNotEmpty().allSatisfy(tag -> {
            assertThat(ALLOWED).containsKey(tag.getKey());
            assertThat(ALLOWED.get(tag.getKey())).as(tag.getKey()).contains(tag.getValue());
            assertThat(UUID_SHAPE.matcher(tag.getValue()).find()).as(tag.getValue()).isFalse();
            assertThat(DATE_SHAPE.matcher(tag.getValue()).find()).as(tag.getValue()).isFalse();
        });
    }

    private void everyEventWithEveryValue() {
        observer.received();
        observer.messageIdMissing();
        observer.alreadySettled();
        observer.duplicate();
        observer.parsedCopySkipped();
        observer.stored(true, Duration.ofSeconds(2));
        observer.stored(false, Duration.ofSeconds(2));
        Arrays.stream(NonShareReason.values()).forEach(observer::notShare);
        Arrays.stream(SweepRowOutcome.values()).forEach(observer::sweepRow);
        for (final IntakeStage stage : IntakeStage.values()) {
            Arrays.stream(IntakeFailureCause.values()).forEach(cause -> observer.intakeFailed(stage, cause));
        }
        for (final ExtractionStage stage : ExtractionStage.values()) {
            Arrays.stream(ExtractionFailureKind.values()).forEach(kind -> observer.extractionFailed(stage, kind));
        }
    }

    private static Map<String, String> tags(final Meter meter) {
        return meter.getId().getTags().stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue));
    }
}
