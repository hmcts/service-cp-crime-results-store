package uk.gov.hmcts.cp.resultsstore.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.resultsstore.api.ProblemReason;
import uk.gov.hmcts.cp.resultsstore.application.ShareQueries.PullRows;
import uk.gov.hmcts.cp.resultsstore.application.ShareReadService.SearchRequest;
import uk.gov.hmcts.cp.resultsstore.domain.DayYouthFilter;
import uk.gov.hmcts.cp.resultsstore.domain.KeyDetails;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadChecksum;
import uk.gov.hmcts.cp.resultsstore.domain.ProjectionStatus;
import uk.gov.hmcts.cp.resultsstore.domain.SearchCursor;
import uk.gov.hmcts.cp.resultsstore.domain.ShareView;
import uk.gov.hmcts.cp.resultsstore.domain.StoredPayload;

/** The read service with plain mocks (no Spring). Payload assertions compare hashes or booleans. */
@ExtendWith(MockitoExtension.class)
@DisplayName("the read service")
class ShareReadServiceTest {

    private static final Duration LAG = Duration.ofSeconds(90);

    private static final UUID COURT = UUID.fromString("aaaaaaaa-0000-4000-8000-00000000000a");

    private static final Instant VISIBLE_UP_TO = Instant.parse("2026-10-03T17:58:30Z");


    @Mock
    private ShareQueries queries;

    @Mock
    private ReadObserver observer;

    private ShareReadService service() {
        return new ShareReadService(queries, observer, LAG);
    }

    @Nested
    @DisplayName("pull")
    class PullPages {

        @Test
        void pull_limit_should_default_to_100_and_refuse_0_and_501() {
            when(queries.pull(any(), eq(LAG))).thenReturn(new PullRows(VISIBLE_UP_TO, null, List.of()));

            service().pull(0, null, null, null);

            final ArgumentCaptor<PullQuery> query = ArgumentCaptor.forClass(PullQuery.class);
            verify(queries).pull(query.capture(), eq(LAG));
            assertThat(query.getValue()).isEqualTo(new PullQuery(0, 100, DayYouthFilter.ANY, null));
            assertRefused(() -> service().pull(0, 0, null, null), ProblemReason.LIMIT_OUT_OF_RANGE);
            assertRefused(() -> service().pull(0, 501, null, null), ProblemReason.LIMIT_OUT_OF_RANGE);
        }

        @Test
        void pull_should_accept_the_limits_1_and_500_and_pass_its_filters() {
            when(queries.pull(any(), eq(LAG))).thenReturn(new PullRows(VISIBLE_UP_TO, null, List.of()));

            service().pull(7, 1, DayYouthFilter.NOT_FALSE, COURT);
            service().pull(7, 500, DayYouthFilter.TRUE, null);

            final ArgumentCaptor<PullQuery> query = ArgumentCaptor.forClass(PullQuery.class);
            verify(queries, times(2)).pull(query.capture(), eq(LAG));
            assertThat(query.getAllValues()).containsExactly(new PullQuery(7, 1, DayYouthFilter.NOT_FALSE, COURT),
                    new PullQuery(7, 500, DayYouthFilter.TRUE, null));
        }

        @Test
        void a_negative_cursor_or_a_false_filter_should_be_refused_on_pull() {
            assertRefused(() -> service().pull(-1, null, null, null), ProblemReason.INVALID_STORED_AFTER_SEQ);
            assertRefused(() -> service().pull(0, null, DayYouthFilter.FALSE, null),
                    ProblemReason.INVALID_DAY_YOUTH_SEEN);
            verify(queries, never()).pull(any(), any());
        }

        @Test
        void has_more_should_be_true_only_when_limit_plus_one_rows_came_back() {
            when(queries.pull(any(), eq(LAG))).thenReturn(new PullRows(VISIBLE_UP_TO, 30L, views(11, 12, 13)));
            final PullPage more = service().pull(10, 2, null, null);

            when(queries.pull(any(), eq(LAG))).thenReturn(new PullRows(VISIBLE_UP_TO, 30L, views(11, 12)));
            final PullPage last = service().pull(10, 2, null, null);

            assertThat(more.hasMore()).isTrue();
            assertThat(more.items()).extracting(ShareView::storedSeq).containsExactly(11L, 12L);
            assertThat(last.hasMore()).isFalse();
            assertThat(last.items()).extracting(ShareView::storedSeq).containsExactly(11L, 12L);
        }

