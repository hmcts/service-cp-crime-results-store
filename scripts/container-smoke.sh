#!/usr/bin/env bash
#
# Container smoke: build the image, run it against the committed compose dependencies, and require
# it to report readiness inside the 60-second budget. Then intake end to end (FR-047, SC-012):
# publish a real-shaped hearing-resulted message to the compose broker, the same message again and
# an unreadable one, and check the rows they leave. Then enrichment (spec 002 FR-039, SC-010): the
# share carries two court applications without results, which the progression stub in
# docker/wiremock/mappings/progression-application.json answers, and the working copy, the arrived
# text and the stub's request count are checked. Then the read API over HTTP (spec 003 FR-060), with
# CJSCPPUID as the gateway would send it: pull lists the share once the derived visibility lag (11 s with
# the compose intake timeouts) has passed; one share; the payload, whose SHA-256 equals its unquoted ETag
# and which holds no _metadata; If-None-Match 304; the text as it arrived (phase D: before enrichment, no
# _metadata, its SHA-256 equal to its ETag and not the stored checksum, 304); the day's versions; the bounded
# 401, 403 and 404 bodies; a vendor Accept still 200; and the read meters. Tears the stack down on every
# exit path, success or failure.
#
# This is the local equivalent of the "Container smoke" step in
# .github/workflows/ci-build-publish.yml; both run this same script, so the two cannot drift.
#
#   ./scripts/container-smoke.sh
#
# It proves the packaged artefact starts, answers and takes in a share through the real broker into
# the real database, which no JUnit suite can: the *IT suites run inside the build's JVM and would
# still pass if the image were unbuildable. Only docker compose, curl, jq and coreutils are needed on the
# host: the broker is driven by its own CLI and the database by its own psql, inside their containers.

set -euo pipefail

readonly READINESS_BUDGET_SECONDS=60
readonly DEPENDENCY_BUDGET_SECONDS=120
readonly READINESS_URL="http://localhost:8082/actuator/health/readiness"
readonly PROMETHEUS_URL="http://localhost:8082/actuator/prometheus"
readonly WIREMOCK_COUNT_URL="http://localhost:8089/__admin/requests/count"
readonly API_URL="http://localhost:8082/results-store/v1"
# The callers the compose usersgroups stub knows (docker/wiremock/mappings): any other id is in "System
# Users" (identity-stub.json, the default); these two are matched on CJSCPPUID.
readonly SYSTEM_USER="7a0c5b8e-1d2f-4e3a-9b6c-0d1e2f3a4b5c"
readonly SECOND_LINE_USER="22222222-2222-4222-8222-222222222222"
readonly NO_GROUP_USER="11111111-1111-4111-8111-111111111111"
# The derived lag is 11 s with the compose intake timeouts (docker-compose.yml); pull waits for it.
readonly PULL_BUDGET_SECONDS=45
# After readiness: for the listener to join the subscription, and for the three messages to settle.
readonly SUBSCRIPTION_BUDGET_SECONDS=30
readonly INTAKE_BUDGET_SECONDS=30

readonly ARTEMIS_CLI="/var/lib/artemis-instance/bin/artemis"
readonly TOPIC="public.event"
readonly SUBSCRIPTION="resultsstore-service.sdg"
readonly CPPNAME_PROPERTY='[{"type":"string","key":"CPPNAME","value":"public.events.hearing.hearing-resulted"}]'

