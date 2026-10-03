# Quickstart: Read API

How to build, run and check feature 003 locally. Docker must be running. The gate, the compose stack,
publishing a sample message and the phase gate are as in
[../001-share-intake/quickstart.md](../001-share-intake/quickstart.md) (§1, §3, §4, §7) and
[../002-enrichment/quickstart.md](../002-enrichment/quickstart.md); this file covers what 003 adds.

## 1. The gate

```bash
cd /home/sachin/moj/service-cp-crime-results-store
JAVA_HOME=/usr/lib/jvm/java-25-openjdk flock -w 7200 /tmp/resultsstore-gradle.lock \
  ./gradlew build pmdMain pmdTest jacocoTestReport
```

From T003 every Spring context test needs the Testcontainers database, `AuthzIT` and
`ActuatorIntegrationTest` included.

## 2. The tests that carry the contract

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk flock -w 7200 /tmp/resultsstore-gradle.lock \
  ./gradlew test --tests '*ResultsStoreRulesTest' --tests '*OpenApiDocumentTest' --tests '*OpenApiContractTest'
JAVA_HOME=/usr/lib/jvm/java-25-openjdk flock -w 7200 /tmp/resultsstore-gradle.lock \
  ./gradlew test --tests '*JdbcShareQueriesIT' --tests '*ReadQueriesPlanIT'
JAVA_HOME=/usr/lib/jvm/java-25-openjdk flock -w 7200 /tmp/resultsstore-gradle.lock \
  ./gradlew test --tests '*ReadApiIT' --tests '*AuditIT'
```

- The rule and OpenAPI tests prove every route has its action, its allow rule and its OpenAPI entry.
- `JdbcShareQueriesIT.a_slow_lower_number_should_never_be_overtaken` is the pull-safety race: two
  connections, an open insert at number *n* and a committed *n + 1*. With lag 0 the race shows; with the
  lag above the open transaction's age *n + 1* is withheld until *n* ends.
- `ReadQueriesPlanIT` prints nothing on success; on failure it names the index it expected and the plan
  node it found, never row content.

## 3. Calling the API on the compose stack

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew bootJar
docker compose up -d --build
```

The compose app runs with short intake timeouts (T012), so the derived lag is about 11 seconds rather
than 110. Publish a share as in 001 quickstart §4, wait for the lag, then (`00000000-…` is the compose's
synthetic "System Users" caller; any id the WireMock default mapping answers):

```bash
BASE=http://localhost:8082/results-store/v1
U='CJSCPPUID: 00000000-0000-0000-0000-000000000000'

curl -s -H "$U" "$BASE/shares?storedAfterSeq=0&limit=10" | jq .
SHARE=$(curl -s -H "$U" "$BASE/shares?storedAfterSeq=0" | jq -r '.items[0].shareId')
curl -s -H "$U" "$BASE/shares/$SHARE" | jq .
curl -s -D /tmp/h -o /tmp/body -H "$U" "$BASE/shares/$SHARE/payload"
grep -i '^etag\|^results-store' /tmp/h
sha256sum /tmp/body                       # equals the ETag without its quotes
ETAG=$(grep -i '^etag' /tmp/h | cut -d' ' -f2 | tr -d '\r')
curl -s -o /dev/null -w '%{http_code}\n' -H "$U" -H "If-None-Match: $ETAG" "$BASE/shares/$SHARE/payload"   # 304
```

Refusals:

```bash
curl -s "$BASE/shares?storedAfterSeq=0" | jq .                                  # 401 unauthenticated
curl -s -H 'CJSCPPUID: 11111111-1111-4111-8111-111111111111' "$BASE/shares/$SHARE" | jq .   # 403 forbidden (the no-group mapping)
curl -s -H "$U" "$BASE/anything" | jq .                                         # 404 route_not_found
curl -s -X OPTIONS -D - -o /dev/null -H "$U" "$BASE/shares"                      # 405, Allow: GET
curl -s -H "$U" -H 'Accept: application/vnd.results-store.get-share-payload+json' \
  "$BASE/shares?storedAfterSeq=0" -o /dev/null -w '%{http_code}\n'              # 200: the vendor Accept chose nothing
```

The no-group caller id is the one in `docker/wiremock/mappings/identity-no-group.json` (T012).

Metrics:

```bash
curl -s localhost:8082/actuator/prometheus | grep -E 'resultsstore_read_|visibility_overrun'
```

## 4. Watching the lag

```bash
docker compose exec postgres psql -U resultsstore -d resultsstore -c \
  "SELECT max(stored_seq), now() - max(stored_at) AS newest_age FROM hearing_share;"
```

A share whose age is below the lag is not yet in pull (unless a later-numbered share is older than the
lag, which cannot happen in a single-writer local stack). Each pull answer's `visibleUpTo` is the
database's clock minus the lag.

## 5. Phase gate

As 001 quickstart §7, with `specDir` `…/specs/003-read-api` and the phase ranges of tasks.md
(*Phase-gate invocations*): A = T001–T003, B = T004–T008, C = T009–T012, D = T013 (only if D-RAW is
accepted).
