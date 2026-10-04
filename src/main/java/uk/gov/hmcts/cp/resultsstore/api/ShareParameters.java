package uk.gov.hmcts.cp.resultsstore.api;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import uk.gov.hmcts.cp.resultsstore.domain.CanonicalUuid;
import uk.gov.hmcts.cp.resultsstore.domain.DayYouthFilter;
import uk.gov.hmcts.cp.resultsstore.filters.ApiRoute;
import uk.gov.hmcts.cp.resultsstore.filters.QueryParameterNames;

/**
 * The read API's strict parameter rules (contracts/read-api.md §2.2, §4; FR-002, FR-003, FR-011, FR-026, FR-027;
 * research R23 C3), checked on the raw query string and the path variables before Spring binds the generated
 * method's typed arguments. Spring would take {@code 1-1-1-1-1} as a UUID, an offset or nine fraction digits
 * as an instant, {@code yes} as a boolean, an empty {@code limit} as the default, and never see an unknown or
 * repeated name; these rules refuse each of them with its own bounded reason, and never echo a value.
 *
 * <p>The order is fixed: the path variables; then unknown names; repeated names; parameters of the other mode
 * or of both search forms ({@code conflicting_parameters}); a missing court or no complete search form
 * ({@code missing_parameter}); then each value, in the order of the contract's parameter tables. Ranges (a
 * reversed or too long range) and the cursor's content are the read service's rules.
 */
public final class ShareParameters {

    private static final String STORED_AFTER_SEQ = ApiRoute.STORED_AFTER_SEQ;

    private static final String LIMIT = "limit";

    private static final String DAY_YOUTH_SEEN = "dayYouthSeen";

    private static final String COURT_CENTRE_ID = "courtCentreId";

    private static final String SHARED_DAY_FROM = "sharedDayFrom";

    private static final String SHARED_DAY_TO = "sharedDayTo";

    private static final String SHARED_FROM = "sharedFrom";

    private static final String SHARED_TO = "sharedTo";

    private static final String LATEST_ONLY = "latestOnly";

    private static final String CURSOR = "cursor";

    private static final Set<String> DAY_FORM = Set.of(SHARED_DAY_FROM, SHARED_DAY_TO);

    private static final Set<String> TIME_FORM = Set.of(SHARED_FROM, SHARED_TO);

    /** Search's own parameters: with {@code storedAfterSeq} they conflict rather than being unknown. */
    private static final Set<String> SEARCH_ONLY =
            Set.of(SHARED_DAY_FROM, SHARED_DAY_TO, SHARED_FROM, SHARED_TO, LATEST_ONLY, CURSOR);

    private static final Set<String> PULL_NAMES = Set.of(STORED_AFTER_SEQ, LIMIT, DAY_YOUTH_SEEN, COURT_CENTRE_ID);

    private static final Set<String> SEARCH_NAMES = Set.of(COURT_CENTRE_ID, SHARED_DAY_FROM, SHARED_DAY_TO,
            SHARED_FROM, SHARED_TO, DAY_YOUTH_SEEN, LATEST_ONLY, LIMIT, CURSOR);

    private static final Pattern DIGITS = Pattern.compile("\\d{1,19}");

    private static final Pattern DATE_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** UTC with {@code Z} and at most six fraction digits: {@code shared_at} holds microseconds (research R9). */
    private static final Pattern INSTANT_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z");

    private static final Set<String> BOOLEANS = Set.of("true", "false");

    private static final long MAX_LIMIT = 500;

    /** Each value's rule, in the order the contract's tables give them; the pull rule for dayYouthSeen. */
    private static final Map<String, Rule> VALUE_RULES = valueRules();

    private ShareParameters() {
        // Static functions only.
    }

