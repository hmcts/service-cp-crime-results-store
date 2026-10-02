# Contract: inbound `hearing-resulted` event

The one interface this feature consumes. The store publishes nothing.

## Subscription

| Item | Value | Source |
|---|---|---|
| Broker | Artemis (legacy estate broker) | constitution IX |
| Topic | `public.event` (multicast address) | `resultsstore.publicevents.topic` |
| Subscription | `resultsstore-service.sdg`, shared, durable, **no client id** | `resultsstore.publicevents.subscription` |
| Selector | `CPPNAME = 'public.events.hearing.hearing-resulted'` (applied by the broker) | `resultsstore.publicevents.selector` |
| Consumers | one per pod (concurrency 1), several pods on the one subscription | `PublicEventsConfig` |
| Session | transacted, no JMS transaction manager; commit = acknowledge | `PublicEventsConfig` |
| Redelivery | the broker's: immediate, max attempts per environment (SIT: 10), then its dead-letter address | broker settings |

The subscription name and the selector are the subscription's identity. Changing either abandons
the existing subscription and its backlog (constitution IX). This feature does not change them.

## JMS headers and properties read

| Item | Use |
|---|---|
| `JMSMessageID` | the receipt key. If null: `sha256:<SHA-256 hex of the text>` and a counter (research R3) |
| `JMSXDeliveryCount` | stored as `delivery_count`; drives the capped pause, `min(2^n s, 30 s)` (research R14). Missing → 1 |
| `CPPNAME` | used only by the broker's selector; equals `_metadata.name` |
| message type | must be `TextMessage`; anything else is `UNREADABLE` (`NOT_TEXT_MESSAGE`) |

## Body

A JSON object: the framework's `JsonEnvelope`, the payload keys beside `_metadata`. The schema
(`public.events.hearing.hearing-resulted.json`) has `additionalProperties: true` at the root and
requires `hearing`, `hearingDay`, `sharedTime`, `isReshare`. The store checks only the three
identity fields; the rest is stored as sent (Principle V).

```json
{
  "_metadata": {
    "id": "…uuid…",
    "name": "public.events.hearing.hearing-resulted",
    "createdAt": "2026-10-02T14:19:51.012Z",
    "causation": ["…uuid…"],
    "stream": { "id": "…uuid…", "version": 42 },
    "source": "…",
    "context": { "user": "…uuid…" },
    "correlation": { "client": "…" }
  },
  "hearing": { "id": "…uuid…", "courtCentre": { "id": "…", "roomId": "…", "lja": { "ljaCode": "…" } }, "…": "…" },
  "hearingDay": "2026-10-02",
  "sharedTime": "2026-10-02T14:19:50.706Z",
  "isReshare": false,
  "shadowListedOffences": []
}
```

`_metadata` fields observed on the estate: `id`, `name`, `createdAt`, `causation[]`,
`stream{id, version}`, `source`, `context{user}`, `correlation{client}`. The store reads none of
them; they stay in the stored text.

## Identity fields

| Field | Path | Form required | Example |
|---|---|---|---|
| hearing id | `hearing.id` | string, canonical UUID (8-4-4-4-12 hex) | `6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a11` |
| hearing day | `hearingDay` | string, `yyyy-MM-dd` | `2026-10-02` |
| shared time | `sharedTime` | string, ISO 8601 / RFC 3339 date-time with offset | `2026-10-02T14:19:50.706Z` |

**`sharedTime` wire format.** Hearing writes UTC with a `Z` suffix and exactly three fraction
digits (milliseconds). The store accepts any valid offset and any number of fraction digits, and
compares instants; the share id is computed from the string as sent (research R4, FR-012).

## Key-detail fields read (never validated as a whole)

| Column | Path | Type |
|---|---|---|
| `court_centre_id` | `hearing.courtCentre.id` | UUID string |
| `court_room_id` | `hearing.courtCentre.roomId` | UUID string |
| `lja_code` | `hearing.courtCentre.lja.ljaCode` | string |
| `jurisdiction_type` | `hearing.jurisdictionType` | string (as stated) |
| `is_sjp` | `hearing.isSJPHearing` | boolean |
| `is_group_proceedings` | `hearing.isGroupProceedings` | boolean |
| `youth_court_id` | `hearing.youthCourt.youthCourtId` | UUID string |
| `is_reshare` | `isReshare` | boolean |
| `share_defendant.case_id` | `hearing.prosecutionCases[].id` | UUID string |
| `share_defendant.defendant_id` | `hearing.prosecutionCases[].defendants[].id` | UUID string |
| `share_defendant.master_defendant_id` | `hearing.prosecutionCases[].defendants[].masterDefendantId` | UUID string |
| `any_subject_is_youth` | `hearing.prosecutionCases[].defendants[].isYouth` | boolean, three-valued |

Not read in 001: `courtApplications[]` parties (spec 002), `youthCourtDefendantIds`,
`hearing.youthCourt` beyond its id.

## Outcomes the publisher can rely on

| Message | Receipt | Broker |
|---|---|---|
| a new share | `STORED` | acknowledged after the store commits |
| a share already stored (any message id) | `DUPLICATE`, pointing at the stored share | acknowledged |
| not a share (unreadable, no identity) | `UNREADABLE` / `NO_IDENTITY` with reason and text | acknowledged, never redelivered or dead-lettered |
| a redelivery of a settled message id | unchanged status, attempts + 1 | acknowledged |
| a retryable failure (database down, lock timeout) | stays `RECEIVED`, attempts counted | rolled back after the capped pause; redelivered; dead-lettered by the broker after its attempts |
