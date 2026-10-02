# Coding Conventions — MOJ / CPP Standard

## Dependency Injection

- Constructor injection ONLY — NEVER use `@Autowired` on fields
- All injected fields MUST be `private final`
- Use Lombok `@RequiredArgsConstructor` OR an explicit constructor
- The application layer injects **port interfaces**, never adapter classes

```java
// CORRECT
private final ShareStore shareStore;
private final ApplicationResultsLookup applicationResultsLookup;

public IntakeService(ShareStore shareStore,
                     ApplicationResultsLookup applicationResultsLookup) {
    this.shareStore = shareStore;
    this.applicationResultsLookup = applicationResultsLookup;
}

// WRONG — never do this
@Autowired
private JdbcShareStore store;
```

(The class names above are examples, not decisions; the feature specs name the real types.)

## DTOs and Data Classes

- Java records for ALL value types — immutable by design
- **The payload stays as received.** Keep the exact text for storage and return; parse it into a
  Jackson tree (`tools.jackson`, Jackson 3) only to read the identity fields and extract the
  indexed columns. Never bind the whole hearing payload to a typed model, and never re-serialise a
  tree in place of the original text
- What this service *produces* (API responses) is typed records
- Use sealed interfaces for polymorphic types (e.g. intake outcomes)

## Error Handling

- Custom exceptions extending `RuntimeException`; classify each failure as retryable (database or
  progression unreachable: throw after the short capped pause, `min(2^deliveryCount s, 30 s)`, so
  the broker redelivers) or not a share (unreadable message, missing identity: record the receipt
  `UNREADABLE` / `NO_IDENTITY` with a bounded reason and the message text, and acknowledge). Never
  dead-letter from code; the broker's dead-letter queue is reached only after its own redelivery
  attempts
- **NEVER swallow exceptions.** No empty catch blocks, no catch-and-log-and-continue, no returning a
  success value from a catch block. Catch only to classify and rethrow, or to record an explicit
  outcome (a non-share reason on the receipt, `projection_status = FAILED`)
- The message listener is the only place that turns an exception into a broker decision
  (acknowledge or roll back)
- `@ControllerAdvice` / `ProblemDetail` for the HTTP API only. Responses carry bounded codes —
  never exception text, never payload content, never a value the caller supplied

## Messaging (Artemis, legacy integration only)

- One shared durable subscription (`resultsstore.publicevents.subscription`) with the broker-side
  selector; no client id; concurrency 1 per pod; transacted session
- Acknowledge only after the store transaction commits; a retryable failure rolls back
- Topic, subscription and selector come from configuration, never a string literal in a class
- Broker health never joins the readiness group
- Nothing is published on Artemis. New messaging uses Azure Service Bus

## Persistence

- Flyway migrations at `src/main/resources/db/migration/V<n>__<snake_case_description>.sql`
- Migrations are additive and forward-only; never edit a migration that has been applied in a
  shared environment
- Stored shares are immutable: no `UPDATE` of a share's facts or payload. The only updates are the
  latest pointer, the predecessor link and the day's youth flag, under the hearing-day lock; and
  the key-details and `projection_*` columns, by the extraction sweep alone, from the stored payload
- A share is inserted with `ON CONFLICT DO NOTHING` on its identity's unique key; nothing inserted
  means a duplicate delivery: the receipt is marked `DUPLICATE`, no error is raised or logged

## Enums and Routing

- Java enums for fixed value sets (projection status, receipt status, non-share reasons)
- Switch expressions for routing — the compiler enforces exhaustive coverage
- Include a `fromValue(String)` factory when parsing wire strings; unknown values are an explicit
  failure, never a silent default

## Logging

- SLF4J with Logback; `logback.xml` emits JSON unconditionally
- Lombok `@Slf4j` or `private static final Logger LOG = LoggerFactory.getLogger(...)` — the only
  allowed forms
- MDC on every message: `hearingId`, `hearingDay`, `sharedTime`, and `shareId` once known (cleared
  in a `finally`)
- NEVER use `System.out.println`, `System.err.println`, or `Throwable#printStackTrace()`
- NEVER log secrets, tokens, or connection strings
- **NEVER log personal data or payload content**, at any level. Identifiers only. Name a caught
  exception by its class; its message may carry text this service did not write

## Imports

- NEVER use wildcard imports (`import java.util.*`) — always explicit imports

## Naming Conventions

| Component        | Pattern            | Example (illustrative)           |
|------------------|--------------------|----------------------------------|
| Application svc  | `*Service`         | `IntakeService`                  |
| Port (interface) | capability noun    | `ShareStore`, `ApplicationResultsLookup` |
| Adapter          | `*Adapter` or technology prefix | `ProgressionApplicationAdapter`, `JdbcShareStore` |
| Event listener   | `*EventListener`   | `HearingResultedEventListener`   |
| Controller       | `*Controller`      | `SharesController`               |
| Response record  | `*Response`        | `ShareSummaryResponse`           |
| Exception        | `*Exception`       | `RetryableIntakeException`       |
| Config           | `*Config` / `*Properties` | `PublicEventsConfig`      |
| Test             | `*Test` / `*IT`    | `IntakeServiceTest`              |

## Testing Conventions

- JUnit 5 + Mockito + AssertJ for unit tests; `@ExtendWith(MockitoExtension.class)`
- `@Nested` classes with `@DisplayName` for grouped scenarios
- Method naming: `{action}_{scenario}_should_{expectation}`
- The application layer is tested with plain mocks — no Spring context
- Integration tests (suffix `*IT`): Testcontainers PostgreSQL (`support/PostgresTestSupport`) for
  the store and Flyway; embedded Artemis for the subscription; WireMock (`dynamicPort()`) for
  usersgroups and progression, asserting the exact CPP media types
- HTTP API: one case per endpoint that a caller without the admitted group is refused, one that a
  caller with it is served; a test that every mapped route has an action and an allow rule
- TDD: write the failing test first, see it fail for the right reason, then implement
- Logging in tests: SLF4J only
- Commands: `./gradlew test` runs the whole suite (unit and `*IT`); Docker must be running.
  `./gradlew build pmdMain pmdTest jacocoTestReport` is the merge gate. `pmdMain` is skipped unless
  named; `pmdTest` and the JaCoCo gate (0.88 line / 0.85 branch) run in `check`