# A real-shaped hearing-resulted body (contracts/inbound-event.md, quickstart.md section 4):
# identifiers only, synthetic values, no names or dates of birth. One case with two defendants, one
# of them a youth; sharedTime in milliseconds with a Z, as Hearing sends it.
readonly HEARING_ID="5e0c7a1d-3f2b-4c6d-8e9f-0a1b2c3d4e5f"
readonly HEARING_DAY="2026-10-02"
readonly SHARED_TIME="2026-10-02T14:19:50.706Z"
readonly CASE_ID="c2c2c2c2-0000-4000-8000-000000000002"
readonly ADULT_ID="d2d2d2d2-0000-4000-8000-000000000001"
readonly ADULT_MASTER_ID="e2e2e2e2-0000-4000-8000-000000000001"
readonly YOUTH_ID="d2d2d2d2-0000-4000-8000-000000000002"
readonly YOUTH_MASTER_ID="e2e2e2e2-0000-4000-8000-000000000002"
# Two court applications without results (spec 002). The stub answers the first FINALISED with one
# result carrying the three amendment fields, and the second 200 {} (not found). The first has no
# judicialResults key at all; the second has an empty array, which is looked up the same way.
readonly APP_ENRICHED_ID="a1a1a1a1-0000-4000-8000-000000000001"
readonly APP_NOT_FOUND_ID="a1a1a1a1-0000-4000-8000-000000000002"
# The compose app's synthetic system user (docker-compose.yml, RESULTS_STORE_SYSTEM_USER_ID).
readonly SYSTEM_USER_ID="00000000-0000-0000-0000-000000000000"
readonly PROGRESSION_PATH="/progression-query-api/query/api/rest/progression/applications"
readonly SHARE_BODY='{"_metadata":{"id":"7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","name":"public.events.hearing.hearing-resulted","createdAt":"2026-10-02T14:19:51.012Z","causation":["8b2c3d4e-5f6a-4b7c-9d8e-0f1a2b3c4d5e"],"stream":{"id":"5e0c7a1d-3f2b-4c6d-8e9f-0a1b2c3d4e5f","version":7},"context":{"user":"9c3d4e5f-6a7b-4c8d-ae9f-1a2b3c4d5e6f"}},'\
'"hearing":{"id":"'"$HEARING_ID"'","jurisdictionType":"MAGISTRATES","isSJPHearing":false,'\
'"courtCentre":{"id":"9d2e4f6a-1b3c-4d5e-8f70-a1b2c3d4e5f6","roomId":"1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d","lja":{"ljaCode":"2577"}},'\
'"prosecutionCases":[{"id":"'"$CASE_ID"'","defendants":['\
'{"id":"'"$ADULT_ID"'","masterDefendantId":"'"$ADULT_MASTER_ID"'","isYouth":false},'\
'{"id":"'"$YOUTH_ID"'","masterDefendantId":"'"$YOUTH_MASTER_ID"'","isYouth":true}]}],'\
'"courtApplications":[{"id":"'"$APP_ENRICHED_ID"'","applicationStatus":"LISTED"},'\
'{"id":"'"$APP_NOT_FOUND_ID"'","applicationStatus":"LISTED","judicialResults":[]}]},'\
'"hearingDay":"'"$HEARING_DAY"'","sharedTime":"'"$SHARED_TIME"'","isReshare":false,"shadowListedOffences":[]}'
readonly UNREADABLE_BODY="not json"

# A project name of this script's own. Everything it creates — containers, network, volumes — is
# namespaced under it, so the teardown's `down --volumes` can only ever destroy what this script
# made. Without it the script would share the default project with a developer's own
# `docker compose up`, and a smoke run would silently delete their database volume.
readonly PROJECT_NAME="resultsstore-smoke"

# The read API checks' response bodies and headers; removed by the teardown.
WORK_DIR="$(mktemp -d)"
readonly WORK_DIR

cd "$(dirname "${BASH_SOURCE[0]}")/.."

compose() {
  docker compose --project-name "$PROJECT_NAME" "$@"
}

log() {
  printf '[container-smoke] %s\n' "$1"
}

teardown() {
  # Captured first: everything below overwrites $?, and the script's real outcome must survive the
  # cleanup rather than be replaced by it.
  local status=$?

  rm -rf "$WORK_DIR"
  log "tearing down"
  if ! compose logs --no-color --tail 50 app; then
    log "WARNING: could not read the application container's logs"
  fi

  if ! compose down --volumes --remove-orphans; then
    log "FAIL: teardown left containers, networks or volumes behind"
    # A cleanup failure fails an otherwise green run: leftovers from this project poison the next
    # run, and a green tick over a stack that would not come down is a lie.
    if [ "$status" -eq 0 ]; then
      status=1
    fi
  fi

  exit "$status"
}
trap teardown EXIT

