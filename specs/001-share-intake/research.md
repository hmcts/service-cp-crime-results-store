# Research: Share intake

**Feature**: `001-share-intake` | **Date**: 2026-10-02 | **Plan**: [plan.md](plan.md)

Each entry gives the decision, why, and what else was looked at. Entries R1 to R6 are the
transaction and database questions; R7 to R12 the identity and payload rules; R13 to R16 the
sweep, pause and metrics; R17 to R21 the build and test traps. Every open point from the
technical context is settled here; none is left for the implementer.

---

## R1. Transaction choreography

**Decision.** Keep `PublicEventsConfig` as it is: `sessionTransacted = true`, **no** JMS
transaction manager, concurrency 1, shared durable subscription. Each message runs two JDBC
transactions in sequence, each through its own `TransactionTemplate` over Boot's
`JdbcTransactionManager` (READ COMMITTED):

1. **No I/O first**: parse the identity, compute share id, checksum and shared days, and run the
   key-details extraction. Pure, in memory, cannot fail the share.
2. **Receipt transaction**: one upsert of the receipt row, commit.
3. If the message is not a share, or its receipt was already in an end state: return.
4. **Store transaction**: lock the day, insert, chain, mark the receipt, commit.
5. Fire metrics (after commit), return from the listener.
6. The listener container commits the JMS session. That commit **is** the acknowledgement.

Any exception thrown out of the listener makes the container roll the session back, and the
broker redelivers. There is no XA. This is "best-effort one-phase commit, database first": the
gap between the database commit and the JMS commit is covered by the receipt key (message id)
and the share's unique key, so a redelivery after a lost acknowledgement does no harm (US6
scenario 4).

**Rationale.** It matches how `DefaultMessageListenerContainer` works with a transacted session:
it calls `session.commit()` after a normal return and `session.rollback()` after a throw. Two
separate templates keep the receipt committed even when the store transaction rolls back, which is
what lets the attempt count survive (US6 scenario 1).

**Alternatives considered.**
- `ChainedTransactionManager`: deprecated in Spring Data and gives no more safety than the order
  above; it commits in reverse order and still has the same gap.
- `JmsTransactionManager` on the container: would make the JMS session the outer transaction and
  tempt nesting the JDBC work inside it; no gain.
- XA (Artemis + PostgreSQL two-phase commit): heavy, needs a transaction manager and prepared
  transactions on Azure Flexible Server; the unique keys already make redelivery harmless.
- One transaction for receipt and store: a store rollback would also lose the receipt and its
  attempt count, so R1 reconciliation could not see the failed attempts.

---

## R2. Bounding the store transaction (timeouts) and the PostgreSQL version

**Decision.** At the start of the store transaction (and of each sweep row transaction) set three
transaction-local timeouts with `SELECT set_config(name, value, true)`:

| Setting | Default | Why |
|---|---|---|
| `lock_timeout` | 10 s | gives up waiting for the hearing-day lock; below Hikari's 30 s socket timeout, so a lock wait ends as a clean PostgreSQL error, not a socket error |
| `statement_timeout` | 20 s | caps any one statement (the 2.4 MB payload insert included) |
| `idle_in_transaction_session_timeout` | 10 s | ends a transaction left open by a stalled JVM |

The Spring `TransactionTemplate` timeout (60 s) stays as the outer bound. `set_config(…, true)` is
the function form of `SET LOCAL`: the value ends with the transaction, so the next borrower of the
pooled connection gets the server defaults (proved by SC-009 / T010). The function form takes bind
parameters, so no SQL is built from strings.

**PostgreSQL version.** No terraform or infrastructure for the Results Store's Azure Flexible
Server exists in any repository under `~/moj`. The only Flexible Server terraform found is
`cpp-module-terraform-azurerm-aks-config/data.tf`, which looks servers up by resource group (for
pgAdmin) and fixes no version. The design verification notes list "PostgreSQL version" as **not
verified**. The legacy SIT single servers report PostgreSQL 11.22, which says nothing about a new
Flexible Server. Local and test runs use `postgres:16` (docker-compose and `PostgresTestSupport`).

So `transaction_timeout` (PostgreSQL 17 and later) is **deferred**. The three settings above work
on every version from 9.6, so 001 does not depend on the answer. To find the version once the
server exists, either:

- `az postgres flexible-server show --resource-group <rg> --name <server> --query version -o tsv`, or
- `SELECT current_setting('server_version_num');` over the service's own connection.

If it is 17 or later, a later change adds `transaction_timeout` beside the other three (one line
in `JdbcShareStore`, one property, one test).

**Alternatives considered.**
- Spring's timeout alone: it becomes a per-statement JDBC query timeout and a deadline check
  before each statement. It does not bound COMMIT, a lock wait that outlives the 30 s socket
  timeout ends as a socket error, and the time between statements is not bounded at all.
- Session-level `SET` (not `LOCAL`): leaks into the next transaction on the same pooled connection.
- A literal `SET LOCAL lock_timeout = '10s'` built from the property: string-built SQL; the
  function form is equivalent and safe.

---

## R3. Broker message id (JMSMessageID) and the fallback key

**Decision.** The receipt key is `JMSMessageID` as the client returns it (for Artemis a string
such as `ID:7a1b…`), stored as `TEXT`. If it is null, the key is `sha256:` followed by the
SHA-256 hex of the message text (of the empty string when there is no text), and the
`resultsstore.intake.message.id.missing` counter goes up (FR-005).

**Rationale.** JMS 2.0 (section 3.4.3) says the provider sets `JMSMessageID` on send unless the
producer called `setDisableMessageID(true)`, and a provider may ignore that hint. The Artemis JMS
client honours the hint, so a null id is possible, though no CPP producer is known to disable it.
The fallback means such a message is still recorded and never loops. Two identical texts without
an id share one receipt: the second is counted as a further attempt on the first, which is correct
because the same text is the same share. The integration test produces a null id by calling
`setDisableMessageID(true)` on its producer.

**Alternatives considered.** Throw on a null id (would end in the dead-letter queue and break
"never refuse"); a random UUID key (a redelivery would make a second receipt, so R1 could not
match them).

---

## R4. Share id: deterministic UUID

**Decision.** UUID version 5 (SHA-1, name-based, RFC 4122 section 4.3; RFC 9562 section 5.5)
over the UTF-8 bytes of `hearingId|hearingDay|sharedTime`, each part exactly as it appears in the
message. The namespace is a fixed constant in `ShareId`, never a property and never changed:

```text
3f6c2a4e-8d1b-4f0a-9c57-1e2b7d9a4c60
```

Golden vectors for `ShareIdTest` (computed with an independent implementation):

| Input | Share id |
|---|---|
| `6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a11\|2026-10-02\|2026-10-02T14:19:50.706Z` | `3ca3dfde-49ec-529d-abd7-76818b64f17c` |
| `6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a11\|2026-10-02\|2026-10-02T14:19:50.7060Z` | `7c8a5ca0-4a02-54b9-a230-bd29a009c962` |

The two rows show why FR-012 exists: the same instant spelt two ways gives two ids, so a duplicate
receipt must point at the **stored** share's id, looked up by (`hearing_id`, `hearing_day`,
`shared_at`), never at the freshly computed one.

**Rationale.** The JDK has only version 3 (`UUID.nameUUIDFromBytes`, MD5). Version 5 is a few
lines over `MessageDigest("SHA-1")`: take the first 16 bytes of SHA-1(namespace bytes + name
bytes), set the version nibble to 5 and the variant bits to `10`.

**Alternatives considered.** Version 3 (MD5; the standard prefers 5); the RFC URL namespace (a
shared namespace invites collisions with other systems' ids); a random id (not repeatable, so a
re-share would get a new id); hashing the parsed values (would hide the spelling difference the
spec wants handled explicitly).

---

## R5. Payload checksum

**Decision.** SHA-256 over the UTF-8 bytes of the stored text, written as 64 lower-case hex
characters (`HexFormat.of().formatHex`). The database checks the form
(`payload_sha256 ~ '^[0-9a-f]{64}$'`).

**Rationale.** Principle II and FR-016. The text is stored unchanged, so anyone can recompute it
(SC-008). Test vector: the empty string gives
`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`; `{"a":"é"}` gives
`b3a092a6af48807fa9482b2ee140105575daa26d5b24b3c0e60a7e2dee6683b1`.

**Alternatives considered.** A checksum over the parsed JSON (not reproducible from the text);
SHA-1 or MD5 (weak; the page says SHA-256).