        @Test
        void next_stored_after_seq_should_be_the_last_item_when_there_is_more() {
            when(queries.pull(any(), eq(LAG))).thenReturn(new PullRows(VISIBLE_UP_TO, 90L, views(11, 15, 40)));

            assertThat(service().pull(10, 2, null, null).nextStoredAfterSeq()).isEqualTo(15);
        }

        @Test
        void next_stored_after_seq_should_be_the_greater_of_the_cursor_and_the_bound_otherwise() {
            when(queries.pull(any(), eq(LAG))).thenReturn(new PullRows(VISIBLE_UP_TO, 90L, views(11)));
            assertThat(service().pull(10, 5, null, COURT).nextStoredAfterSeq()).isEqualTo(90);

            when(queries.pull(any(), eq(LAG))).thenReturn(new PullRows(VISIBLE_UP_TO, 90L, List.of()));
            assertThat(service().pull(120, 5, null, null).nextStoredAfterSeq()).isEqualTo(120);
        }

        @Test
        void a_null_bound_should_keep_the_cursor() {
            when(queries.pull(any(), eq(LAG))).thenReturn(new PullRows(VISIBLE_UP_TO, null, List.of()));

            final PullPage page = service().pull(42, null, null, null);

            assertThat(page.nextStoredAfterSeq()).isEqualTo(42);
            assertThat(page.hasMore()).isFalse();
            assertThat(page.items()).isEmpty();
        }

        @Test
        void visible_up_to_should_be_passed_through() {
            when(queries.pull(any(), eq(LAG))).thenReturn(new PullRows(VISIBLE_UP_TO, 3L, views(1)));

            assertThat(service().pull(0, null, null, null).visibleUpTo()).isEqualTo(VISIBLE_UP_TO);
        }
    }

    @Nested
    @DisplayName("search")
    class SearchPages {

        @Test
        void search_day_form_should_accept_31_days_and_refuse_32_and_a_reversed_range() {
            when(queries.search(any())).thenReturn(List.of());

            service().search(days("2026-01-01", "2026-01-31"));
            service().search(days("2026-01-01", "2026-01-01"));

            assertRefused(() -> service().search(days("2026-01-01", "2026-02-01")), ProblemReason.DAY_RANGE_TOO_LONG);
            assertRefused(() -> service().search(days("2026-01-02", "2026-01-01")), ProblemReason.DAY_RANGE_REVERSED);
        }

        @Test
        void search_time_form_should_accept_31_days_and_refuse_31_days_and_a_microsecond_and_a_to_not_after_from() {
            when(queries.search(any())).thenReturn(List.of());
            final Instant from = Instant.parse("2026-01-01T00:00:00Z");

            service().search(timeForm(from, from.plus(Duration.ofDays(31))));
            service().search(timeForm(from, from.plusNanos(1000)));

            assertRefused(() -> service().search(timeForm(from, from.plus(Duration.ofDays(31)).plusNanos(1000))),
                    ProblemReason.TIME_RANGE_TOO_LONG);
            assertRefused(() -> service().search(timeForm(from, from)), ProblemReason.TIME_RANGE_REVERSED);
            assertRefused(() -> service().search(timeForm(from, from.minusSeconds(1))),
                    ProblemReason.TIME_RANGE_REVERSED);
        }

        @Test
        void an_instant_finer_than_a_microsecond_should_be_refused() {
            final Instant from = Instant.parse("2026-01-01T00:00:00Z");

            assertRefused(() -> service().search(timeForm(from.plusNanos(1), from.plusSeconds(1))),
                    ProblemReason.INVALID_SHARED_FROM);
            assertRefused(() -> service().search(timeForm(from, from.plusSeconds(1).plusNanos(1))),
                    ProblemReason.INVALID_SHARED_TO);
        }

