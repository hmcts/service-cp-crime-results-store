# Quickstart: Operations API

How to build, run and check feature 004 locally. Docker must be running. The gate, the compose stack,
publishing a sample message and the phase gate are as in
[../001-share-intake/quickstart.md](../001-share-intake/quickstart.md) (§1, §3, §4, §7),
[../002-enrichment/quickstart.md](../002-enrichment/quickstart.md) and
[../003-read-api/quickstart.md](../003-read-api/quickstart.md); this file covers what 004 adds.

## 1. The gate

```bash
cd /home/sachin/moj/service-cp-crime-results-store
JAVA_HOME=/usr/lib/jvm/java-25-openjdk flock -w 7200 /tmp/resultsstore-gradle.lock \
  ./gradlew build pmdMain pmdTest jacocoTestReport
```

## 2. The tests that carry the contract

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk flock -w 7200 /tmp/resultsstore-gradle.lock \
  ./gradlew test --tests '*ResultsStoreRulesTest' --tests '*OpenApiDocumentTest' --tests '*OpenApiContractTest'
JAVA_HOME=/usr/lib/jvm/java-25-openjdk flock -w 7200 /tmp/resultsstore-gradle.lock \
  ./gradlew test --tests '*OperationsSchemaIT' --tests '*FlywayMigrationIT'
JAVA_HOME=/usr/lib/jvm/java-25-openjdk flock -w 7200 /tmp/resultsstore-gradle.lock \
  ./gradlew test --tests '*JdbcRerunRequestsIT' --tests '*RerunSweepIT' --tests '*RerunConcurrencyIT'
JAVA_HOME=/usr/lib/jvm/java-25-openjdk flock -w 7200 /tmp/resultsstore-gradle.lock \
  ./gradlew test --tests '*OperationsApiIT' --tests '*AuditIT'
```

- `OperationsSchemaIT` refuses each guard branch by name: an `OK` row rewritten with no pending item
  (`hearing_share_rerun_guard`), moved to `FAILED` (`hearing_share_projection_guard`), a `true` youth
  subject lowered (`hearing_share_youth_guard`), a version lowered or attempts not raised
  (`hearing_share_projection_version_guard`).
- `RerunSweepIT.two_sweeps_should_never_work_one_item_twice` runs two sweeps over one queue.
- `OperationsApiIT` seeds marker strings in `message_text`, `payload_text`, `payload_json` and a rerun
  reason, and checks no response body holds one.

## 3. Calling the API on the compose stack

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew bootJar
docker compose up -d --build
```

The compose app runs its sweep every few seconds (T010). Publish a share as in 001 quickstart §4, then
(`22222222-…` is the compose's synthetic "Second Line Support" caller, from
`docker/wiremock/mappings/identity-second-line-support.json`):

```bash
OPS=http://localhost:8082/operations
S='CJSCPPUID: 22222222-2222-4222-8222-222222222222'

curl -s -H "$S" "$OPS/extraction/status" | jq .
docker compose exec postgres psql -U resultsstore -d resultsstore -Atc \
  "SELECT hearing_id, hearing_day, share_id FROM hearing_share LIMIT 1"     # note the three values
curl -s -H "$S" "$OPS/receipts?hearingId=<hearing id>&hearingDay=<hearing day>" | jq .
curl -s -H "$S" "$OPS/reconciliation/daily?date=$(TZ=Europe/London date +%F)" | jq .
curl -s -H "$S" -H 'Content-Type: application/json' -X POST "$OPS/extraction/rerun" \
  -d '{"reason":"Local check of the rerun path","shareIds":["<share id>"]}' | jq .     # 202, matched 1
curl -s -H "$S" -H 'Content-Type: application/json' -X POST "$OPS/extraction/rerun" \
  -d '{"reason":"Local check, posted again","shareIds":["<share id>"]}' | jq .repeat  # true while open
```

Refusals:

```bash
curl -s -H 'CJSCPPUID: 00000000-0000-0000-0000-000000000000' "$OPS/extraction/status" | jq .  # 403: System Users
curl -s "$OPS/extraction/status" | jq .                                                          # 401
curl -s -H "$S" "$OPS/nope" | jq .                                                               # 404 route_not_found
curl -s -D - -o /dev/null -H "$S" "$OPS/extraction/rerun"                                        # 405, Allow: POST
curl -s -H "$S" -H 'Content-Type: text/plain' -X POST "$OPS/extraction/rerun" -d 'x' | jq .     # 415
```

Metrics:

```bash
curl -s localhost:8082/actuator/prometheus | grep -E 'resultsstore_operations_|resultsstore_sweep_rerun|sweep_round'
```

## 4. Watching a rerun

```bash
docker compose exec postgres psql -U resultsstore -d resultsstore -c \
  "SELECT r.status, i.state, i.outcome, i.attempts FROM extraction_rerun r
     JOIN extraction_rerun_item i USING (rerun_id) ORDER BY r.requested_at DESC LIMIT 5;"
docker compose exec postgres psql -U resultsstore -d resultsstore -c \
  "SELECT pod, extractor_version, finished_at, rows_rerun, last_worked_at FROM sweep_round;"
```

The item moves to `DONE` (outcome `UNCHANGED` for a share whose key details were already right), the
request to `DONE` at the next round end, and the share stays `OK` with the same `stored_seq`.

## 5. Phase gate

As 001 quickstart §7, with `specDir` `…/specs/004-operations-api` and the phase ranges of tasks.md
(*Phase-gate invocations*): A = T001–T003, B = T004–T006, C = T007–T009, D = T010–T011.