---

## R6. London and UTC shared days

**Decision.** In Java: `sharedAt.atZone(ZoneId.of("Europe/London")).toLocalDate()` and
`sharedAt.atOffset(ZoneOffset.UTC).toLocalDate()`, both passed to the insert. Tests cover:

| `sharedTime` | London day | UTC day |
|---|---|---|
| `2026-06-30T23:30:00.000Z` (BST) | 2026-07-01 | 2026-06-30 |
| `2026-03-28T23:30:00.000Z` (GMT, the evening before the clocks go forward) | 2026-03-28 | 2026-03-28 |
| `2026-03-29T23:30:00.000Z` (first BST evening) | 2026-03-30 | 2026-03-29 |
| `2026-10-24T23:30:00.000Z` (last BST evening) | 2026-10-25 | 2026-10-24 |
| `2026-10-25T23:30:00.000Z` (GMT again) | 2026-10-25 | 2026-10-25 |

**Rationale.** A generated column using `AT TIME ZONE` is not allowed (the expression is not
immutable), and java.time carries the tz database with the JDK.

**Alternatives considered.** A trigger (logic hidden in the database); computing the day in the
read API later (spec 003 needs it indexed).

---

## R7. Reading the identity

**Decision.** `ShareIdentityParser` reads the body with the application's Jackson 3 mapper, with
`DeserializationFeature.FAIL_ON_TRAILING_TOKENS` switched on for this reader whatever the library
default is. Outcomes:

| Input | Receipt status | Reason code |
|---|---|---|
| not a `TextMessage` | `UNREADABLE` | `NOT_TEXT_MESSAGE` |
| text contains the character U+0000 | `UNREADABLE` | `NUL_CHARACTER` (text not kept, see R8) |
| not JSON, or trailing content | `UNREADABLE` | `NOT_JSON` |
| JSON but not an object | `UNREADABLE` | `NOT_OBJECT` |
| `hearing.id` missing / not a string | `NO_IDENTITY` | `MISSING_HEARING_ID` |
| `hearing.id` not a canonical UUID | `NO_IDENTITY` | `INVALID_HEARING_ID` |
| `hearingDay` missing / invalid | `NO_IDENTITY` | `MISSING_HEARING_DAY` / `INVALID_HEARING_DAY` |
| `sharedTime` missing / invalid | `NO_IDENTITY` | `MISSING_SHARED_TIME` / `INVALID_SHARED_TIME` |

The first failing field in the order `hearing.id`, `hearingDay`, `sharedTime` names the reason;
every part that did parse is still recorded on the receipt (FR-009).

Forms:
- **UUID**: must match `^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$` before
  `UUID.fromString`, because `UUID.fromString` accepts short forms such as `1-1-1-1-1`.
- **`hearingDay`**: `LocalDate.parse` (ISO `yyyy-MM-dd`, strict).
- **`sharedTime`**: `OffsetDateTime.parse` with `ISO_OFFSET_DATE_TIME`, then `toInstant()`. An
  RFC 3339 date-time needs an offset, so a value without one is `INVALID_SHARED_TIME`.
- **Year (both dates)**: exactly four ASCII digits, no sign, as the event schema's `yyyy` and RFC
  3339's `date-fullyear`. Java's ISO parsing also takes signed and longer years (`+999999999`) that
  PostgreSQL cannot hold, so those are invalid. Every four-digit year, 0000 to 9999, with any offset
  is accepted: the instants it can name run from year -1 to year 10000 UTC, well inside
  `timestamptz` (4713 BC to 294276 AD); `FlywayMigrationIT` proves the extremes through JDBC.

**Rationale.** FR-007 to FR-009. Trailing content must be refused here because PostgreSQL's
`jsonb` would refuse it later, inside the store transaction, and that would loop the message to
the dead-letter queue.

**Alternatives considered.** Validating against the event schema (Principle V forbids it);
binding to a typed model (the technical rules forbid it).

---

## R8. `\u0000`, raw NUL and the parsed `jsonb` copy