# Unconditional: the image is built from whatever sits in build/libs, and a jar left there by an
# earlier checkout or version would have this script smoke-testing code that is no longer in the tree.
# The old jars are removed first, so the image holds the one jar built here (docker/startup.sh refuses
# to start with more than one).
log "building the application jar"
rm -f build/libs/*.jar
./gradlew bootJar
jar_count=$(find build/libs -maxdepth 1 -name '*.jar' | wc -l)
if [ "$jar_count" -ne 1 ]; then
  log "FAIL: expected one application jar in build/libs, found ${jar_count}"
  exit 1
fi

log "starting dependencies"
compose up --detach postgres artemis wiremock

log "waiting for postgres to accept connections (budget ${DEPENDENCY_BUDGET_SECONDS}s)"
deadline=$((SECONDS + DEPENDENCY_BUDGET_SECONDS))
until [ "$(docker inspect --format '{{.State.Health.Status}}' \
    "$(compose ps --quiet postgres)")" = "healthy" ]; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    log "FAIL: postgres did not become healthy within ${DEPENDENCY_BUDGET_SECONDS}s"
    exit 1
  fi
  sleep 2
done

log "building the application image"
compose build app

log "starting the application container"
compose up --detach app

log "polling ${READINESS_URL} (budget ${READINESS_BUDGET_SECONDS}s)"
deadline=$((SECONDS + READINESS_BUDGET_SECONDS))
until curl --silent --fail --max-time 2 "$READINESS_URL" | grep -q '"status":"UP"'; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    log "FAIL: readiness did not report UP within ${READINESS_BUDGET_SECONDS}s"
    exit 1
  fi
  sleep 2
done

log "PASS: readiness reported UP within the ${READINESS_BUDGET_SECONDS}s budget"

artemis() {
  compose exec -T artemis "$ARTEMIS_CLI" "$@" --user admin --password admin --url tcp://localhost:61616
}

# One value from the store, tuples-only and unaligned; a SQL error fails the call. A boolean joined
# into text reads true / false.
query() {
  compose exec -T postgres psql -U resultsstore -d resultsstore -v ON_ERROR_STOP=1 -tA -c "$1"
}

publish() {
  artemis producer --destination "topic://$TOPIC" --message-count 1 --message "$1" \
    --properties "$CPPNAME_PROPERTY" > /dev/null
}

failures=0

# Compares one query's result with what is expected; a mismatch is reported and counted, so a run
# names every row that is wrong rather than only the first.
expect() {
  local what=$1 expected=$2 sql=$3 actual
  if ! actual=$(query "$sql"); then
    log "FAIL: ${what}: the query failed"
    failures=$((failures + 1))
  elif [ "$actual" != "$expected" ]; then
    log "FAIL: ${what}: expected '${expected}', found '${actual}'"
    failures=$((failures + 1))
  else
    log "ok: ${what}"
  fi
}

# Published before the subscription exists, a message on a topic reaches no one, so wait for it.
log "waiting for the listener on ${SUBSCRIPTION} (budget ${SUBSCRIPTION_BUDGET_SECONDS}s)"
deadline=$((SECONDS + SUBSCRIPTION_BUDGET_SECONDS))
# The broker escapes the dot in a shared subscription's queue name, so the name is matched up to it.
until artemis queue stat --field NAME --operation CONTAINS --value "${SUBSCRIPTION%%.*}" --json 2> /dev/null \
    | grep -Eq '"consumerCount":"[1-9]'; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    log "FAIL: no consumer on ${SUBSCRIPTION} within ${SUBSCRIPTION_BUDGET_SECONDS}s"
    exit 1
  fi
  sleep 1
done

log "publishing a share, the same share again, and an unreadable message"
publish "$SHARE_BODY"
publish "$SHARE_BODY"
publish "$UNREADABLE_BODY"

log "waiting for three settled receipts (budget ${INTAKE_BUDGET_SECONDS}s)"
deadline=$((SECONDS + INTAKE_BUDGET_SECONDS))
until [ "$(query "SELECT count(*) FROM event_receipt WHERE status <> 'RECEIVED'" 2> /dev/null)" = "3" ]; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    log "FAIL: the three messages did not settle within ${INTAKE_BUDGET_SECONDS}s"
    query "SELECT status, reason, attempts FROM event_receipt ORDER BY first_received_at" || true
    exit 1
  fi
  sleep 1
done

expected_bytes=$(printf '%s' "$SHARE_BODY" | wc -c | tr -d ' ')
expected_md5=$(printf '%s' "$SHARE_BODY" | md5sum | cut -d ' ' -f 1)
expected_sha256=$(printf '%s' "$SHARE_BODY" | sha256sum | cut -d ' ' -f 1)

expect "receipts, one of each" "DUPLICATE,STORED,UNREADABLE" \
  "SELECT string_agg(status, ',' ORDER BY status) FROM event_receipt"
expect "STORED and DUPLICATE name the one stored share" "2" \
  "SELECT count(*) FROM event_receipt r JOIN hearing_share s ON s.share_id = r.share_id
    WHERE r.status IN ('STORED', 'DUPLICATE')"
expect "the unreadable receipt keeps its reason and text, and no share" "NOT_JSON|${UNREADABLE_BODY}|true" \
  "SELECT reason || '|' || message_text || '|' || (share_id IS NULL) FROM event_receipt
    WHERE status = 'UNREADABLE'"
expect "one share" "1" "SELECT count(*) FROM hearing_share"
expect "the share: identity, latest with no predecessor, key details read" \
  "${HEARING_ID}|${HEARING_DAY}|${SHARED_TIME}|true|true|false|OK|2577|MAGISTRATES|false|true" \
  "SELECT hearing_id || '|' || hearing_day || '|'
          || to_char(shared_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') || '|'
          || is_latest || '|' || (predecessor_share_id IS NULL) || '|' || arrived_out_of_order || '|'
          || projection_status || '|' || lja_code || '|' || jurisdiction_type || '|' || is_reshare || '|'
          || any_subject_is_youth
     FROM hearing_share"
expect "one payload, the text byte for byte, with its parsed copy" "1|${expected_bytes}|${expected_md5}|true|true" \
  "SELECT count(*) || '|' || max(p.text_bytes) || '|' || max(md5(p.payload_text)) || '|'
          || bool_and(p.payload_json IS NOT NULL) || '|'
          || bool_and(s.payload_sha256 = encode(sha256(convert_to(p.payload_text, 'UTF8')), 'hex'))
     FROM hearing_share_payload p JOIN hearing_share s ON s.share_id = p.share_id"
expect "the share's defendant rows" \
  "${CASE_ID}/${ADULT_ID}/${ADULT_MASTER_ID},${CASE_ID}/${YOUTH_ID}/${YOUTH_MASTER_ID}" \
  "SELECT string_agg(d.case_id || '/' || d.defendant_id || '/' || d.master_defendant_id, ','
                     ORDER BY d.defendant_id)
     FROM share_defendant d JOIN hearing_share s ON s.share_id = d.share_id"
expect "one day row naming the share as latest, one share, youth seen" "1|1|true|true" \
  "SELECT count(*) || '|' || max(h.share_count) || '|' || bool_and(h.latest_share_id = s.share_id) || '|'
          || bool_and(h.youth_seen)
     FROM hearing_day_head h JOIN hearing_share s
       ON s.hearing_id = h.hearing_id AND s.hearing_day = h.hearing_day"

# Enrichment (spec 002). The working copy holds the first application's result without the three
# amendment fields, every other field kept; the second application, answered 200 {}, keeps its
# empty array. The arrived text is untouched: its first application still has no judicialResults
# key, and the share's checksum is the SHA-256 of the published text, computed here on the host.
expect "enrichment: flag, one result added, no amendment field, other fields kept, not-found left as it arrived" \
  "true|1|false|b1b1b1b1-0000-4000-8000-000000000001|Synthetic result|0" \
  "SELECT s.enrichment_applied || '|'
          || jsonb_array_length(p.payload_json->'hearing'->'courtApplications'->0->'judicialResults') || '|'
          || (p.payload_json->'hearing'->'courtApplications'->0->'judicialResults'->0
                ?| array['amendmentDate', 'amendmentReason', 'amendmentReasonId']) || '|'
          || (p.payload_json->'hearing'->'courtApplications'->0->'judicialResults'->0->>'judicialResultId') || '|'
          || (p.payload_json->'hearing'->'courtApplications'->0->'judicialResults'->0->>'label') || '|'
          || jsonb_array_length(p.payload_json->'hearing'->'courtApplications'->1->'judicialResults')
     FROM hearing_share s JOIN hearing_share_payload p ON p.share_id = s.share_id"
expect "enrichment: the arrived text unchanged and its checksum the published text's" \
  "false|${expected_sha256}" \
  "SELECT (CAST(p.payload_text AS jsonb)->'hearing'->'courtApplications'->0 ? 'judicialResults') || '|'
          || s.payload_sha256
     FROM hearing_share s JOIN hearing_share_payload p ON p.share_id = s.share_id"

# The stub's request log, filtered to the progression path and the store's own user rather than
# counted globally (the usersgroups stub shares this WireMock). One lookup per application: the
# duplicate share is found stored before any lookup, so it adds none.
expect_requests() {
  local what=$1 expected=$2 pattern=$3 actual
  if ! actual=$(curl --silent --fail --max-time 5 -X POST "$WIREMOCK_COUNT_URL" -d \
      '{"method":"GET","urlPathPattern":"'"$pattern"'","headers":{"Accept":{"equalTo":"application/vnd.progression.query.application-only+json"},"CJSCPPUID":{"equalTo":"'"$SYSTEM_USER_ID"'"}}}' \
      | grep -o '"count" *: *[0-9]*' | grep -o '[0-9]*$'); then
    log "FAIL: ${what}: could not read the stub's request count"
    failures=$((failures + 1))
  elif [ "$actual" != "$expected" ]; then
    log "FAIL: ${what}: expected ${expected} request(s), found ${actual}"
    failures=$((failures + 1))
  else
    log "ok: ${what}"
  fi
}
expect_requests "progression asked once for the enriched application" "1" "${PROGRESSION_PATH}/${APP_ENRICHED_ID}"
expect_requests "progression asked once for the not-found application" "1" "${PROGRESSION_PATH}/${APP_NOT_FOUND_ID}"
expect_requests "progression asked twice in all" "2" "${PROGRESSION_PATH}/.*"

scrape=$(curl --silent --fail --max-time 5 "$PROMETHEUS_URL") || scrape=""
for line in 'resultsstore_intake_received_total 3.0' \
    'resultsstore_intake_stored_total{order="in_order"} 1.0' \
    'resultsstore_intake_duplicate_total 1.0' \
    'resultsstore_intake_not_share_total{reason="not_json",status="unreadable"} 1.0' \
    'resultsstore_enrichment_applied_total 1.0' \
    'resultsstore_enrichment_applications_total{outcome="enriched"} 1.0' \
    'resultsstore_enrichment_applications_total{outcome="not_found"} 1.0' \
    'resultsstore_enrichment_skipped_total{reason="already_stored"} 1.0'; do
  if printf '%s\n' "$scrape" | grep -qxF "$line"; then
    log "ok: metric ${line}"
  else
    log "FAIL: metric line missing: ${line}"
    failures=$((failures + 1))
  fi
done

if [ "$failures" -gt 0 ]; then
  log "FAIL: ${failures} intake check(s) failed"
  exit 1
fi
log "ok: intake stored the share enriched, dropped its duplicate and recorded the unreadable message"

# --- The read API (spec 003 FR-060) ---------------------------------------------------------------
# GET with the given extra curl arguments; leaves the body in $WORK_DIR/body and the headers in
# $WORK_DIR/headers, and prints the status. A transport failure prints 000.
call() {
  local path=$1
  shift
  curl --silent --max-time 10 --output "$WORK_DIR/body" --dump-header "$WORK_DIR/headers" \
    --write-out '%{http_code}' "$@" "${API_URL}${path}" || printf '000'
}

header() {
  grep -i "^$1:" "$WORK_DIR/headers" | head -n 1 | cut -d ':' -f 2- | tr -d '\r' | sed 's/^ *//'
}

