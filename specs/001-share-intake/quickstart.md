# Quickstart: Share intake

How to build, run and check feature 001 locally. Docker must be running.

## 1. The gate

```bash
cd /home/sachin/moj/service-cp-crime-results-store
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew build pmdMain pmdTest jacocoTestReport
```

`build` runs every test (unit and `*IT`, Testcontainers `postgres:16` and the embedded Artemis
broker), PMD on test code, and the JaCoCo gate (0.88 line, 0.85 branch). `pmdMain` runs only when
named. When several agents or trees share the machine, put every Gradle call behind the shared
lock:

```bash
flock -w 7200 /tmp/resultsstore-gradle.lock ./gradlew build pmdMain pmdTest jacocoTestReport
```

## 2. Run one test

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew test --tests 'uk.gov.hmcts.cp.resultsstore.persistence.JdbcShareStoreIT'
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew test --tests '*IntakeIT.duplicate*'
```

`failFast` is on, so the first failure stops the run.

## 3. The compose stack

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew bootJar
docker compose up -d --build              # app, postgres, artemis, wiremock
curl -s localhost:8082/actuator/health/readiness
```

The Artemis console is at http://localhost:8161 (admin / admin); the subscription
`resultsstore-service.sdg` appears under the `public.event` address once the app is up.

## 4. Publish a sample message

Save a real-shaped body (identifiers only, synthetic values) as `/tmp/share.json`:

```json
{"_metadata":{"id":"0b1f5a8e-1c2d-4e3f-8a9b-0c1d2e3f4a5b","name":"public.events.hearing.hearing-resulted","createdAt":"2026-10-02T14:19:51.012Z"},
 "hearing":{"id":"6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a11","jurisdictionType":"MAGISTRATES",
   "courtCentre":{"id":"9d2e4f6a-1b3c-4d5e-8f70-a1b2c3d4e5f6","roomId":"1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d","lja":{"ljaCode":"2577"}},
   "prosecutionCases":[{"id":"c1c1c1c1-0000-4000-8000-000000000001",
     "defendants":[{"id":"d1d1d1d1-0000-4000-8000-000000000001","masterDefendantId":"e1e1e1e1-0000-4000-8000-000000000001","isYouth":false}]}]},
 "hearingDay":"2026-10-02","sharedTime":"2026-10-02T14:19:50.706Z","isReshare":false}
```

Publish it with the broker's own CLI, setting the `CPPNAME` property the selector filters on:

```bash
docker compose cp /tmp/share.json artemis:/tmp/share.json
docker compose exec artemis /var/lib/artemis-instance/bin/artemis producer \
  --url tcp://localhost:61616 --user admin --password admin \
  --destination topic://public.event --message-count 1 \
  --message "$(cat /tmp/share.json)" \
  --properties '[{"type":"string","key":"CPPNAME","value":"public.events.hearing.hearing-resulted"}]'
```

(If the image's `producer` does not offer `--properties`, check `artemis producer --help`; the
console's "Send message" form on the `public.event` address can set the same header.)

Publish it a second time (a new broker message id: expect `DUPLICATE`), then a body that is not
JSON, for example `--message "not json"` with the same property (expect `UNREADABLE`).

## 5. Inspect the tables

```bash
docker compose exec postgres psql -U resultsstore -d resultsstore -c \
  "SELECT message_id, status, attempts, reason, share_id FROM event_receipt ORDER BY first_received_at;"
docker compose exec postgres psql -U resultsstore -d resultsstore -c \
  "SELECT share_id, shared_at, is_latest, predecessor_share_id, projection_status, any_subject_is_youth, stored_seq FROM hearing_share;"
docker compose exec postgres psql -U resultsstore -d resultsstore -c \
  "SELECT share_id, text_bytes, payload_json IS NOT NULL AS parsed FROM hearing_share_payload;"
docker compose exec postgres psql -U resultsstore -d resultsstore -c "SELECT * FROM share_defendant;"
docker compose exec postgres psql -U resultsstore -d resultsstore -c "SELECT * FROM hearing_day_head;"
curl -s localhost:8082/actuator/prometheus | grep '^resultsstore_'
```

Expected after the three messages: receipts `STORED`, `DUPLICATE` (same `share_id`) and
`UNREADABLE`; one share (latest, no predecessor, `OK`); one payload; one defendant row; one day row
with `share_count = 1`.

The container smoke script does the same in CI (T015):

```bash
./scripts/container-smoke.sh
```

Stop the stack with `docker compose down` (add `--volumes` to drop the database).

## 6. Operations: the sweep schedule

The extraction sweep runs on its own thread. A round that throws a runtime failure is counted
(`resultsstore.sweep.rounds.failed`) and the next round runs as usual. A round that throws an
`Error` (for example `OutOfMemoryError`) ends the schedule on that pod for good: the log shows one
ERROR line, `Extraction sweep schedule ended`, with the class names, and the `sweepSchedule`
contributor of the liveness group goes `DOWN`:

```bash
curl -s localhost:8082/actuator/health/liveness
```

Recovery is a restart only, and Kubernetes does it through the liveness probe; nothing restarts
the schedule in place. Readiness is not affected, so the pod keeps taking traffic until it is
restarted.

## 7. Run a phase through the phase gate

Each phase of `tasks.md` is implemented and reviewed with the repository's workflow
`.claude/workflows/phase-gate.js`. Arguments (paths absolute):

```json
{
  "tree": "/home/sachin/moj/service-cp-crime-results-store",
  "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/001-share-intake",
  "tasks": ["T002", "T003", "T004"],
  "baseCommit": "<HEAD before the phase starts>",
  "codex": true
}
```

The phase ranges are in [plan.md](plan.md) ("Phase plan"): T002–T004, T005–T007, T008–T011,
T012–T015. `baseCommit` is the commit the phase starts from (for phase 1, the commit that adds
`tasks.md`). The gate runs `build pmdMain pmdTest jacocoTestReport` behind the shared lock, then
the code-reviewer, qa, spec-validator and Codex reviews, and remediates until all pass.