**Decision.**
- **Escaped `\u0000` inside JSON** (six characters): valid JSON that PostgreSQL `jsonb` refuses
  ("unsupported Unicode escape sequence"). Before the store transaction, Java checks the text for
  the escape (case-insensitive `\u0000`) and for an unpaired surrogate escape (`\uD800`–`\uDFFF`
  not in a valid pair), which `jsonb` also refuses. If either is found, `payload_json` is written
  as NULL and `resultsstore.intake.parsed.copy.skipped` goes up. The text is stored as it arrived
  (FR-015). The check may be over-cautious (for example a literal `\\u0000`); the only effect is
  an empty parsed copy, which nothing in 001 reads.
- **A raw U+0000 character** in the message: PostgreSQL `text` cannot hold it at all, in any
  column. JSON does not allow it unescaped, so such a body is never a share. It is recorded
  `UNREADABLE` with reason `NUL_CHARACTER` and **no** message text, so the receipt write itself
  cannot fail. This is a narrow addition to FR-008 ("with the message text"): the text cannot be
  stored, and storing a changed copy would misrepresent it.
- `payload_json` is filled with `CAST(:payload AS jsonb)` in the payload insert.
- **Escaped `\u0000` inside a string key detail** (`hearing.courtCentre.lja.ljaCode`,
  `hearing.jurisdictionType`): the parsed value holds U+0000, which the `text` key-detail column
  cannot hold, so the insert would fail on every delivery. The extractor fails the projection with
  `NUL_CHARACTER:<path>` (kind `nul_character`), so the share is stored `FAILED` with empty key
  details and is acknowledged. The id fields need canonical UUIDs, so a NUL there is already
  `INVALID_UUID`. The sweep does not retry it (the payload never changes), unless the extractor
  version is raised.

**Rationale.** Never refuse to store (Principle V). Without the pre-check, one such payload would
fail the store transaction on every delivery and end on the dead-letter queue.

**Alternatives considered.** `payload_json NOT NULL` (breaks "never refuse"); a savepoint around
the cast (works, but adds nested transactions for a case the pre-check already covers); dropping
the parsed copy (the page keeps it for R2 and rebuilds).

---

## R9. Duplicate detection: `ON CONFLICT DO NOTHING … RETURNING`

**Decision.** `INSERT INTO hearing_share (…) VALUES (…) ON CONFLICT (hearing_id, hearing_day,
shared_at) DO NOTHING RETURNING stored_seq, stored_at`. No row back means a duplicate: select the
existing `share_id` by the three values, mark the receipt `DUPLICATE` with it, commit. No
exception, no rollback.

**Rationale.** FR-014 and Principle VI. Inside a transaction, `ON CONFLICT DO NOTHING` returns no
row for a conflicting insert and the transaction carries on normally. Because the hearing-day lock
is already held, no other transaction can be inserting the same identity at the same moment, so
the insert never waits on a speculative insertion.

**Alternatives considered.** Catching the unique violation (aborts the transaction; would need a
savepoint); a separate idempotency table (the page rules it out); `DO UPDATE` as a no-op to get
`RETURNING` (writes a new row version for nothing and changes `xmin`).

---

## R10. The hearing-day lock under READ COMMITTED

**Decision.** First statement after the timeouts:

```sql
INSERT INTO hearing_day_head (hearing_id, hearing_day) VALUES (:h, :d) ON CONFLICT DO NOTHING;
SELECT latest_share_id, share_count, youth_seen
  FROM hearing_day_head WHERE hearing_id = :h AND hearing_day = :d FOR UPDATE;
```

**Rationale.** If two pods race on the first share of a day, the second pod's insert waits for the
first transaction to finish (a speculative-insert conflict), then does nothing; its `FOR UPDATE`
then waits for the row lock. Under READ COMMITTED every later statement takes a fresh snapshot, so
the second pod sees everything the first committed (US4 scenario 3). Lock order is the same
everywhere: day row, then that day's share rows, then the receipt row. Each transaction locks one
day row, so no deadlock cycle is possible.

**Alternatives considered.** `pg_advisory_xact_lock(hash)` (hash collisions serialise unrelated
days, and the day row is needed anyway); SERIALIZABLE (retries on serialisation failures that the
lock makes unnecessary); `SELECT … FOR UPDATE` alone (finds nothing to lock on the first share of
a day).

---

## R11. One latest per day, and the order of the switch

**Decision.** A partial unique index `ON hearing_share (hearing_id, hearing_day) WHERE is_latest`.
A new share is always **inserted with `is_latest = false`**. If it is the greatest `shared_at` of
the day: first `UPDATE` the old latest to false, then `UPDATE` the new share to true, then the day
row (latest share and share count in one statement).