check() {
  local what=$1 expected=$2 actual=$3
  if [ "$actual" = "$expected" ]; then
    log "ok: ${what}"
  else
    log "FAIL: ${what}: expected '${expected}', found '${actual}'"
    failures=$((failures + 1))
  fi
}

# A refusal: its status, its bounded reason, the four fields only, and no path or id echoed.
check_refusal() {
  local what=$1 status=$2 reason=$3 actual=$4
  check "${what}: status" "$status" "$actual"
  check "${what}: reason" "$reason" "$(jq -r '.reason' "$WORK_DIR/body" 2> /dev/null)"
  check "${what}: the four fields only" "reason,status,title,type" \
    "$(jq -r 'keys | join(",")' "$WORK_DIR/body" 2> /dev/null)"
  check "${what}: no path or id in the body" "0" \
    "$(grep -c -e 'results-store/v1' -e "$share_id" -e 'anything' "$WORK_DIR/body" || true)"
}

share_id=$(query "SELECT share_id FROM hearing_share")
as_system=(--header "CJSCPPUID: ${SYSTEM_USER}")

log "waiting for pull to list the share once the visibility lag has passed (budget ${PULL_BUDGET_SECONDS}s)"
deadline=$((SECONDS + PULL_BUDGET_SECONDS))
listed=false
until [ "$listed" = "true" ]; do
  if [ "$(call '/shares?storedAfterSeq=0' "${as_system[@]}")" = "200" ] \
      && [ "$(jq -r --arg id "$share_id" '[.items[].shareId] | index($id) != null' "$WORK_DIR/body")" = "true" ]; then
    listed=true
  elif [ "$SECONDS" -ge "$deadline" ]; then
    break
  else
    sleep 1
  fi