        @Test
        void search_should_need_the_court_and_exactly_one_complete_form() {
            final LocalDate day = LocalDate.parse("2026-01-01");
            final Instant at = Instant.parse("2026-01-01T00:00:00Z");

            assertRefused(() -> service().search(new SearchRequest(null, day, day, null, null, null, null, null, null)),
                    ProblemReason.MISSING_PARAMETER);
            assertRefused(() -> service().search(new SearchRequest(COURT, day, null, null, null, null, null, null, null)),
                    ProblemReason.MISSING_PARAMETER);
            assertRefused(() -> service().search(new SearchRequest(COURT, null, null, null, at, null, null, null, null)),
                    ProblemReason.MISSING_PARAMETER);
            assertRefused(() -> service().search(new SearchRequest(COURT, day, day, at, null, null, null, null, null)),
                    ProblemReason.CONFLICTING_PARAMETERS);
            verify(queries, never()).search(any());
        }

        @Test
        void search_day_form_should_reach_the_query_as_london_midnight_instants() {
            when(queries.search(any())).thenReturn(List.of());

            service().search(new SearchRequest(COURT, LocalDate.parse("2026-10-03"), LocalDate.parse("2026-10-25"), null,
                    null, DayYouthFilter.FALSE, true, 7, null));

            final ArgumentCaptor<SearchQuery> query = ArgumentCaptor.forClass(SearchQuery.class);
            verify(queries).search(query.capture());
            assertThat(query.getValue()).isEqualTo(new SearchQuery(COURT, Instant.parse("2026-10-02T23:00:00Z"),
                    Instant.parse("2026-10-26T00:00:00Z"), DayYouthFilter.FALSE, true, null, 7));
        }

        @Test
        void search_limit_should_default_to_100_and_refuse_0_and_501() {
            when(queries.search(any())).thenReturn(List.of());
            final Instant from = Instant.parse("2026-01-01T00:00:00Z");

            service().search(timeForm(from, from.plusSeconds(60)));

            final ArgumentCaptor<SearchQuery> query = ArgumentCaptor.forClass(SearchQuery.class);
            verify(queries).search(query.capture());
            assertThat(query.getValue()).isEqualTo(new SearchQuery(COURT, from, from.plusSeconds(60), DayYouthFilter.ANY,
                    false, null, 100));
            assertRefused(() -> service().search(new SearchRequest(COURT, null, null, from, from.plusSeconds(1), null,
                    null, 0, null)), ProblemReason.LIMIT_OUT_OF_RANGE);
            assertRefused(() -> service().search(new SearchRequest(COURT, null, null, from, from.plusSeconds(1), null,
                    null, 501, null)), ProblemReason.LIMIT_OUT_OF_RANGE);
        }

        @Test
        void search_next_cursor_should_encode_the_last_item_and_be_null_on_the_last_page() {
            final List<ShareView> three = views(1, 2, 3);
            when(queries.search(any())).thenReturn(three);
            final Instant from = Instant.parse("2026-01-01T00:00:00Z");

            final SearchPage more = service().search(new SearchRequest(COURT, null, null, from, from.plusSeconds(60),
                    null, null, 2, null));
            final SearchPage last = service().search(new SearchRequest(COURT, null, null, from, from.plusSeconds(60),
                    null, null, 3, null));

            assertThat(more.items()).hasSize(2);
            assertThat(more.nextCursor()).isEqualTo(SearchCursor.after(three.get(1).sharedTime(), three.get(1).shareId())
                    .encode());
            assertThat(last.items()).hasSize(3);
            assertThat(last.nextCursor()).isNull();
        }

        /** A page ending on a share from before 1970 still gets a cursor the next request accepts (Codex R1). */
        @Test
        void a_page_ending_before_the_epoch_should_get_a_next_cursor_that_decodes() {
            final List<ShareView> three = views(Instant.parse("1969-12-31T23:59:00Z"), 1, 2, 3);
            when(queries.search(any())).thenReturn(three);
            final Instant from = Instant.parse("1969-12-31T00:00:00Z");

            final SearchPage page = service().search(new SearchRequest(COURT, null, null, from, from.plusSeconds(86_400),
                    null, null, 2, null));
            service().search(new SearchRequest(COURT, null, null, from, from.plusSeconds(86_400), null, null, 2,
                    page.nextCursor()));

            final ArgumentCaptor<SearchQuery> query = ArgumentCaptor.forClass(SearchQuery.class);
            verify(queries, times(2)).search(query.capture());
            assertThat(query.getAllValues().get(1).after())
                    .isEqualTo(SearchCursor.after(three.get(1).sharedTime(), three.get(1).shareId()));
        }

