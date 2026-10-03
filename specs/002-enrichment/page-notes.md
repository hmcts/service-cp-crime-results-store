# Design page: forward notes from spec 002

**For**: the owner of the Results Store design page (CRA 321061800).
**From**: spec 002 (enrichment), constitution 2.1.0 (Principle II and Principle VI).
**Status**: notes only. The page has not been edited. Each note gives the section, the wording on the
page today, and paste-ready wording with the same meaning as Principle II 2.1.0 (FR-035, FR-037).

In one line: the store now keeps two forms of each share. `payload_text` is the message exactly as it
arrived, and the checksum is over it. `payload_json` is the working copy: the same message with any
finalised application results from progression added. Key details, the sweep and the read API all use
the working copy.

---

## 1. Data model and versioning: bullet 2 (*The payload is the source of truth*)

**Today**: "The store keeps each share's message text exactly as it arrived, byte for byte, header
included (plus the finalised application results added at intake, once that step exists). Every column
is read from that text …"

**Replace with**:

> **The payload is the source of truth.** The store keeps each share's message exactly as it arrived,
> byte for byte, header included. The checksum is SHA-256 over that text. Beside it the store keeps one
> working copy: the same message with the finalised application results from progression added at
> intake, without `amendmentDate`, `amendmentReason` and `amendmentReasonId`. Nothing else is added,
> removed or changed. The working copy is held as `jsonb`, which keeps the content but not the key
> order, spacing or duplicate keys. Every column is read from the working copy and can be rebuilt from
> it if the extraction rules change or a new field needs indexing.

---

## 2. Data model: the `hearing_share_payload` row

**Today**: "The payload, stored twice: the exact text that arrived, so it can be returned unchanged and
its checksum reproduced; and a parsed `jsonb` copy, used to rebuild the columns and for the R2 check,
not by consumers. In case of performance issues, the `jsonb` copy can be dropped after NFT testing."

**Replace with**:

> The payload, stored twice. `payload_text`: the exact text that arrived, so the checksum always proves
> what hearing sent. `payload_json`: the working copy, a `jsonb` copy of that text with finalised
> application results added at intake (see *Adding finalised application results*). The working copy
> is what the columns are read from, what the R2 check samples, and what the read API returns. It is
> permanent and is not dropped. It is empty only when the database cannot hold the text as `jsonb`
> (for example a `\u0000` character); then the text is used instead.

---

## 3. Read API: the `GET /shares/{shareId}/payload` row

**Today**: "The payload, exactly as received. The `ETag` is the payload checksum. `enrichmentApplied`
says whether application results were added at intake."

**Replace with**:

> The payload as the store holds it: the working copy, with finalised application results added at
> intake (the text as it arrived when the working copy is empty). The `ETag` is computed over the exact
> bytes returned, not the payload checksum, because the working copy is not byte-identical to the
> message that arrived. `enrichmentApplied` says whether application results were added at intake.

(This is the constraint spec 003 builds to: serve `payload_json`, `ETag` over the bytes served,
`payload_text` when `payload_json` is empty; spec 002 FR-041.)

---

## 4. Intake: *Adding finalised application results*, the paragraph after the bullets

**Today**: "The store calls progression as a system user: the call goes through the API gateway with
the store's own system user id in the CJSCPPUID header, the same way the validation and YOT services
call other contexts. If progression cannot be reached, the share is retried (below); it is not stored
half-enriched. The store records whether enrichment was applied."

**Replace with**:

> The store calls progression's query API on the stack's internal host (`CP_BASE_URL`, set per
> environment), with the store's own system user id in the CJSCPPUID header. It never uses another
> service's user. It asks once per application, one at a time. A share already stored makes no call.
>
> Progression answers an unknown application with `200 {}`. The store treats that as "nothing to add"
> and leaves the application as it arrived. The same goes for an application that is not `FINALISED`,
> or is `FINALISED` with no results.
>
> Anything else fails closed: the share is not stored, and the message goes back to the broker to be
> tried again. That covers progression being down, slow or unreachable, and also answers that point
> to a fault on our side: a 404 (only a wrong route gives one, since an unknown id gives `200 {}`), a
> 401 or 403 (the system user is refused), or a body the store cannot read. Storing those shares
> without their results would quietly break parity, so the store waits instead. If the fault is not
> fixed, the message reaches the dead-letter queue when the broker's attempts run out. A share is never
> stored half-enriched.
>
> The message as it arrived is kept unchanged, with its checksum. The results are added to the working
> copy only (*Data model and versioning*). The store records whether enrichment was applied.

---

## 5. Observability: *Metrics on the dashboard*, additions

Add to the list:

> - progression lookups by outcome: enriched, not found, not finalised, no results, invalid id;
> - how long progression lookups take;
> - shares stored with application results added;
> - shares that needed a lookup but made none: enrichment switched off, share already stored, or
>   results the database could not hold (stored as they arrived);
> - failed intake attempts at the enrichment step, by cause: progression unavailable, unreachable or
>   timed out (progression or the network); refused (the system user); rejected or malformed (the
>   route or the contract).

The *Progression lookups failing* alert reads the last item.

---

## 6. Future considerations: two additions under *Later capabilities*

> - **Working out application results from the store's own history.** Instead of asking progression,
>   the store could find an application's finalised results in the earlier shares it holds. Not now,
>   for four reasons:
>   1. **History gap.** The store holds no shares from before it went live, so it cannot answer for
>      applications finalised earlier.
>   2. **Rules drift.** Progression carries results forward by its own rules. The store would have to
>      copy those rules and keep them in step.
>   3. **No application index.** The store does not index shares by application, so it cannot find the
>      earlier share quickly.
>   4. **Parity is defined by progression.** Results asks progression today. Matching results means
>      asking the same source.
>
>   It could be revisited once the history is complete. It would also need an agreed change to the
>   "no business rules at capture" principle, since it is a derivation.
> - **Listening for progression's update instead of asking.** Progression publishes
>   `public.progression.hearing-resulted-application-updated`. The store could subscribe to it instead
>   of calling the query. That needs a second subscription and a way to join each update to the right
>   share, and the query is what results uses today, so the query stays for now.

---

## Related wording to check (optional)

- *Intake*, *Retries*, bullet 1: "A failure that a retry can fix (progression not reachable, database
  not reachable) rolls the message back." Could add: "or an answer from progression the store cannot
  accept (a wrong route, a refused user, an unreadable body)".
- *Write path*, step 3 (*Enrich*): "If progression cannot be reached, stop here" could read "If
  progression cannot be reached, or gives an answer the store cannot accept, stop here".