    /**
     * Checks a request on a read route.
     *
     * @param route         the route the request matched
     * @param rawQuery      the query string as sent, or {@code null}
     * @param pathVariables the route's path variables as Spring will bind them (decoded)
     * @return the first rule broken, or empty when the request may be bound
     */
    public static Optional<ProblemReason> check(final ApiRoute route, final String rawQuery,
            final Map<String, String> pathVariables) {
        final List<Parameter> parameters = parse(rawQuery);
        return pathVariable(pathVariables, "shareId", ProblemReason.INVALID_SHARE_ID, ShareParameters::uuid)
                .or(() -> pathVariable(pathVariables, "hearingId", ProblemReason.INVALID_HEARING_ID,
                        ShareParameters::uuid))
                .or(() -> pathVariable(pathVariables, "hearingDay", ProblemReason.INVALID_HEARING_DAY,
                        ShareParameters::date))
                .or(() -> unknown(route, parameters))
                .or(() -> repeated(parameters))
                .or(() -> conflicting(route, parameters))
                .or(() -> missing(route, parameters))
                .or(() -> values(route, parameters));
    }

    private static Optional<ProblemReason> pathVariable(final Map<String, String> variables, final String name,
            final ProblemReason reason, final Predicate<String> rule) {
        final String value = variables == null ? null : variables.get(name);
        return value == null || rule.test(value) ? Optional.empty() : Optional.of(reason);
    }

    private static Optional<ProblemReason> unknown(final ApiRoute route, final List<Parameter> parameters) {
        final Set<String> known = switch (route) {
            case PULL_SHARES -> union(PULL_NAMES, SEARCH_ONLY);
            case SEARCH_SHARES -> SEARCH_NAMES;
            default -> Set.of();
        };
        return parameters.stream().anyMatch(parameter -> parameter.name() == null || !known.contains(parameter.name()))
                ? Optional.of(ProblemReason.UNKNOWN_PARAMETER)
                : Optional.empty();
    }

    private static Optional<ProblemReason> repeated(final List<Parameter> parameters) {
        final Set<String> seen = new HashSet<>();
        return parameters.stream().allMatch(parameter -> seen.add(parameter.name()))
                ? Optional.empty()
                : Optional.of(ProblemReason.REPEATED_PARAMETER);
    }

    private static Optional<ProblemReason> conflicting(final ApiRoute route, final List<Parameter> parameters) {
        final Set<String> names = names(parameters);
        final boolean conflict = route == ApiRoute.PULL_SHARES
                ? names.stream().anyMatch(SEARCH_ONLY::contains)
                : route == ApiRoute.SEARCH_SHARES && anyOf(names, DAY_FORM) && anyOf(names, TIME_FORM);
        return conflict ? Optional.of(ProblemReason.CONFLICTING_PARAMETERS) : Optional.empty();
    }

    private static Optional<ProblemReason> missing(final ApiRoute route, final List<Parameter> parameters) {
        final Set<String> names = names(parameters);
        final boolean missing = route == ApiRoute.SEARCH_SHARES
                && (!names.contains(COURT_CENTRE_ID) || !names.containsAll(DAY_FORM) && !names.containsAll(TIME_FORM));
        return missing ? Optional.of(ProblemReason.MISSING_PARAMETER) : Optional.empty();
    }

    private static Optional<ProblemReason> values(final ApiRoute route, final List<Parameter> parameters) {
        final Map<String, String> byName = new LinkedHashMap<>();
        parameters.forEach(parameter -> byName.put(parameter.name(), parameter.value()));
        return VALUE_RULES.entrySet().stream()
                .filter(entry -> byName.containsKey(entry.getKey()))
                .filter(entry -> !entry.getValue().accepts(route, byName.get(entry.getKey())))
                .map(entry -> entry.getValue().reason())
                .findFirst();
    }