done
check "pull lists the share after the lag" "true" "$listed"
check "pull carries visibleUpTo with six fraction digits" "true" \
  "$(jq -r '.visibleUpTo | test("^[0-9-]{10}T[0-9:]{8}[.][0-9]{6}Z$")' "$WORK_DIR/body" 2> /dev/null)"

check "one share: 200" "200" "$(call "/shares/${share_id}" "${as_system[@]}")"
check "one share: its id and key details" "${share_id}|2577" \
  "$(jq -r '.shareId + "|" + .keyDetails.ljaCode' "$WORK_DIR/body" 2> /dev/null)"
check "one share for a Second Line Support caller: 200" "200" \
  "$(call "/shares/${share_id}" --header "CJSCPPUID: ${SECOND_LINE_USER}")"

check "payload: 200" "200" "$(call "/shares/${share_id}/payload" "${as_system[@]}")"
etag=$(header ETag)
check "payload: SHA-256 of the body equals the unquoted ETag" "${etag//\"/}" \
  "$(sha256sum "$WORK_DIR/body" | cut -d ' ' -f 1)"
check "payload: no _metadata" "false" "$(jq 'has("_metadata")' "$WORK_DIR/body" 2> /dev/null)"
check "payload: the hearing it holds" "$HEARING_ID" "$(jq -r '.hearing.id' "$WORK_DIR/body" 2> /dev/null)"
check "payload: Content-Type" "application/json" "$(header Content-Type)"
check "payload: Results-Store-Share-Id" "$share_id" "$(header Results-Store-Share-Id)"
check "payload: Results-Store-Enrichment-Applied" "true" "$(header Results-Store-Enrichment-Applied)"
check "payload: Cache-Control" "no-store" "$(header Cache-Control)"
check "payload: If-None-Match gives 304" "304" \
  "$(call "/shares/${share_id}/payload" "${as_system[@]}" --header "If-None-Match: ${etag}")"