**Rationale.** FR-022 and FR-023. The index is not deferrable, so it is checked at each statement:
setting the new one before clearing the old one would fail, and the message would loop. The index
turns a chain bug into a loud failure instead of two "latest" rows.

**Alternatives considered.** A deferrable unique constraint (constraints cannot have a `WHERE`
clause; only indexes can be partial); trusting the day row alone (no database guard).

---

## R12. `stored_at`: `clock_timestamp()` rather than `now()`

**Decision.** `stored_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()`. The receipt's
`first_received_at`, `last_received_at` and `settled_at` use `clock_timestamp()` too.

**Rationale.** `now()` is the start of the transaction, before any wait for the day lock; the lag
metric (`stored_at − shared_at`, FR-039) should include that wait. Spec 002's bound "commit ≤
stored_at + cap" still holds, because `clock_timestamp()` is never earlier than the start of the
transaction.

**Alternatives considered.** `now()` (understates lag under lock contention).

---

## R13. Sweep without a distributed lock

**Decision.** No ShedLock in 001. Each pod runs the sweep on its own dedicated scheduler thread.
A round:

1. Select candidates without locks: `projection_status = 'FAILED' AND (projection_version <
   :current OR (projection_reason LIKE 'UNEXPECTED%' AND projection_attempts < :maxAttempts))
   ORDER BY stored_seq LIMIT :batch`, remembering each row's `projection_attempts`.
2. For each row: read the stored `payload_text` (outside any transaction; it never changes) and
   extract.
3. In one transaction per row: timeouts, lock the day row `FOR UPDATE`, lock the share row
   `FOR UPDATE`, and re-check that it is still `FAILED` **with the attempts value seen in step 1**.
   If not, skip it (another pod got there first). Otherwise write the result: on success the
   key-detail columns, `OK`, version, attempts + 1, the defendant rows, then the youth recompute
   (R15); on failure the new reason, version and attempts + 1.
4. Catch a failure per row, count it, carry on (FR-037). Never catch `Throwable`.

**Rationale.** Correctness comes from the per-row re-check under the locks, not from who runs.
Checking the attempts value seen makes each row processed at most once per round even with
several pods (US5 scenario 5). Extraction is deterministic over an immutable payload, so a retry
cap (3, counting the intake attempt) and the version gate stop pointless repeats.

**Why not a session-level advisory lock.** `pg_try_advisory_lock` belongs to the database session.
With HikariCP the lock and the unlock can run on different pooled connections, so the unlock does
nothing and the lock stays held by a connection the pool keeps for ever. Only the transaction form
(`pg_advisory_xact_lock`) is safe, and it is not needed here.

**Alternatives considered.** ShedLock (YOT uses 7.10.1; a dependency and a table for a guarantee
the re-check already gives; spec 004 can add it if its status endpoint needs a "last run" row);
`FOR UPDATE SKIP LOCKED` on candidates (fine, but the day lock must be taken first to keep lock
order, which `SKIP LOCKED` on the share would invert).

---

## R14. The capped pause before a redelivery

**Decision.** In the listener, when the intake throws (any `RuntimeException`), read
`JMSXDeliveryCount` (n, starting at 1), pause for `min(2^n seconds, cap)` (cap 30 s by
default), then rethrow the original exception. The pause goes through a small `Sleeper`
interface so unit tests use a recording fake. If the thread is interrupted while paused, the
interrupt flag is restored and the original exception is rethrown. A missing or unreadable
delivery count counts as 1. The pause is switched off in the `test` profile, except in the one
test that proves it.

**Rationale.** FR-045 and D2. SIT redelivers at once, 10 times, then dead-letters; the pause
spreads those 10 attempts over about three minutes (2 + 4 + 8 + 16 + 30 × 5 s), so a short
database outage does not dead-letter every share in flight. The listener is the one place that
turns an exception into a broker decision (technical rules).

**Alternatives considered.** Broker `redelivery-delay` on the subscription address (the better
long-term answer; a platform request, outside this repository); a retry loop in the store
(Principle VI forbids it); no pause (a short outage dead-letters messages).

---

## R15. Youth flags: three values, recomputed under the lock