    private static Map<String, Rule> valueRules() {
        final Map<String, Rule> rules = new LinkedHashMap<>();
        rules.put(STORED_AFTER_SEQ, new Rule(ProblemReason.INVALID_STORED_AFTER_SEQ,
                (route, value) -> longValue(value).isPresent()));
        rules.put(LIMIT, new Rule(ProblemReason.LIMIT_OUT_OF_RANGE,
                (route, value) -> longValue(value).filter(limit -> limit >= 1 && limit <= MAX_LIMIT).isPresent()));
        rules.put(DAY_YOUTH_SEEN, new Rule(ProblemReason.INVALID_DAY_YOUTH_SEEN,
                (route, value) -> DayYouthFilter.fromValue(value)
                        .filter(filter -> route != ApiRoute.PULL_SHARES || filter.allowedOnPull()).isPresent()));
        rules.put(COURT_CENTRE_ID, new Rule(ProblemReason.INVALID_COURT_CENTRE_ID, (route, value) -> uuid(value)));
        rules.put(SHARED_DAY_FROM, new Rule(ProblemReason.INVALID_SHARED_DAY, (route, value) -> date(value)));
        rules.put(SHARED_DAY_TO, new Rule(ProblemReason.INVALID_SHARED_DAY, (route, value) -> date(value)));
        rules.put(SHARED_FROM, new Rule(ProblemReason.INVALID_SHARED_FROM, (route, value) -> instant(value)));
        rules.put(SHARED_TO, new Rule(ProblemReason.INVALID_SHARED_TO, (route, value) -> instant(value)));
        rules.put(LATEST_ONLY, new Rule(ProblemReason.INVALID_LATEST_ONLY,
                (route, value) -> BOOLEANS.contains(value)));
        return Collections.unmodifiableMap(rules);
    }

    private static Optional<Long> longValue(final String value) {
        Optional<Long> parsed = Optional.empty();
        if (DIGITS.matcher(value).matches()) {
            try {
                parsed = Optional.of(Long.parseLong(value));
            } catch (NumberFormatException e) {
                // Nineteen digits above Long.MAX_VALUE: not a value the store holds; refused by the caller.
                parsed = Optional.empty();
            }
        }
        return parsed;
    }

    private static boolean uuid(final String value) {
        return CanonicalUuid.parse(value).isPresent();
    }

    private static boolean date(final String value) {
        boolean valid = DATE_SHAPE.matcher(value).matches();
        if (valid) {
            try {
                LocalDate.parse(value);
            } catch (DateTimeException e) {
                // A shape that is no calendar date (2026-02-30): refused by the caller.
                valid = false;
            }
        }
        return valid;
    }

    private static boolean instant(final String value) {
        boolean valid = INSTANT_SHAPE.matcher(value).matches();
        if (valid) {
            try {
                Instant.parse(value);
            } catch (DateTimeException e) {
                // A shape that is no instant (hour 24, February 30): refused by the caller.
                valid = false;
            }
        }
        return valid;
    }

    private static Set<String> names(final List<Parameter> parameters) {
        final Set<String> names = new HashSet<>();
        parameters.forEach(parameter -> names.add(parameter.name()));
        return names;
    }

    private static boolean anyOf(final Set<String> names, final Set<String> form) {
        return form.stream().anyMatch(names::contains);
    }

    private static Set<String> union(final Set<String> left, final Set<String> right) {
        final Set<String> all = new HashSet<>(left);
        all.addAll(right);
        return all;
    }

    /**
     * The {@code &}-separated pairs, decoded as the container decodes a query (UTF-8 escapes, {@code +} as a
     * space). An empty pair is skipped, as the container skips it; a name with a malformed escape is
     * {@code null}, so it is unknown; a malformed value is {@code null}, so no rule accepts it.
     */
    private static List<Parameter> parse(final String rawQuery) {
        final List<Parameter> parameters = new ArrayList<>();
        if (rawQuery != null) {
            for (final String pair : rawQuery.split("&")) {
                if (!pair.isEmpty()) {
                    final String[] parts = pair.split("=", 2);
                    parameters.add(new Parameter(decoded(parts[0]), parts.length > 1 ? decoded(parts[1]) : ""));
                }
            }
        }
        return parameters;
    }

    private static String decoded(final String raw) {
        return QueryParameterNames.decode(raw).orElse(null);
    }

    /** One decoded query pair. */
    private record Parameter(String name, String value) {
    }

    /** A value's rule and the reason it is refused with. */
    private record Rule(ProblemReason reason, ValueTest test) {

        /* default */ boolean accepts(final ApiRoute route, final String value) {
            return value != null && test.accepts(route, value);
        }
    }

    /** Whether a value is acceptable on a route. */
    @FunctionalInterface
    private interface ValueTest {

        boolean accepts(ApiRoute route, String value);
    }
}