        @Test
        void a_cursor_should_reach_the_query_decoded() {
            when(queries.search(any())).thenReturn(List.of());
            final Instant from = Instant.parse("2026-01-01T00:00:00Z");
            final SearchCursor cursor = SearchCursor.after(from.plusSeconds(5), UUID.randomUUID());

            service().search(new SearchRequest(COURT, null, null, from, from.plusSeconds(60), null, null, null,
                    cursor.encode()));

            final ArgumentCaptor<SearchQuery> query = ArgumentCaptor.forClass(SearchQuery.class);
            verify(queries).search(query.capture());
            assertThat(query.getValue().after()).isEqualTo(cursor);
        }

        @Test
        void a_bad_cursor_should_be_invalid_cursor() {
            final Instant from = Instant.parse("2026-01-01T00:00:00Z");

            assertRefused(() -> service().search(new SearchRequest(COURT, null, null, from, from.plusSeconds(60), null,
                    null, null, "not-a-cursor")), ProblemReason.INVALID_CURSOR);
            verify(queries, never()).search(any());
        }
    }

    @Nested
    @DisplayName("one share and the day's versions")
    class OneShareAndDay {

        @Test
        void an_unknown_share_should_be_share_not_found() {
            final UUID shareId = UUID.randomUUID();
            when(queries.share(shareId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().share(shareId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("share_not_found")
                    .satisfies(failure -> assertThat(((NotFoundException) failure).reason())
                            .isEqualTo(ProblemReason.SHARE_NOT_FOUND));
        }

        @Test
        void a_known_share_should_be_returned() {
            final ShareView view = views(5).getFirst();
            when(queries.share(view.shareId())).thenReturn(Optional.of(view));

            assertThat(service().share(view.shareId())).isEqualTo(view);
        }

        @Test
        void an_empty_day_should_be_hearing_day_not_found() {
            final UUID hearingId = UUID.randomUUID();
            final LocalDate day = LocalDate.parse("2026-10-02");
            when(queries.dayVersions(hearingId, day)).thenReturn(List.of());

            assertThatThrownBy(() -> service().dayVersions(hearingId, day))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("hearing_day_not_found")
                    .satisfies(failure -> assertThat(((NotFoundException) failure).getMessage())
                            .doesNotContain(hearingId.toString()));
        }

        @Test
        void a_day_with_shares_should_be_returned_in_the_order_read() {
            final List<ShareView> day = views(3, 1, 2);
            when(queries.dayVersions(any(), any())).thenReturn(day);

            assertThat(service().dayVersions(UUID.randomUUID(), LocalDate.parse("2026-10-02"))).isEqualTo(day);
        }
    }

    @Nested
    @DisplayName("payload")
    class Payload {

        @Test
        void payload_etag_should_be_the_quoted_sha256_of_exactly_the_bytes_returned() {
            final String body = "{\"name\":\"Zoë £ 𝄞\",\"n\":1.50}";
            final StoredPayload stored = payload(body);
            when(queries.payload(stored.shareId())).thenReturn(Optional.of(stored));

            final ServedPayload served = service().payload(stored.shareId());

            assertThat(served.etag()).isEqualTo("\"" + PayloadChecksum.sha256Hex(served.body()) + "\"")
                    .matches("^\"[0-9a-f]{64}\"$");
            assertThat(PayloadChecksum.sha256Hex(served.body())).as("served bytes hash")
                    .isEqualTo(PayloadChecksum.sha256Hex(body.getBytes(StandardCharsets.UTF_8)));
            assertThat(served.length()).isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
        }

        @Test
        void a_working_copy_should_be_served_as_read() {
            // The database already removed _metadata; the service neither parses nor rewrites the text.
            final String body = "{\"b\": 1, \"a\": {\"_metadata\": 2}}";
            final StoredPayload stored = payload(body);
            when(queries.payload(stored.shareId())).thenReturn(Optional.of(stored));

            final ServedPayload served = service().payload(stored.shareId());

            assertThat(PayloadChecksum.sha256Hex(served.body())).as("served bytes hash")
                    .isEqualTo(PayloadChecksum.sha256Hex(body));
        }

        @Test
        void the_served_payload_should_carry_the_stored_share_s_facts() {
            final StoredPayload working = payload("{}");
            when(queries.payload(working.shareId())).thenReturn(Optional.of(working));

            final ServedPayload servedWorking = service().payload(working.shareId());

            assertThat(servedWorking.shareId()).isEqualTo(working.shareId());
            assertThat(servedWorking.hearingId()).isEqualTo(working.hearingId());
            assertThat(servedWorking.hearingDay()).isEqualTo(working.hearingDay());
            assertThat(servedWorking.sharedTime()).isEqualTo(working.sharedTime());
            assertThat(servedWorking.enrichmentApplied()).isTrue();
        }

        @Test
        void an_unknown_payload_should_be_share_not_found() {
            final UUID shareId = UUID.randomUUID();
            when(queries.payload(shareId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().payload(shareId)).isInstanceOf(NotFoundException.class)
                    .hasMessage("share_not_found");
        }
    }

    @Nested
    @DisplayName("the observer")
    class Observer {

        @Test
        void page_items_and_payload_bytes_should_be_reported_to_the_observer() {
            when(queries.pull(any(), eq(LAG))).thenReturn(new PullRows(VISIBLE_UP_TO, 9L, views(1, 2, 3)));
            when(queries.search(any())).thenReturn(views(4));
            final StoredPayload stored = payload("{\"é\":1}");
            when(queries.payload(stored.shareId())).thenReturn(Optional.of(stored));
            final Instant from = Instant.parse("2026-01-01T00:00:00Z");

            service().pull(0, 2, null, null);
            service().search(timeForm(from, from.plusSeconds(1)));
            service().payload(stored.shareId());

            verify(observer).pageItems(2);
            verify(observer).pageItems(1);
            verify(observer).payloadBytes(8L);
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 3})
        void served_payloads_should_compare_by_their_bytes(final int copies) {
            final StoredPayload stored = payload("{}");
            when(queries.payload(stored.shareId())).thenReturn(Optional.of(stored));
            final List<ServedPayload> served = new ArrayList<>();
            for (int i = 0; i < copies; i++) {
                served.add(service().payload(stored.shareId()));
            }

            assertThat(served).allSatisfy(each -> {
                assertThat(each).isEqualTo(served.getFirst()).hasSameHashCodeAs(served.getFirst());
                assertThat(each.toString()).contains("bytes=2").doesNotContain("{}");
            });
            assertThat(served.getFirst()).isNotEqualTo(new Object());
        }
    }

    private static void assertRefused(final ThrowingCallable call, final ProblemReason reason) {
        assertThatThrownBy(call).isInstanceOf(BadParameterException.class)
                .hasMessage(reason.code())
                .satisfies(failure -> assertThat(((BadParameterException) failure).reason()).isEqualTo(reason));
    }

    private static SearchRequest days(final String from, final String to) {
        return new SearchRequest(COURT, LocalDate.parse(from), LocalDate.parse(to), null, null, null, null, null,
                null);
    }

    private static SearchRequest timeForm(final Instant from, final Instant to) {
        return new SearchRequest(COURT, null, null, from, to, null, null, null, null);
    }

    private static List<ShareView> views(final long... seqs) {
        return views(Instant.parse("2026-10-02T09:00:00Z"), seqs);
    }

    private static List<ShareView> views(final Instant base, final long... seqs) {
        final List<ShareView> views = new ArrayList<>();
        for (final long seq : seqs) {
            views.add(new ShareView(UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse("2026-10-02"),
                    base.plusSeconds(seq), seq, Instant.parse("2026-10-02T09:00:01Z"),
                    LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-02"),
                    new KeyDetails(COURT, null, "2577", "MAGISTRATES", false, false, null, false), false, false, true,
                    null, false, false, ProjectionStatus.OK, 1, Instant.parse("2026-10-02T09:00:01Z"), 1));
        }
        return views;
    }

    private static StoredPayload payload(final String body) {
        return new StoredPayload(UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse("2026-10-02"),
                Instant.parse("2026-10-02T09:00:00Z"), true, body);
    }
}