**Decision.**
- Per share, `any_subject_is_youth`: TRUE if any `hearing.prosecutionCases[].defendants[].isYouth`
  is `true`; FALSE only if there is at least one defendant and every one states `false`; NULL if
  any defendant does not state it, if there are no defendants, or if extraction failed.
- Per day, recomputed after each insert and each successful sweep row:

```sql
SELECT bool_or(any_subject_is_youth) AS any_true,
       bool_or(any_subject_is_youth IS NULL) AS any_unknown
  FROM hearing_share WHERE hearing_id = :h AND hearing_day = :d;
```

  `youth_seen` = TRUE if `any_true`; else NULL if `any_unknown`; else FALSE.
- Then `UPDATE hearing_share SET day_youth_seen = :seen WHERE <day> AND day_youth_seen IS
  DISTINCT FROM :seen` (covers the new row and every older row when the day flag changes, D3),
  and update the day row's `youth_seen` when it changed.

**Rationale.** D1 and D3 with Sachin, and FR-026 to FR-028. TRUE stays TRUE by construction: a
share's TRUE never changes (only `FAILED` rows, whose value is NULL, are rewritten). "No
defendants" is NULL, not FALSE: nothing was stated. The read side will filter `IS NOT FALSE`, so
unknown keeps a day visible to a youth pull.

**Alternatives considered.** A sticky OR that treats a mix of false and absent as FALSE (could
hide a youth case); snapshotting `day_youth_seen` only at insert (rejected in D3).

**Consequence for spec 003.** Propagating `day_youth_seen` rewrites older rows, which changes
their `xmin`. A pull cursor must use `stored_seq`, never `xmin`.

---

## R16. Metrics: Micrometer, bounded tags, after commit

**Decision.** `application/IntakeObserver` is a port with one method per event. The Micrometer
adapter `config/MicrometerIntakeObserver` has no branches; the mapping from outcome to tag values
lives in `domain` enums (`IntakeOutcome`, `NonShareReason`, `ExtractionFailureKind`,
`SweepRowOutcome`, `IntakeFailureCause`), so JaCoCo measures it. The service calls the observer
after the template returns (after commit); a failure is counted after the rollback. Tag values
come only from those enums (contract: [contracts/metrics.md](contracts/metrics.md)).

`io.micrometer:micrometer-registry-prometheus` is added (version from the Boot BOM, as YOT
does): `management.endpoints.web.exposure.include` already lists `prometheus`, but the endpoint
does not exist without the registry. Export to Azure Monitor stays with the existing OpenTelemetry
starter.

**Rationale.** Principles VIII and XI; FR-038 to FR-040. Keeping Micrometer out of `application/`
keeps it framework-free (design rules).

**Alternatives considered.** Injecting `MeterRegistry` into the service (framework type in
`application/`); tags carrying the exception message or field path (unbounded or free text).

---

## R17. PMD 7.22 traps

**Decision.** Write to the single-exit shape from the start:
- `OnlyOneReturn` is **on** (codestyle; `.github/pmd-ruleset.xml` keeps it deliberately). Use an
  outcome variable, a `switch` expression over a sealed type, or `Optional` chains. Where an early
  return is clearly better, suppress at the site with a reason:
  `@SuppressWarnings("PMD.OnlyOneReturn") // reason`.
- `AvoidDuplicateLiterals`: SQL column names and status strings repeated in one class become
  constants or enum names; SQL lives in `private static final String` text blocks.
- `AvoidLiteralsInIfCondition`, `LinguisticNaming`, `UseExplicitTypes`: name constants; `is*`
  methods return boolean; no `var`.
- `AvoidCatchingThrowable` and `AvoidCatchingNPE` (errorprone) apply: the extractor catches
  `RuntimeException` only.
- `GuardLogStatement` is excluded; `AvoidCatchingGenericException` sits in `design.xml`, which is
  not enabled, so `catch (RuntimeException)` is allowed.

**Rationale.** SC-011. Fixing the shape late costs more than writing it right.

**Alternatives considered.** Excluding `OnlyOneReturn` (the ruleset comment forbids it and
`UnnecessaryWarningSuppression` would then fire).

---

## R18. JaCoCo: where branching code lives

**Decision.** The gate (0.88 line, 0.85 branch) excludes `Application` and `config/**`. So:
- every decision (outcome mapping, property rules that matter, chain choice, youth rule, pause
  maths) lives in `domain/`, `application/`, `persistence/` or `adapter/`;
