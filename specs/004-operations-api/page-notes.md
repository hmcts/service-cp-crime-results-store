# Design page: forward notes from spec 004

**For**: the owner of the Results Store design page (CRA 321061800), and the YOT and probation teams who
build to the read API.
**From**: spec 004 (operations API); constitution 2.3.0 once T011 lands (Principle I).
**Status**: notes only. The page has not been edited. Each note gives the section, the wording on the
page today where this repository records it, and paste-ready wording. Points marked *pending Sachin*
carry the default of spec.md *Decisions pending Sachin*. T011 reconciles these notes with what was built.

In one line: the operations API is four endpoints under `/operations` for "Second Line Support" only. A
rerun is a request the extraction sweep works: it re-reads each named share's stored working copy and
rewrites its key details in place, and the share stays `OK`. Receipts and the daily reconciliation are
read on demand; nothing is stored for them. The full support contract is
`specs/004-operations-api/contracts/operations-api.md`.

---

## 1. *Data model and versioning*

**Today** (as summarised in this repository's design rules): the tables table lists
`reconciliation_finding` (*Reconciliation findings (R1, R2)*), and *What may be updated?* says "By the
extraction sweep alone: the key-details columns and the `projection_*` columns".

**Add to the tables table**:

> | `extraction_rerun` | One row per operator rerun request: the selector, its hash, the reason, the operator, counts, `OPEN` then `DONE` |
> | `extraction_rerun_item` | One row per share named by a request: pending or done, its outcome, its operational attempts. At most one pending item per share |
> | `sweep_round` | Each pod's last extraction-sweep round: times, extractor version, counts |

**Change the `reconciliation_finding` row to**: "Not built in phase 1. A later spec adds it with the
nightly reconciliation job (pending Sachin)."

**Change *What may be updated?* to**:

> By the extraction sweep alone, re-read from the stored working copy: the key-details columns and the
> `projection_*` columns. The sweep retries a `FAILED` share, and re-reads an `OK` share only while a
> pending rerun item names it. An `OK` share stays `OK`: a failed re-read keeps its key details, an older
> extractor never overwrites a newer reading, and a youth subject once true stays true. The database
> refuses anything else.

---

## 2. *Operations API*

**Today**: "`POST /operations/extraction/rerun`, `GET /operations/extraction/status`,
`GET /operations/receipts?hearingId=&hearingDay=`, `GET /operations/reconciliation/daily?date=`. "Second
Line Support" only, audited, never returns a payload."

**Replace with**:

> | Endpoint | Purpose |
> |---|---|
> | `POST /operations/extraction/rerun` | Ask the store to read some shares' key details again: a stored-time range (up to 31 days, ending before the read API's visibility lag), up to 200 hearings, or up to 1,000 shares; at most 200,000 shares; a reason is required (no personal data). Answers `202` at once with a request id and counts. Posting the same selector while it is open returns the same request. The sweep then works the items, 200 per round per pod |
> | `GET /operations/extraction/status` | `FAILED` shares waiting, out of retries, or waiting for a new extractor; pending rerun items; the 20 most recent requests with their outcomes; items held or abandoned; each pod's last sweep round |
> | `GET /operations/receipts?hearingId=&hearingDay=` or `?messageId=` | The receipts of one hearing day, or one receipt: status, attempts, times, bounded reason, share id. Never the message text |
> | `GET /operations/reconciliation/daily?date=` | One London day, worked out on demand: receipts by status, shares stored, extraction failures, R1 (receipts still `RECEIVED` an hour after their last delivery; pending Sachin) and R2 (counts only; pending Sachin) |
>
> "Second Line Support" only (each action's rule names that group and matches its method and path),
> audited, never returns a payload, a message's text, a rerun reason or an operator id. There is no replay
> endpoint.

**Add a short paragraph *How a rerun works***:

> A rerun never touches a share when it is asked for. It records a request and one item per matched
> share. Each sweep round claims pending items (pods never claim the same ones), reads each share's
> stored working copy again and writes the result in place, under the hearing-day lock. Outcomes:
> re-extracted, unchanged, fixed or failed again (a `FAILED` share), kept (the re-read failed), youth
> kept (a `true` youth subject is never lowered), youth raise held (a `false` youth subject that would
> become `true` is not changed until consumers can be told; pending Sachin), newer kept (an older
> extractor never overwrites a newer reading), abandoned (three operational failures; pending Sachin). A
> request closes when no item is pending.

**Add a line on the contract**:

> The operations API's OpenAPI contract is published with the read API's, from
> `hmcts/api-cp-crime-results-store` (tag `operations`, from release `0.3.0`; pending Sachin). A stored
> range must end at least the read API's visibility lag (90 seconds) before the request.

---

## 3. *Reconciliation*

**Today** (as summarised in this repository's design rules): "R1: every receipt reached an end state
(`STORED`, `DUPLICATE`, `UNREADABLE`, `NO_IDENTITY`). R2: indexed columns still match the payload", with
the findings in `reconciliation_finding`.

**Add**:

> In phase 1 the reconciliation is worked out on demand by
> `GET /operations/reconciliation/daily?date=` over the London day (23 or 25 hours on clock-change
> days). R1: receipts first received that day, still `RECEIVED`, whose last delivery is older than a
> give-up window (1 hour until the broker's redelivery give-up time is confirmed). R2: counts of shares
> whose extraction failed or was read by an older extractor. Nothing is stored. The nightly job, the
> findings table and the alert "reconciliation does not run" are a later spec (pending Sachin).

---

## 4. *Observability*

**Add to the metrics list**: rerun requests (created, repeated), shares queued, operations requests
refused by reason, every rerun item outcome, rerun requests finished, sweep rounds not recorded.

**Add to the alerts**: an abandoned rerun item; a held youth raise (tell the YOT team); a sweep round not
recorded. The alert "Reconciliation … does not run" waits for the nightly job.

---

## 5. *Read API*: values that change in place

**Add to the read API's rules**:

> A rerun can rewrite a share's key details in place, with no new `storedSeq`, so a pull does not
> present the share again. `projectionVersion` and `projectedAt` show when key details were last
> written. A youth subject that is `true` is never lowered; one that is `false` is not raised to `true`
> by a rerun until a youth-raised feed exists (pending Sachin); one that is unknown can become `false` or
> `true`, and one that is `false` can become unknown (pending Sachin). So a hearing day can leave or join
> the `dayYouthSeen=notFalse` view without a new share. Consumers re-read; re-pulling from an older
> cursor is the way to reconcile.

---

## 6. Open questions to add to the page

Until Sachin rules: D-RERUN-GUARD, D-NEVER-BLANK, D-YOUTH-RAISE, D-RERUN-CANCEL, D-RERUN-BOUNDS,
D-RECON-CLOCK, D-R1-WINDOW (with the platform team), D-R2, D-NIGHTLY, D-SWEEP-ROUND, D-RERUN-ERASURE,
D-PRINCIPLE-I-BUMP, D-OPS-CONTRACT (spec.md *Decisions pending Sachin* has each question, default and
alternatives). D-LAG-VALUE (90 seconds), D-AUTHZ-REQUIRED, D-VII-AUDIT-WORDING and D-REFUSALS-UNAUDITED
were decided with Sachin for spec 003 and apply here as settled; D-PG-VERSION stays a recorded risk.

---

## 7. For the YOT team

- **Key details can change in place.** A support rerun can rewrite a share's court, room, LJA, SJP flag
  and other key details after you pulled it, with no new `storedSeq`. If you batch by court centre,
  re-read shares before a batch closes, or reconcile by re-pulling from an older cursor.
- **Youth.** A rerun never lowers `dayYouthSeen` from `true`. A `false`-to-`true` change is held, not
  written, until a youth-raised feed exists (pending Sachin); support staff see it in the status and an
  alert fires, so tell the store team what notice you need. An unknown day can become `false` (it leaves
  your `notFalse` view) or `true`; a `false` day can become unknown (it re-enters the view, but no share
  is presented again).
- **Nothing else changes for you**: the read API's endpoints, fields and errors are as spec 003's
  contract.

## 8. For the probation team

- **"Everything returned is final"** holds for the set of shares and their `storedSeq` order, not for
  their key details: a support rerun can rewrite them in place. Re-read `GET /shares/{shareId}` when a
  key detail matters, and compare `projectionVersion` and `projectedAt`.
- **Payloads do not change.** A rerun re-reads the stored working copy; the payload, its bytes and its
  `ETag` are never touched.
