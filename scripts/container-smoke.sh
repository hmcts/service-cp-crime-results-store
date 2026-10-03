#!/usr/bin/env bash
#
# Container smoke: build the image, run it against the committed compose dependencies, and require
# it to report readiness inside the 60-second budget. Then intake end to end (FR-047, SC-012):
# publish a real-shaped hearing-resulted message to the compose broker, the same message again and
# an unreadable one, and check the rows they leave. Tears the stack down on every exit path,
# success or failure.
#
# This is the local equivalent of the "Container smoke" step in
# .github/workflows/ci-build-publish.yml; both run this same script, so the two cannot drift.
#
#   ./scripts/container-smoke.sh
#
# It proves the packaged artefact starts, answers and takes in a share through the real broker into
# the real database, which no JUnit suite can: the *IT suites run inside the build's JVM and would
# still pass if the image were unbuildable. Only docker compose, curl and coreutils are needed on the
# host: the broker is driven by its own CLI and the database by its own psql, inside their containers.

set -euo pipefail

readonly READINESS_BUDGET_SECONDS=60
readonly DEPENDENCY_BUDGET_SECONDS=120
readonly READINESS_URL="http://localhost:8082/actuator/health/readiness"
readonly PROMETHEUS_URL="http://localhost:8082/actuator/prometheus"
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
readonly SHARE_BODY='{"_metadata":{"id":"7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","name":"public.events.hearing.hearing-resulted","createdAt":"2026-10-02T14:19:51.012Z","causation":["8b2c3d4e-5f6a-4b7c-9d8e-0f1a2b3c4d5e"],"stream":{"id":"5e0c7a1d-3f2b-4c6d-8e9f-0a1b2c3d4e5f","version":7},"context":{"user":"9c3d4e5f-6a7b-4c8d-ae9f-1a2b3c4d5e6f"}},'\
'"hearing":{"id":"'"$HEARING_ID"'","jurisdictionType":"MAGISTRATES","isSJPHearing":false,'\
'"courtCentre":{"id":"9d2e4f6a-1b3c-4d5e-8f70-a1b2c3d4e5f6","roomId":"1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d","lja":{"ljaCode":"2577"}},'\
'"prosecutionCases":[{"id":"'"$CASE_ID"'","defendants":['\
'{"id":"'"$ADULT_ID"'","masterDefendantId":"'"$ADULT_MASTER_ID"'","isYouth":false},'\
'{"id":"'"$YOUTH_ID"'","masterDefendantId":"'"$YOUTH_MASTER_ID"'","isYouth":true}]}]},'\
'"hearingDay":"'"$HEARING_DAY"'","sharedTime":"'"$SHARED_TIME"'","isReshare":false,"shadowListedOffences":[]}'
readonly UNREADABLE_BODY="not json"

# A project name of this script's own. Everything it creates — containers, network, volumes — is
# namespaced under it, so the teardown's `down --volumes` can only ever destroy what this script
# made. Without it the script would share the default project with a developer's own
# `docker compose up`, and a smoke run would silently delete their database volume.
readonly PROJECT_NAME="resultsstore-smoke"

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
# earlier checkout would have this script smoke-testing code that is no longer in the tree.
log "building the application jar"
./gradlew bootJar

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

scrape=$(curl --silent --fail --max-time 5 "$PROMETHEUS_URL") || scrape=""
for line in 'resultsstore_intake_received_total 3.0' \
    'resultsstore_intake_stored_total{order="in_order"} 1.0' \
    'resultsstore_intake_duplicate_total 1.0' \
    'resultsstore_intake_not_share_total{reason="not_json",status="unreadable"} 1.0'; do
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
log "PASS: intake stored the share, dropped its duplicate and recorded the unreadable message"
