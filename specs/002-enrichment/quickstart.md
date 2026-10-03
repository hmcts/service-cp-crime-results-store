# Quickstart: Enrichment

How to build, run and check feature 002 locally. Docker must be running. The gate, the compose stack
and the 001 checks are as in [../001-share-intake/quickstart.md](../001-share-intake/quickstart.md);
this file covers what 002 adds.

## 1. The gate

```bash
cd /home/sachin/moj/service-cp-crime-results-store
JAVA_HOME=/usr/lib/jvm/java-25-openjdk flock -w 7200 /tmp/resultsstore-gradle.lock \
  ./gradlew build pmdMain pmdTest jacocoTestReport
```

## 2. The parity suite

Results' own fixtures, copied into `src/test/resources/progression/` (research R4):

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk flock -w 7200 /tmp/resultsstore-gradle.lock \
  ./gradlew test --tests 'uk.gov.hmcts.cp.resultsstore.application.ApplicationResultsParityTest'
```

Expected: six tests green (app1 enriched, app2 amendment fields removed, app3 no call, listed
unchanged, `200 {}` unchanged, several applications). A failure prints the JSONAssert difference by
JSON path, never the whole payload.

The contract tests for the client, one per row of the error table:

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk flock -w 7200 /tmp/resultsstore-gradle.lock \
  ./gradlew test --tests '*ProgressionApplicationClientTest'
```

## 3. The progression stub in compose

`docker/wiremock/mappings/progression-application.json` is loaded by the existing `wiremock` service,
which `CP_BASE_URL` already points at. It answers one synthetic application id with a `FINALISED`
application holding one result that carries the three amendment fields:

```json
{
  "request": {
    "method": "GET",
    "urlPath": "/progression-query-api/query/api/rest/progression/applications/a1a1a1a1-0000-4000-8000-000000000001",
    "headers": {
      "Accept": { "equalTo": "application/vnd.progression.query.application-only+json" },
      "CJSCPPUID": { "matches": "[0-9a-f-]{36}" }
    }
  },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/vnd.progression.query.application-only+json" },
    "jsonBody": {
      "courtApplication": {
        "id": "a1a1a1a1-0000-4000-8000-000000000001",
        "applicationStatus": "FINALISED",
        "judicialResults": [
          { "judicialResultId": "b1b1b1b1-0000-4000-8000-000000000001", "label": "Synthetic result",
            "amendmentDate": "2026-10-01", "amendmentReason": "Synthetic", "amendmentReasonId": "c1c1c1c1-0000-4000-8000-000000000009" }
        ]
      }
    }
  }
}
```

Any other application id gets WireMock's 404, so a mistyped id fails closed and shows up at once.
All values are synthetic.

Start the stack (the app's environment already has `CP_BASE_URL` and a synthetic
`RESULTS_STORE_SYSTEM_USER_ID`):

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew bootJar
docker compose up -d --build
curl -s localhost:8082/actuator/health/readiness
```

## 4. Publish a share with an application lacking results

Take the 001 sample (`/tmp/share.json`, 001 quickstart §4) and add, inside `hearing`, an application
with no `judicialResults`; give it a new `sharedTime` so it is a new share:

```json
"courtApplications":[{"id":"a1a1a1a1-0000-4000-8000-000000000001","applicationStatus":"LISTED"}]
```

Publish it as in 001 §4, then inspect:

```bash
docker compose exec postgres psql -U resultsstore -d resultsstore -c \
  "SELECT s.enrichment_applied,
          jsonb_array_length(p.payload_json->'hearing'->'courtApplications'->0->'judicialResults') AS results,
          p.payload_json->'hearing'->'courtApplications'->0->'judicialResults'->0 ? 'amendmentDate' AS amended,
          encode(sha256(convert_to(p.payload_text,'UTF8')),'hex') = s.payload_sha256 AS text_intact
     FROM hearing_share s JOIN hearing_share_payload p USING (share_id) ORDER BY s.stored_seq DESC LIMIT 1;"
curl -s -X POST localhost:8089/__admin/requests/count -d \
  '{"method":"GET","urlPathPattern":"/progression-query-api/query/api/rest/progression/applications/.*"}'
curl -s localhost:8082/actuator/prometheus | grep '^resultsstore_enrichment_'
```

Expected: `t | 1 | f | t`; a count of 1; `resultsstore_enrichment_applied_total 1.0` and
`resultsstore_enrichment_applications_total{outcome="enriched"} 1.0`.

Publish the same body again (a new broker message id): the receipt is `DUPLICATE`, the request count
stays 1 and `resultsstore_enrichment_skipped_total{reason="already_stored"}` is 1.

`scripts/container-smoke.sh` does this in CI (T009), alongside the three 001 cases.

## 5. Simulate progression failing (503, 404)

Add a higher-priority mapping through WireMock's admin API; publish a new share with the application
above; watch the receipt; then remove the mapping and watch it recover.

```bash
# 503 for that application (priority 1 beats the file mapping's default 5)
curl -s -X POST localhost:8089/__admin/mappings -d '{
  "priority": 1,
  "request": {"method":"GET","urlPath":"/progression-query-api/query/api/rest/progression/applications/a1a1a1a1-0000-4000-8000-000000000001"},
  "response": {"status": 503}
}'
# note the "id" it returns, publish the share, then:
docker compose exec postgres psql -U resultsstore -d resultsstore -c \
  "SELECT message_id, status, attempts FROM event_receipt ORDER BY last_received_at DESC LIMIT 1;"
curl -s localhost:8082/actuator/prometheus | grep 'resultsstore_intake_failed_total{.*enrich'
```

Expected while the mapping is there: the receipt `RECEIVED` with `attempts` rising after each pause
(`min(2^n s, 30 s)`), no new share, and `cause="progression_unavailable"` rising. Then:

```bash
curl -s -X DELETE localhost:8089/__admin/mappings/<id>
```

The next redelivery stores the share once, enriched; the receipt becomes `STORED`. If the broker's
attempts run out first, the message moves to the dead-letter address (Artemis console,
http://localhost:8161) with the receipt still `RECEIVED`.

For a 404 use `"status": 404`: the same path, counted `cause="progression_rejected"`. For a refused
user use 403 (`progression_refused`); for a contract fault a 200 with `"body": "<html>…</html>"`
(`progression_malformed`). Check the app's log holds none of the body:

```bash
docker compose logs app | grep -c '<html>'      # expected 0
```

## 6. Switching enrichment off

Add `RESULTSSTORE_ENRICHMENT_ENABLED: "false"` to the `app` service's `environment` in
`docker-compose.yml` (locally, not committed), then recreate it:

```bash
docker compose up -d app
```

Shares are stored un-enriched with no progression call, counted
`resultsstore_enrichment_skipped_total{reason="disabled"}` when they had an application needing
results. With enrichment on, the app refuses to start if `CP_BASE_URL` or
`RESULTS_STORE_SYSTEM_USER_ID` is blank; the start-up log names the property, not a value.

Stop the stack with `docker compose down` (add `--volumes` to drop the database).