# The text as it arrived (phase D): before enrichment, without _metadata, hashed to its own ETag, which is
# never the stored checksum (that is over the text with _metadata).
stored_checksum=$(query "SELECT payload_sha256 FROM hearing_share WHERE share_id = '${share_id}'")
check "arrived: 200" "200" "$(call "/shares/${share_id}/payload/arrived" "${as_system[@]}")"
# A missing header must fail its checks, not stop the script under pipefail.
arrived_etag=$(header ETag || true)
check "arrived: SHA-256 of the body equals the unquoted ETag" "${arrived_etag//\"/}" \
  "$(sha256sum "$WORK_DIR/body" | cut -d ' ' -f 1)"
check "arrived: the ETag is not the stored checksum" "true" \
  "$([ -n "$stored_checksum" ] && [ -n "$arrived_etag" ] && [ "${arrived_etag//\"/}" != "$stored_checksum" ] \
    && echo true || echo false)"
check "arrived: no _metadata" "false" "$(jq 'has("_metadata")' "$WORK_DIR/body" 2> /dev/null)"
check "arrived: the hearing it holds" "$HEARING_ID" "$(jq -r '.hearing.id' "$WORK_DIR/body" 2> /dev/null)"
check "arrived: its first application without the results added at intake" "true" \
  "$(jq '.hearing.courtApplications[0] | type == "object" and (has("judicialResults") | not)' \
    "$WORK_DIR/body" 2> /dev/null)"