- `config/` holds wiring and typed properties only; `ConfigurationValidationTest` still covers the
  property rules, because the technical rules require it;
- the extractor gets one parameterised case per field for "missing", "wrong type" and "invalid
  UUID", which are the branches most likely to sink the branch ratio;
- the chain gets cases for no predecessor, no successor, and both.

**Rationale.** `gradle/test.gradle` lines 46-49. ITs run in the one `test` task, so persistence
classes are measured through the Testcontainers suites.

**Alternatives considered.** Raising exclusions (thresholds only go up; exclusions would hide
logic).

---

## R19. Test infrastructure

**Decision.**
- **PostgreSQL**: `support/PostgresTestSupport` (exists, `postgres:16`, one container per JVM).
  Each suite cleans its own tables in `@BeforeEach` (truncate the five tables).
- **Artemis**: extract the embedded-broker code in `HearingResultedEventListenerIT` into
  `support/EmbeddedBrokerSupport` (start once per JVM, publish with `CPPNAME`, await the
  subscription, count messages left on it), following YOT's `DocumentEventListenerIT`. The broker
  gets `AddressSettings` for `public.event` with `maxDeliveryAttempts` 3 and a dead-letter address,
  so the persistent-failure test (US6 scenario 5) finishes quickly and can see the dead letter.
- **Two pods**: a second `DefaultMessageListenerContainer` on the same shared subscription, built
  in the test from the same factory settings (YOT's `ScaledPastOnePod`).
- **Commit failure after the database commit**: a `ConnectionFactory` wrapper whose sessions throw
  on the first `commit()`, so the real path (database committed, acknowledgement lost) is tested,
  not just a stopped container.
- **Overlap**: concurrency tests hold the day row with `SELECT … FOR UPDATE` on a second JDBC
  connection and release it on a latch, never by thread timing.
- **Fixtures**: `support/SampleShares` builds real-shaped bodies (identifiers only, synthetic
  values) from the SIT `results-shared-v3` shape.

**Rationale.** `failFast = true` in `gradle/test.gradle`: one flaky test stops the build.

**Alternatives considered.** A Testcontainers Artemis (slower, and the embedded broker already
works in the skeleton); `Thread.sleep` (flaky, and banned by the rules).

---

## R20. Latches, not sleeps

**Decision.** Waits use Awaitility (`await().atMost(…).until(…)`) on an observable condition, or
`CountDownLatch` around a held lock. No `Thread.sleep` in tests. The production pause (R14) is
tested through the `Sleeper` fake, and once for real with a 1 s cap.

**Rationale.** Speed and stability under `failFast`.

**Alternatives considered.** Fixed sleeps.

---

## R21. Logging context

**Decision.** The listener puts `messageId` in the MDC first, then `hearingId`, `hearingDay`,
`sharedTime` and `shareId` once the intake returns them, and clears every key in a `finally`. Log
lines carry ids, counts and timings only. A caught exception is named by its class; its message is
never logged (it may hold text from the payload). `NoPayloadInLogsIT` captures every log line
through all scenarios and asserts none contains a marker string planted in the payloads.

**Rationale.** Principle XI; FR-041; SC-010.

**Alternatives considered.** Logging the exception message (may carry payload text).

---

## R22. Small points settled while planning

- **`is_reshare`** is read with the key details (FR-018 lists it). If extraction fails it is
  NULL and the sweep fills it, which FR-044 allows ("the key-detail columns"). The Key Entities
  line that lists re-share as "fixed at insert" is read as "never changed once set"; flagged for
  `/speckit-analyze`.
- **No defendants** (no `prosecutionCases`): no defendant rows, extraction `OK`,
  `any_subject_is_youth` NULL (R15).
- **Sub-millisecond `sharedTime`**: `timestamptz` holds microseconds. Hearing sends milliseconds,
  so nothing is lost; a nanosecond value would be rounded, and two spellings differing only below
  a microsecond would be one share.
- **Duplicate JSON keys**: Jackson keeps the last value; the stored text keeps both, as sent.
- **Lag below zero** (a clock ahead of the store's): recorded as zero; Micrometer timers do not
  take negative durations.
- **Court centre code**: not in FR-018, so not a column in 001.