check "arrived: Content-Type" "application/json" "$(header Content-Type || true)"
check "arrived: Results-Store-Payload-Form" "arrived-text" "$(header Results-Store-Payload-Form || true)"
check "arrived: Results-Store-Enrichment-Applied" "true" "$(header Results-Store-Enrichment-Applied || true)"
check "arrived: Cache-Control" "no-store" "$(header Cache-Control || true)"
check "arrived: If-None-Match gives 304" "304" \
  "$(call "/shares/${share_id}/payload/arrived" "${as_system[@]}" --header "If-None-Match: ${arrived_etag}")"
check "arrived for a Second Line Support caller: 200" "200" \
  "$(call "/shares/${share_id}/payload/arrived" --header "CJSCPPUID: ${SECOND_LINE_USER}")"

check "day versions: 200" "200" \
  "$(call "/hearings/${HEARING_ID}/days/${HEARING_DAY}/shares" "${as_system[@]}")"
check "day versions: the one share, latest, version 1" "${share_id}|true|1" \
  "$(jq -r '.items | map(.shareId + "|" + (.isLatest | tostring) + "|" + (.versionNumber | tostring)) | join(",")' \
    "$WORK_DIR/body" 2> /dev/null)"

check_refusal "no CJSCPPUID" "401" "unauthenticated" "$(call "/shares/${share_id}")"
check_refusal "a caller in neither group" "403" "forbidden" \
  "$(call "/shares/${share_id}" --header "CJSCPPUID: ${NO_GROUP_USER}")"
check_refusal "an unmapped path" "404" "route_not_found" "$(call '/anything' "${as_system[@]}")"
check "a vendor Accept on pull: 200" "200" "$(call '/shares?storedAfterSeq=0' "${as_system[@]}" \
  --header 'Accept: application/vnd.results-store.get-share-payload+json')"
check "a vendor Accept on pull: answered as application/json" "application/json" "$(header Content-Type)"

scrape=$(curl --silent --fail --max-time 5 "$PROMETHEUS_URL") || scrape=""
for line in 'resultsstore_read_requests_total{endpoint="share",outcome="ok"} 2.0' \
    'resultsstore_read_requests_total{endpoint="payload",outcome="not_modified"} 1.0' \
    'resultsstore_read_requests_total{endpoint="arrived_payload",outcome="not_modified"} 1.0' \
    'resultsstore_read_refused_total{reason="route_not_found"} 1.0' \
    'resultsstore_read_refused_total{reason="unauthenticated"} 1.0' \
    'resultsstore_read_refused_total{reason="forbidden"} 1.0' \
    'resultsstore_intake_visibility_overrun_total 0.0'; do
  if printf '%s\n' "$scrape" | grep -qxF "$line"; then
    log "ok: metric ${line}"
  else
    log "FAIL: metric line missing: ${line}"
    failures=$((failures + 1))
  fi
done

if [ "$failures" -gt 0 ]; then
  log "FAIL: ${failures} read API check(s) failed"
  exit 1
fi
log "PASS: intake stored the share enriched and the read API served it to admitted callers only"
