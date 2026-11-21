# lego-data-ingress runbook

What this service does today, how its parts fit together, the rules that keep the BrickLink account safe, and
how to operate and diagnose it. Written as current state (HOMELAB-24). The earlier 3,000-line runbook and the
inventory-sync playbook, with their phase-by-phase history, are kept in
`00-workspace/docs/legacy/lego-data-ingress/`. They describe removed jobs and tables; don't follow them.

For a property's full list of keys and defaults, read the `@ConfigurationProperties` class named in
[Configuration](#configuration). The code is the reference; this document does not copy it.

Intake of a physical item up to a local BrickLink draft is done by lego-data-service: see its
`playbook.md` and `runbook.md`.

## The picture

```text
                       lego-data-service: intake, local DRAFT listing (unpriced or fixed price)
                                                   |
  every environment                                v
  BricklinkWantListSyncJob ---- what I sell ----> marketplace_pricing.want_list_item
                                                   |
  one deployment (SANDBOX)                         v
  BricklinkPricingCrawlJob --- catalogifs.ajax --> marketplace_pricing.market_snapshot + market_listing
                                                   |   (shared, append-only market history)
  every environment                                v
  RepriceJob: decide -> guard -> record pricing_decision -> (APPLY) ACCEPTED + sync request
                                                   |   immediate hand-off, or left PENDING for the job
                                                   v
  BricklinkMarketplaceSyncJob: claim -> safety check -> BrickLink write -> read-back -> local state
                                                   |
  read-only watchers                               v
  BricklinkInventoryReconciliationJob (drift)   BricklinkLotAuditJob (lower-env lots never public)

  Orders:     BricklinkOpenOrderProbeJob (read BrickLink orders) -> FulfillmentScheduledSyncJob (PROD writes)
  Photos/catalog: S3 upload events -> Kafka -> photo, BrickLink and Rebrickable catalog ingestion
  Images:     ImageHostingScheduledSyncJob -> Flickr albums + flic.kr short links (via lego-imaging)
```

In plain words: each environment says which items it is selling. One crawler fetches the BrickLink market for
all of them into a shared schema. Each environment then decides its own prices from that market, checks every
price against a guard, and hands accepted prices to the marketplace sync worker. That worker is the only code
that writes to BrickLink lots, and it re-verifies everything just before it writes.

## Safety invariants

These hold in every environment. Code enforces each one; the class is named so it can be checked.

| Invariant | Enforced by |
| --- | --- |
| **One BrickLink account for every environment.** Lower environments may only use their own stockroom: SANDBOX `C`, DEV `B`. Stockroom `A` and public lots are PROD only. | `LegoEnvironment` / `BricklinkStockroomPolicy` (lego-data); the sync worker's safety services; `BricklinkLotAuditJob` checks it independently |
| **Every lot carries an ownership block** in its remarks: `[SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true; LEGOHUNTER_ENV=<env>; MARKETPLACE_LISTING_ID=<id>; ITEM_INVENTORY_UUID=<uuid> [SYSTEM_END]`. A write to a lot whose block is missing, malformed or names another environment, listing or item is blocked. Human remarks outside the block are kept. | `BricklinkRemoteInventorySafetyService`, `BricklinkListingCreateSafetyService` |
| **Remote first.** The local price changes only after BrickLink's read-back shows the new price. A draft's first price is the exception: it is set with the create request. | `BricklinkMarketplaceSyncService` |
| **A lot is created at most once.** The new lot id is saved before the read-back. A retry resumes from it, or finds the lot by its ownership block. | `BricklinkMarketplaceSyncService` (HOMELAB-15) |
| **Orders are PROD-only writes.** ShipStation orders, BrickLink order status and Drive Thru emails are written only when `lego.environment.production=true`, which is set only in `application-prod.yml`. Lower environments read orders. | `FulfillmentSyncService` |
| **The environment must match its database.** Startup fails if `lego.environment` (code and production flag) does not match the connected schema. | lego-data starter (HOMELAB-19/34) |
| **Never copy listing or marketplace state from PROD to a lower environment.** Item data may be copied. | Owner rule; seeding (HOMELAB-40) creates fresh stockroom lots |

## What runs where

From `application-<profile>.yml`. Kubernetes runs `<env>,kubernetes`, and the later profile wins, so the
`kubernetes` profile's crawl interval (3 min) applies in the cluster. A local run defaults to `sandbox,local`.

| Job | SANDBOX | DEV | PROD |
| --- | --- | --- | --- |
| `BricklinkWantListSyncJob` | on | on | off |
| `BricklinkPricingCrawlJob` (shared crawler) | **on, the only one** | off | off |
| `RepriceJob` | `APPLY` | `DRY_RUN` | off |
| `BricklinkMarketplaceSyncJob` | `APPLY`, stockroom C | `APPLY`, stockroom B | off |
| `BricklinkInventoryReconciliationJob` | on (hourly) | off | off |
| `BricklinkLotAuditJob` | on (30 min) | off | off |
| `BricklinkOpenOrderProbeJob` | every 5 min, writes local staging only | every 5 min, local staging only | hourly |
| `FulfillmentScheduledSyncJob` | maps only (apply off) | maps only (apply off) | hourly, applies |
| `ImageHostingScheduledSyncJob` | every 5 min, applies (`kubernetes` profile) | same | same |

PROD go-live for pricing and sync is tracked in epic HOMELAB-84. When PROD runs them, the lot audit should move
to PROD, where a SANDBOX feature deploy cannot switch it off.

Every scheduled job holds a ShedLock lock and runs with a fixed delay, measured from the end of the previous
run. A job needs both its `enabled` and its `scheduled.enabled` switch.

## Pricing

### Want list: `BricklinkWantListSyncJob` (hourly)

Keeps this environment's rows in `marketplace_pricing.want_list_item`, one per canonical item + condition
(`N`/`U`) + completeness (`S`/`C`/`B`/`X`) it has a priceable listing for (`ACTIVE` or `DRAFT`, recognised
condition, a completeness, a catalog item with type and number). It writes and prunes only rows with its own
environment code. A row not confirmed for `prune-after` (2 days) is deleted.

### Shared crawler: `BricklinkPricingCrawlJob` (SANDBOX, every 3 min in the cluster)

Crawls the combined want list once, for every environment. Each run:

1. **Targets:** creates a `crawl_target` per wanted item + `N`/`U`, re-enables targets that are wanted again,
   and disables ones no environment wants.
2. **Blackout:** stops (outcome `BLACKOUT`) inside the blackout window, which is weekdays 21:30–08:30 New York
   time.
3. **Claim and fetch:** claims `worker-batch-size` (1) due targets, then fetches `catalogifs.ajax`. It first
   resolves BrickLink's `idItem` through `searchproduct.ajax` when it is not known.
4. **Store:** writes one `market_snapshot` per success and one `market_listing` per lot. An empty market is a
   success. Every attempt is logged as a `crawl_attempt` row.
5. **Release:** after a success, the target is due again after `crawl-cadence` (7 days). After an item failure,
   it is due again after `retry-backoff` (6 h), or after a full cadence once `max-attempts` (3) failures in a
   row are reached.

**Outages** (any redirect such as the maintenance page, 403/429, 5xx, I/O or timeout; `BricklinkOutageClassifier`)
do not count against the target. The run stops at the first outage, with outcome `OUTAGE`, and no manual reset is
needed afterwards (HOMELAB-78).

To test crawler changes from a branch, point it at `lego.marketplace-pricing.schema=marketplace_pricing_scratch`,
never at the real schema.

### Reprice: `RepriceJob` (every 10 min in SANDBOX and DEV)

The one pricing job (HOMELAB-23/65/66). For each candidate listing, in one transaction:

1. **Decide** a price (`BricklinkPricingDecider`, next section).
2. **Guard** it (`RepriceGuard`).
3. **Record** a `pricing_decision`, with `guard_status_code` (`PASSED`/`BLOCKED`) and `guard_reason_code`.
4. **Accept** (only in mode `APPLY`): the decision becomes `ACCEPTED`, and a `PENDING` sync request is queued:
   `PRICE_UPDATE` for a listing with a lot, or `LISTING_CREATE` for a draft, which gets its local price in the
   same transaction.

After the commit, up to `immediate-sync-limit` (5) requests go straight to the sync worker, through the same claim
and checks as the job. The rest wait for the sync job. Nothing is handed off unless marketplace sync is also in
`APPLY`.

Candidates are `ACTIVE`/`DRAFT` BrickLink listings with a catalog item and (unless fixed price) a recognised
condition and completeness, whose newest matching market snapshot has no decision yet. Fixed-price listings are
recorded `SKIPPED` once. SANDBOX and DEV set `require-current-snapshot: true`, so a listing without a snapshot
waits instead of failing. A listing with a sync request still `PENDING` or `CLAIMED` gets no decision until that
change finishes.

| Mode (`lego.pricing.reprice.mode`) | Guard passed → | Writes |
| --- | --- | --- |
| `DRY_RUN` | `PROPOSED` | the decision only |
| `APPLY_LOCAL_ONLY` | `APPLIED` | the decision and `marketplace_listing.unit_price`; no BrickLink write |
| `APPLY` | `ACCEPTED` | the decision and the sync request, then the hand-off |

Decision statuses: `PROPOSED`, `BLOCKED` (the guard said no), `SKIPPED` (fixed price), `FAILED` (no price
could be decided), `ACCEPTED` (request queued), `APPLIED` (BrickLink confirmed), `SYNC_FAILED` (the request
ended `BLOCKED`, or `FAILED` with no attempts left; HOMELAB-64).

### How a price is decided

Algorithm `bricklink-market-position-v2` (from the HOMELAB-13 analysis:
`00-workspace/context/pricing-plane/pricing-algorithm-analysis.md`). Buyers buy the cheaper lots, so the
formula prices at a chosen **percentile** of the competing lots, not above the market.

**Comparables** are the lots in the newest snapshot with the same condition **and** sub-condition (Sealed,
Complete, Incomplete) as the listing. Excluded: the listing's own lot, lots from `own-seller-names`, and lots
without a positive price in the listing's currency.

| Comparables | Price | Reason |
| --- | --- | --- |
| 3 or more | Outliers above `outlier-max-ratio-to-median` (3×) are dropped first. Then the **target percentile** of the rest, interpolated. | `MARKET_POSITION` |
| 2 | lower + (higher − lower) × `two-comparables-fraction` (0.25) | `TWO_COMPARABLES_WEIGHTED` |
| 1 | just under it: `min(p − min(3% of p, 10), max(p − 1, 1))` | `SINGLE_COMPARABLE_DISCOUNTED` |
| 0 | none: `FAILED`; set a price by hand | `NO_CURRENT_COMPARABLES` / `NO_EXACT_COMPARABLES` |

Then, in order:
- for used items, the box and instructions grade factors (SL 1.10 … P 0.85; missing counts as 1.0);
- the optional weekly step-down for unsold active listings;
- the per-run change limit: ± `maximum-change-per-run` (25%), and `maximum-absolute-change-per-run` where set (50.00 in SANDBOX and DEV);
- the `minimum-price` / `maximum-price` clamps;
- rounding to cents.

`pricing_decision.source_summary_json` records every input: target percentile, packaging, comparables, outliers
dropped, market price, step-down and whether the change limit applied.

**Target percentiles by preset** (`lego.pricing.reprice.algorithm.aggression`). For used items, your own item's
packaging picks the column: box and instructions (`BI`), one of the two, or neither.

| `aggression` | Intent | Used `BI` | Used one | Used neither | Sealed / other new |
| --- | --- | --- | --- | --- | --- |
| `QUICK` | sell within ~6 months | 0.55 | 0.20 | 0.15 | 0.10 |
| `BALANCED` (default) | sell within 12 months at the best price per sale | 0.65 | 0.35 | 0.25 | 0.20 |
| `PATIENT` | hold out for price; 18–24 months is fine | 0.75 | 0.45 | 0.35 | 0.30 |

Overrides use bracketed market keys, where the most specific key wins:

```yaml
lego:
  pricing:
    reprice:
      algorithm:
        aggression: BALANCED
        target-percentiles:
          "[U-C-BI]": 0.70      # Used/Complete with box and instructions
          "[N-S]": 0.25         # Sealed
        step-down:
          enabled: true         # 1%/week after 8 weeks unsold, at most 20% off, never below the 10th percentile
```

### The guard

`RepriceGuard` (`lego.pricing.reprice.guard.*`) decides whether a decided price may be sent. A blocked decision
records why in `guard_reason_code`.

| Guard reason | Blocks when |
| --- | --- |
| `PRICE_UNCHANGED`, `BELOW_MINIMUM_DELTA`, `BELOW_MINIMUM_DELTA_PERCENT` | the move is smaller than `minimum-price-delta`, or than `minimum-delta.percent` (2% in SANDBOX and DEV) |
| `ABOVE_MAXIMUM_ABSOLUTE_DELTA`, `ABOVE_MAXIMUM_PERCENT_DELTA` | the move is larger than `maximum-absolute-delta` / `maximum-percent-delta` (50.00 / 50%) |
| `BELOW_MINIMUM_CONFIDENCE`, `BELOW_MINIMUM_COMPARABLE_COUNT` | confidence < `minimum-confidence` (0.75), or comparables < `minimum-comparable-count` (3) |
| `BLOCKED_REASON_CODE`, `INELIGIBLE_REASON` | the decision's reason is in `blocked-reason-codes`, or not in `eligible-reason-codes` |
| `FIXED_PRICE`, `MISSING_FINAL_PRICE`, `MISSING_CURRENT_PRICE`, `CURRENCY_MISMATCH` | there is nothing valid to send |
| `NO_REMOTE_LISTING`, `DRAFT_HAS_REMOTE_LISTING` | a non-draft has no lot, or an unpriced draft already has one |

A draft's first price is checked against confidence and comparable count only. Confidence by comparable count:
5+ is 0.90, 3–4 is 0.75, 2 is 0.60, 1 is 0.40. Keep the algorithm's change limits at or below the guard's
maximums, so a big correction happens over several runs instead of being blocked.

## Marketplace sync: `BricklinkMarketplaceSyncJob` (every 10 min in SANDBOX and DEV)

The only code that writes to BrickLink lots. It takes `PENDING` requests that are due, `batch-size` (5) per run:

| Type | What it does |
| --- | --- |
| `PRICE_UPDATE` | Reads the lot and checks safety, writes the price (and, outside PROD, the stockroom), reads it back. Only then does it move the local price and mark the decision `APPLIED`. |
| `LISTING_CREATE` | Re-checks the draft locally. Reads the whole store and compares each lot's ownership block with the item's UUID (never the item number). A lot for this listing, left by an earlier lost response, is taken over. A lot for the same item under **another listing** blocks the create with `REMOTE_LOT_EXISTS_FOR_ITEM` (HOMELAB-100). The same item under another environment's block is only logged. Otherwise it creates the lot and saves its id at once. Reads it back, checking ownership, stockroom and quantity 1, then marks the listing `ACTIVE`. A lot that fails the read-back is hidden in a stockroom (A in PROD, the environment's own elsewhere). |
| `DESCRIPTION_UPDATE` | Writes only the lot's Description (see "The lot Description" below). The read-back must show the text and unchanged remarks (HOMELAB-80). A text over 255 characters is `BLOCKED` with `DESCRIPTION_TOO_LONG` before anything is sent. |
| `LISTING_DELETE` | Seeded-item teardown (HOMELAB-40): deletes a lower-environment lot that is in this environment's stockroom and carries its ownership block. It is refused in PROD, and success is proven by the lot reading back as not found. The listing becomes `ENDED`. |

**The lot Description (HOMELAB-82)** is one rule, `BricklinkLotText` in lego-data, also used by lego-data-service:
1. the listing's description, otherwise its title;
2. `<a href="ALBUM">View all photos</a>.`, when the item's Flickr album has an address (its short link first);
3. "See Extended Description for additional Item Condition notes.", when a processed photo has a caption **and**
   the Extended Description sync runs in `APPLY` here (otherwise buyers would be pointed at an empty field).

A text on its own is sent as typed; with a link or the sentence after it, it ends with a period. BrickLink keeps
the anchor tag (the link is clickable) but drops a `target` attribute, stores `&` as `&amp;`, and **silently cuts
the stored text at 255 characters**, mid-link if need be (measured 2026-11-19). So the length is counted as stored,
and a text that does not fit is never sent or trimmed: the owner shortens the listing's description or title.
lego-data-service refuses such an edit with the length in the message.

Request states: `PENDING → CLAIMED → SUCCEEDED | BLOCKED | FAILED`.
- **`BLOCKED`** is final. A safety check said no, and retrying would repeat an unsafe write. `last_error_message`
  starts with the check's code. BrickLink's own refusal of a change, `409 RESOURCE_UPDATE_NOT_ALLOWED`, is
  `BLOCKED` with `REMOTE_UPDATE_NOT_ALLOWED`: a retry would send the same change.
- **`FAILED`** means the request used up `max_attempts` (3). A claim spends an attempt; a failure that is not an
  outage puts the request back to `PENDING` after `retry-backoff` (6 h).
- A request left `CLAIMED` for longer than `stale-claim-timeout` (1 h), for example by a killed pod, is released
  at the start of the next run.

**BrickLink outages (HOMELAB-81).** A redirect, 401/403/429, 5xx or I/O failure is confirmed with one read-only
probe (`GET /colors/1`). If the probe fails, the request goes back to `PENDING` with its attempt given back, its
decision stays `ACCEPTED`, and the run stops (outcome `OUTAGE`). If BrickLink answers the probe, the failure
belongs to that request, and the attempt counts.

Safety-check codes you may see:
- **Local create checks:** `LISTING_NOT_DRAFT`, `REMOTE_INVENTORY_ALREADY_EXISTS`, `INVENTORY_NOT_SELLABLE`,
  `MISSING_BRICKLINK_COLOR_ID`, `REQUEST_PRICE_MISMATCH`, `NON_PROD_PUBLIC_VISIBILITY_FORBIDDEN`, `REMARKS_TOO_LONG`, …
- **Remote checks:** `REMOTE_NOT_STOCKROOM`, `REMOTE_STOCKROOM_MISMATCH`, `REMOTE_SYSTEM_BLOCK_MISSING`,
  `REMOTE_SYSTEM_BLOCK_ENV_MISMATCH`, `REMOTE_SYSTEM_BLOCK_LISTING_MISMATCH`, `REMOTE_SYSTEM_BLOCK_INVENTORY_MISMATCH`.
- **Store search before a create:** `REMOTE_LOT_EXISTS_FOR_ITEM` (the item already has a lot under another
  listing; the message names the lot and that listing), `DUPLICATE_REMOTE_INVENTORY` (several lots for this listing).
- **Read-back:** `REMOTE_PRICE_MISMATCH`, `REMOTE_QUANTITY_MISMATCH`,
  `REMOTE_DESCRIPTION_MISMATCH`, `REMOTE_REMARKS_MISMATCH`.
- **The text itself:** `DESCRIPTION_TOO_LONG` (fix the listing's text), `REMOTE_UPDATE_NOT_ALLOWED` (BrickLink 409).

Mode `DRY_RUN` verifies and logs without claiming or writing. The `local` environment is always `DRY_RUN`.

## Store checks (read-only)

- **`BricklinkInventoryReconciliationJob`** reads the whole store once per run and reports drift against the
  local listings. It never writes to BrickLink. Drift types:
  - `MISSING_REMOTE_LOT` (usually sold);
  - `PRICE_DRIFT`, `QUANTITY_DRIFT`, `VISIBILITY_DRIFT`, `REMARKS_DRIFT`, `DESCRIPTION_DRIFT`;
  - `DESCRIPTION_TOO_LONG` (the text the listing calls for is over 255 characters as BrickLink stores it, for
    example after the album link arrived; fix the listing's text, nothing is queued);
  - `UNTRACKED_REMOTE_LOT` (the block names this environment, but no listing points at the lot).

  Changes still in flight are not reported. Each drift logs `bricklink.inventory_reconciliation.drift`.
  With `inventory-reconciliation.queue-description-updates: true` (SANDBOX), each run also queues a
  `DESCRIPTION_UPDATE` for every `DESCRIPTION_DRIFT` (`bricklink.description_drift.queued`). That is how a new
  album link, or a caption added or removed, reaches the lot. A listing whose last `DESCRIPTION_UPDATE` ended
  without success within `retry-backoff` (6 h) is held back.
- **`BricklinkLotAuditJob`** checks independently that no lower-environment lot is public
  (`LOWER_ENV_LOT_PUBLIC`), in stockroom A, or in another environment's stockroom. It logs
  `bricklink.lot_audit.violation` at ERROR and never moves a lot: move it back by hand, then find out how it got
  there.
- **Description backfill:** `POST /internal/bricklink/marketplace-sync/description-backfill?apply=false` lists
  the `DESCRIPTION_UPDATE` requests it would queue for every `DESCRIPTION_DRIFT`; `apply=true` queues them.
  Always run the dry run first.

## Web gateway syncs (lot image, Extended Description)

Both write through this environment's `bricklink-web-gateway` (`lego.bricklink.web-gateway.base-url`, caller token
from `bricklink-web-gateway-caller.yml`), which holds the signed-in BrickLink website session. Both read the lot
through the official API and run the same ownership and stockroom checks first; the gateway checks the stockroom
again. A gateway that is signed out or unreachable, or a BrickLink API outage, puts the request back to `PENDING`
with its attempt given back (`deferral-delay`, 30 min) and stops the run. Each writes only when its own `mode` and
the marketplace sync's mode are both `APPLY`.

| Job | Request type | What it keeps in step | Recorded on `bricklink_marketplace_listing` |
| --- | --- | --- | --- |
| `BricklinkLotImageSyncJob` (HOMELAB-92) | `LOT_IMAGE_UPDATE` | the lot's custom image = the item's primary photo | `lot_image_md5`, `lot_image_synced_at` |
| `BricklinkExtendedDescriptionSyncJob` (HOMELAB-82) | `EXTENDED_DESCRIPTION_UPDATE` | the lot's Extended Description = the captions of the item's processed photos, one per line, in photo order, each once; cleared when none is left | `extended_description_hash` (SHA-256 of the text sent), `extended_description_synced_at` |

Each run compares the local database only (no BrickLink request), queues a request for every listing that is out
of date, and sends `batch-size` (3) of them. The text is built again when it is sent. Hand edits made on BrickLink
are not noticed. If the text the gateway reads back is only the start of the one sent, BrickLink cut it short: the
request is `BLOCKED` with `EXTENDED_DESCRIPTION_TRUNCATED` and both lengths (shorten the captions). Log events:
`bricklink.extended_description.{queued,sent,deferred,blocked,truncated,dry_run_verified}`.
Where they run: SANDBOX (both `APPLY`), DEV (both `DRY_RUN`), PROD (off until the gateway is there, HOMELAB-114).

## Orders and fulfillment

- **`BricklinkOpenOrderProbeJob`** reads open BrickLink orders. With `apply=true` it stages them in this
  environment's database (`marketplace_order`, `marketplace_order_item`, `marketplace_order_payload`, one
  `marketplace_order_sync_run` per run). It never writes to BrickLink.
  While BrickLink cannot be used (a redirect such as the maintenance page, 401 such as `TOKEN_IP_MISMATCHED`, 403,
  429, 5xx, I/O or timeout) a run logs one WARN `bricklink.order_sync.probe.outage_deferred`, records the run as
  `OUTAGE_DEFERRED` and counts `bricklink_order_sync{outcome="outage_deferred"}`; the next run that reaches BrickLink
  reads every open order. This is the same rule the queued syncs use (`BricklinkRestOutageClassifier`). A refused
  token therefore shows up only as WARN lines and deferred runs and requests: it needs its own alert.
  The lot audit and the inventory reconciliation log the same cases as WARN `bricklink.lot_audit.outage` and
  `bricklink.inventory_reconciliation.outage` (no stack trace); their report outcome stays `FAILED`.
- **`FulfillmentScheduledSyncJob`** maps invoiced, staged orders to ShipStation orders, records shipments and
  marks BrickLink orders shipped. It writes only when `lego.fulfillment.sync.scheduled.apply=true` **and**
  `lego.environment.production=true`. Elsewhere it logs `fulfillment.sync_job.apply_suppressed` and maps only.

## Photos, catalog and image hosting

S3 upload events arrive on Kafka (one topic per upload type, per environment). A photo is filed by its `uuid`
keyword onto an existing inventory item. A photo that cannot be filed is moved to `photos/rejected/` with the
reason logged as `photo.process.rejected`: no `uuid` or `bl` keyword, a uuid that matches no inventory item
(HOMELAB-97; it used to create a bare item), an unknown catalog number, an unreadable image, a file that is
not a JPEG, a file over the upload limit, or a conflict under the sealed / built-once rules below.

**A photo is stored exactly as uploaded (HOMELAB-99).** Ingestion no longer scales or re-encodes a photo: the
bytes exported from the photo editor are the bytes kept in `lego-photos-<env>` and published to the image
host. It used to re-encode every photo as a 75%-quality JPEG of at most 2 MB.

- The photo's fingerprint (`item_inventory_photo.md5`) is taken from the picture alone
  (`PhotoFingerprintService`, HOMELAB-117): the file without its Exif and XMP (APP1), its Photoshop and IPTC
  block (APP13) and its comments. The colour profile and all picture data count. So two exports of one
  picture that differ only in keywords or caption have the same fingerprint, and a keyword-only re-upload
  updates the text without replacing the photo. The stored file is then not rewritten: it keeps the keywords
  it was first uploaded with, and the database holds the current ones.
- A photo stored before these changes has an older kind of fingerprint, so re-uploading the same export
  counts as new pixels once: the stored file is replaced, and the published photo with it (see "A
  re-uploaded photo" below).
- The size is decided by the export, so the export must carry the agreed size cap. An upload larger than
  `lego.photos.max-upload-bytes` (default 10,000,000; zero or less turns the limit off) is rejected.
- Only JPEG is accepted; nothing converts another format any more.
- The only scaled copy is made when the lot image is sent to BrickLink: the lot image sync scales the
  primary photo down in memory when it is over `lot-image.max-image-bytes` (2,000,000). That copy is not
  stored.

**What a photo may change on its item (HOMELAB-96).** A photo changes only what its keywords say: the three
condition grades (`item:`, `bc:`, `ic:`), and sealed, built once and New/Used under the rules below. Everything
else on the item stays as entered, including its description: a caption is stored on the photo
(`item_inventory_photo.caption`) and no longer overwrites `item_inventory.description`.

**The file's caption is the photo's caption, also when it has none (HOMELAB-82).** Lightroom writes nothing for
an empty caption, so a replacement upload (same item, same file name) without a caption clears the stored caption.
That is how the owner removes one. The cleared caption then leaves the Flickr photo and album descriptions and the
lot's Extended Description, and the lot Description loses its "See Extended Description" sentence when no caption
is left. An export preset that drops metadata clears captions the same way, so export with All Metadata.

**The cover is different: it stays until another photo claims it (HOMELAB-131, owner decision 2026-11-21).**
A replacement without the `primary` keyword keeps the photo's `is_primary` (`effectivePrimaryPhoto`). The cover
moves only when another photo is uploaded with `primary` (`setPrimaryPhoto`), or when a photo is uploaded with
`primary:false`. So re-exporting the cover without its keyword never leaves an album without a cover.

**Sealed and built-once rules** (`PhotoKeywordRules`), across all photos of one item. The photos win over what was
entered at intake. `sealed` alone means `sealed:true`.

| Photos of the item say | Result |
| --- | --- |
| sealed on any photo, `bo:true` on none | Item sealed, built once false, New. The lot is created with BrickLink's Sealed indicator. |
| no photo sealed, `bo:true` | Used |
| no photo sealed, `bo:false` | New |
| `sealed:false` | Not sealed; it may sit beside `bo:true` |
| sealed on any photo and `bo:true` on any photo (the same one included) | Conflict |
| two photos disagree (`bo:true` / `bo:false`, or `sealed:true` / `sealed:false`) | Conflict |
| no sealed and no bo keyword on any photo | Nothing changes |

A conflict rejects the photo being processed, which is the one that arrived second; the log line names the other
photo. A re-uploaded photo is judged against the item's other photos, not its own earlier values, and its stored
values are replaced. Each photo's own values are kept in `item_inventory_photo.sealed_keyword` and
`built_once_keyword` (NULL = no such keyword), with its raw keyword list in `keywords`. The check runs again
under the item's row lock when the photo is written, because several consumers can process one item's photos at
once. A lot that is already live is not updated when New/Used changes.

Listeners process photos
(`PhotoUploadS3EventListener`, `PhotoDeletedS3EventListener`), split BrickLink catalog and category XML, and
split Rebrickable catalog and theme CSVs into per-row topics. Per-row consumers upsert `external_catalog_item`
and `external_category`. Topics and groups are in `kafka.topic-configuration.*` (`KafkaCustomProperties`).

`ImageHostingScheduledSyncJob` keeps each item's photos in its Flickr album, through
lego-imaging. Manual endpoints are under `/internal/image-hosting/item-inventories/{id}/`: `sync-plan`,
`sync-plan/apply`, `repair-plan`, `repair-plan/apply` and `generated-description`. Prefer a plan, then apply it.

**Short album links are Flickr's own (HOMELAB-128).** An album's `short_url` is `https://flic.kr/s/` plus the
album id in base58, calculated in every environment with no API call. It replaced the production-only bit.ly
links. Each new album's link is opened once, signed out; a WARN `image_hosting.short_url.check.wrong_page`
means Flickr may have changed the form, so check the link by hand.

To move existing albums (bit.ly or empty) to their flic.kr link, run the backfill as a dry run first:
`POST /internal/image-hosting/short-url-backfill`. Read `rows[].bricklinkListings`: a lot with `tooLong: true`
would pass BrickLink's 255 characters with the new link, so shorten that listing's description first. Then run
it with `?apply=true`. Each changed album is marked `PENDING`, so the next scheduled image-hosting sync rewrites
its Flickr description with the new link (`UPDATE_ALBUM_METADATA`) and marks it `SYNCED` again; the BrickLink lot
Descriptions follow through reconciliation's `DESCRIPTION_DRIFT`. This is also the step that moves the legacy PROD
listings from bit.ly to flic.kr at the PROD cutover.

**The database is the system of record; Flickr is a published copy** (owner decision 2026-11-17).
- What Flickr should hold (photos, cover, album title and description) is computed from the database and the
  final-photo bucket.
- Repairs go from the database to Flickr. A change made directly on Flickr is drift: a sync plan reverses it.
- Only linkage is read back from Flickr. When an existing album belongs to the item, `repair-plan` records the
  album and photo ids, which of the item's photos the album already holds, and the album's current title. That
  records what Flickr has; it does not decide what Flickr should have. The next sync plan still brings the
  album to the database's state.
- Each environment has its own Flickr account (PROD: the Pro account; SANDBOX and DEV: one free account each).
  A free account holds 1,000 items and Flickr deletes the oldest above that. Do not repair those deletions:
  re-uploading puts the account over the limit again. Free space instead.

**Which album belongs to which item.** An album's title is `<item number> - <item name>`, so every copy of one
set has the same title. The item's UUID tells the copies apart (`ImageHostingAlbumIdentity`):
- Every album description the system writes ends with `Item reference: <uuid>`.
- An item with no album id is linked to an existing album only when that album's description carries the item's
  UUID. Legacy albums, whose whole description is the UUID, are recognised.
- No album carries the UUID: a new album is created, even when another album has the same title.
- Several albums carry the UUID: the plan is blocked as ambiguous. Remove or re-describe the duplicate on Flickr.

**Creating an album takes three calls.** Flickr creates an album from its cover photo alone, so the system then
puts every photo into it, and writes the description again once the short link exists (the first description
cannot hold the link). If either follow-up fails, the album keeps its id and is marked `FAILED`; the next job run
compares it with Flickr and repairs it. To check one album by hand:
`GET /internal/image-hosting/remote/albums/{albumId}/snapshot` shows what Flickr holds, and
`GET /internal/image-hosting/item-inventories/{id}/sync-plan` shows what would be repaired.

The job picks its work from database state. An album the database believes is in sync but Flickr holds
differently is not revisited by the job; the nightly difference report (below) finds it.

**Removing photos.** A photo that leaves the database leaves Flickr too (`HostedContentRemovalService`).

- **Delete one photo (every environment):** remove its object from the final-photo bucket
  (`lego-photos-<env>/<item>/<uuid>/<md5>.jpg`). The system deletes the photo on Flickr, the rows that link it,
  and the photo row, then brings the album back in step (its description lists captions). When that was the
  item's last photo, Flickr drops the empty album itself and the album row is removed. If Flickr refuses the
  deletion, nothing is removed and the event is retried; the log line is `image_hosting.removal.*` or
  `photo.delete.*`. Deleting the cover photo leaves the item without one: tag another photo `primary`.
- **Remove all of an item's photos (lower environments only):**
  `GET /internal/image-hosting/item-inventories/{id}/hosted-content` lists what would go;
  `POST .../hosted-content/purge` removes the photos from Flickr, the photo bucket and the database, and the
  album rows. Run it before a seeded-item teardown, which refuses an item that still has photos or an album.
  It can be repeated if it stops part-way.
- **Photos no item owns (lower environments only):** `GET /internal/image-hosting/orphans` lists every photo and
  album on the account that the database does not know; `POST /internal/image-hosting/orphans/delete`
  (`maxDeletes`, default 200) deletes those photos. Run it after a database reset. An album listed with
  `knownPhotos` above zero holds photos an item does own: look at it before deleting.

The two lower-environment operations answer 403 in production. The production account holds albums made by the
legacy system that this database does not know yet; a sweep there would call them all orphans.

**A re-uploaded photo.** When a photo is uploaded again with the same file name and new pixels, the sync job
replaces it on Flickr (plan action `REPLACE_PHOTO`). The job finds it by comparing the stored file's hash
(`item_inventory_photo.md5`) with the hash recorded at upload (`external_image.md5_at_upload`); the job's
`candidates_selected` log line counts these as `contentChanged`.

- PROD (Pro account): Flickr swaps the file in place. Same photo id, same album, nothing else changes.
- SANDBOX and DEV (free accounts, where Flickr refuses replace): the new file goes up as a new photo, takes
  the old photo's place in the album, and the old photo is deleted. The photo's Flickr id and URL change.
- A failed replacement is tried again on the next run. Log lines: `image_hosting.photo_replace.completed`,
  `.skipped`, `.failed`, `.old_photo_not_removed`; metric `image_hosting_photo_replace` by `outcome`.
- Setting: `lego.image-hosting.sync.photo-replacement.mode` (`AUTO`, `IN_PLACE`, `UPLOAD_AND_SWAP`).

**Staying under the account's item limit.** A free Flickr account (SANDBOX, DEV) holds 1,000 items and Flickr
deletes the oldest above that. Putting deleted photos back only makes Flickr delete others, so the system stops
before the limit (`ImageHostingHeadroomGuard`).

- Before a plan uploads new photos, the account's count and limit are read (and kept for
  `cache-seconds`). When the count plus the plan's new photos would pass `limit - reserve-items` (default
  1,000 - 100 = 900), the whole plan is held back: its actions come back `SKIPPED` with the reason, nothing is
  uploaded and nothing is marked failed. The item stays a candidate and is published once there is room.
- Updates to photos already on Flickr, album changes and deletions are never held back.
- An account with no limit (PROD's Pro account) is never held back. If the account cannot be read, publishing
  goes ahead and `image_hosting.headroom.unreadable` is logged.
- See where the account stands: `GET /internal/image-hosting/account-usage`. Log lines:
  `image_hosting.headroom.account_usage` (each reading) and `image_hosting.headroom.blocked` (a plan held back);
  metric `image_hosting_headroom_blocked`.
- To free space: delete photos no item owns (`/orphans/delete`), or remove the photos of items no longer needed
  (`/hosted-content/purge`).
- Settings: `lego.image-hosting.sync.headroom.enabled`, `reserve-items`, `cache-seconds`.
- Not covered: the legacy direct sync endpoint (`POST .../sync`), which is not used by the scheduled job.

**Nightly difference report.** Once a night (03:30 Eastern) every album the database knows is compared with
Flickr (`ImageHostingDifferenceReportService`). It reports and repairs nothing: no change on Flickr, none in the
database.

- **Read it:** `GET /internal/image-hosting/differences` returns the last report (204 when none has run since the
  service started; reports are kept in memory, so a restart clears it). `POST /internal/image-hosting/differences/run`
  compares now and returns the report. Log lines: `image_hosting.difference_report.completed` (the counts),
  `.album_differs` (one per album, at WARN), `.album_pending`, `.failed`. Metrics: `image_hosting_difference_report`
  (runs, by `outcome`), `image_hosting_difference_report_albums_checked` and `..._albums_different` (last
  complete run).
- **How it compares:** the album listing is read once for the whole account and gives each album's title,
  description, cover and photo count. An album's photo list is read only when the listing shows something off, or
  when it is the album's turn: every album is read in full once in `full-check-days` nights (default 7), because
  Flickr's photo count is a hint, not proof. `albumsReadInFull` says how many were read that night. What counts
  as a difference is decided by the same planner as an item's sync plan.
- **A finding and what to do.** Each finding carries `whatToDo`.

  | Finding | Meaning | What to do |
  | --- | --- | --- |
  | `TITLE`, `DESCRIPTION`, `COVER`, `PHOTO_ORDER`, `PHOTOS_NOT_IN_ALBUM` | Changed on Flickr; the photos are still there. | `POST /internal/image-hosting/item-inventories/{id}/sync-plan/apply` |
  | `EXTRA_PHOTOS` | The album holds photos the database does not list for the item. | Look at them, then apply the sync plan with `allowReviewRequired=true`. |
  | `PHOTOS_DELETED` | Flickr no longer has the photo, in the album or anywhere on the account. | The sync plan cannot bring it back. Lower environments: purge the item's hosted content and upload the photos again. Production: no procedure yet. |
  | `ALBUM_MISSING` | The album is gone. The detail says how many of its photos are left on the account. | As for `PHOTOS_DELETED`. |
  | `PHOTOS_NOT_PUBLISHED`, `PHOTO_REPLACEMENT_PENDING`, `PHOTO_METADATA_PENDING` | Work the database already shows as outstanding. | Nothing; the sync job does it. If it stays night after night, look at the job (headroom, failures). |
  | `CHECK_FAILED` | The album could not be compared. | Read the detail; the next run tries again. |

- **`accountLimitLikely`** on `PHOTOS_DELETED` or `ALBUM_MISSING` means the account is limited and at or past
  the count where publishing stops, so Flickr deleting its oldest items is the likely cause. Putting those photos
  back would only make Flickr delete others: free space first (see above).
- **`complete: false`** means the album listing could not be read; nothing is reported as missing on a partial
  view. `message` says why.
- `albumsOnHostUnknown` counts albums on the account the database does not know. In production those are the
  legacy system's albums; in a lower environment see the orphan list.
- Settings: `lego.image-hosting.sync.difference-report.enabled` (on under the `kubernetes` profile), `cron`,
  `zone`, `full-check-days`.

## Operating

**Deploy.**
- A push to a feature branch builds and deploys the SANDBOX preview.
- A merge to `develop` deploys DEV.
- `main` deploys PROD behind a gate.

When lego-data changes too, check that the build used the new lego-data snapshot (the
`lego-data-mybatis-1.1.0-<timestamp>.jar` line in the build log) before a schema drop runs. A feature build can
pick up the previous snapshot.

**Turning a job on** is a profile change plus a deploy. Set both `enabled` and `scheduled.enabled`. For anything
that writes, start with `mode: DRY_RUN` (or `apply: false`), read the logs and decisions, then switch to
`APPLY`. Never set `lego.environment.production=true` outside `application-prod.yml`.

**Credentials** come from `/etc/.credentials`, projected by each environment's ExternalSecret from Infisical (that
environment plus the shared `global` environment, which holds the BrickLink API keys; HOMELAB-76). Locally they come
from `/dev/config`. A missing file fails startup.

Flickr uses two files. `flickr-api-keys.yml` (Global) holds the API application's `flickr.secrets.key` and
`flickr.secrets.secret`, one copy for every environment. `flickr-configuration.yml` (per environment) holds that
environment's Flickr account: `flickr.userId`, `flickr.secrets.token` and `flickr.secrets.tokenSecret`. SANDBOX,
DEV and PROD each publish to their own Flickr account.

**Endpoints.** All are internal and behind cluster access. OpenAPI is at `/swagger-ui/index.html`.

| Endpoint | Does |
| --- | --- |
| `GET /internal/bricklink/pricing/maintenance-report` | Crawl queue by state and the latest failed attempts |
| `GET /internal/bricklink/pricing/data-quality-report` | Priceable listings missing condition, completeness, catalog item, item number or type, BrickLink `idItem`, or box/instructions grade |
| `POST /internal/bricklink/inventory/reconciliation` | Run the reconciliation now (404 when off) |
| `POST /internal/bricklink/inventory/lot-audit` | Run the lot audit now (404 when off) |
| `POST /internal/bricklink/marketplace-sync/description-backfill?apply=false` | See above |
| `/internal/image-hosting/...` | See above |

**Watching it.** The Grafana dashboard `lego-data-ingress` has a row per plane. Every job logs one
`*.job.completed` line with its result, and records `<job>_total{outcome}` and `<job>_duration`. Key series:

- `bricklink_pricing_crawl_job_total{outcome}` and `bricklink_pricing_crawl_target_current{state}`, the crawl
  queue. A ban shows as failures rising while successes stay flat.
- `pricing_reprice_decision_total{result}` and `bricklink_pricing_decision_current{status}`.
- `bricklink_marketplace_sync_request{result}`, including `outage_deferred`, and
  `bricklink_marketplace_sync_request_current{state}`.
- `bricklink_pricing_data_quality_gaps_current{gap}`.
- the reconciliation drift and lot-audit violation gauges.

## Diagnosis

| Symptom | Look at |
| --- | --- |
| A listing never gets a decision | `data-quality-report` for its gaps. Then its `want_list_item` row and its `crawl_target` (below). With `require-current-snapshot`, no snapshot means no decision. |
| Decisions are `FAILED` `NO_EXACT_COMPARABLES` / `NO_CURRENT_COMPARABLES` | A thin market for that condition and sub-condition. Price it by hand, or wait for the next crawl. |
| Decisions are `BLOCKED` | `guard_reason_code`. Large moves are spread over runs by the change limit. A block means the move exceeded the guard. |
| Decisions stay `PROPOSED` | Reprice is in `DRY_RUN` (DEV). |
| An `ACCEPTED` decision never applies | Its sync request: status, `attempt_count`, `next_attempt_at`, `last_error_message`. Marketplace sync must be in `APPLY`. |
| A request is `BLOCKED` | The code at the start of `last_error_message`, listed under the marketplace sync safety codes above. Fix the cause. A new decision queues a new request. |
| Requests `FAILED`, `TOKEN_IP_MISMATCHED` or 401 everywhere | The home IP changed, and the IP-bound BrickLink token stopped working. Re-register the IP on BrickLink. Outage handling keeps attempts from being spent meanwhile. |
| Crawl outcome `OUTAGE` every run | BrickLink is down or blocking. Nothing to reset; it resumes on its own. |
| Crawl outcome `BLACKOUT` | Inside the blackout window. Expected. |
| `bricklink.lot_audit.violation` | Move the lot back to the right stockroom by hand, then investigate. |
| `fulfillment.sync_job.apply_suppressed` | Apply was requested outside PROD. Expected; set `apply: false` in that profile. |
| Startup fails on the environment check | The profile and the database disagree. Check the active profiles and the database configuration. |

Read-only SQL (connect with the read-only identity; replace the schema with the environment's):

```sql
-- Latest decision per listing, with its guard outcome and sync request
SELECT pd.marketplace_listing_id, pd.pricing_decision_id, pd.created_at, pd.decision_status_code,
       pd.reason_code, pd.guard_status_code, pd.guard_reason_code, pd.previous_price, pd.final_price,
       sr.sync_request_type_code, sr.sync_request_status_code, sr.attempt_count, sr.last_error_message
FROM pricing_decision pd
JOIN (SELECT marketplace_listing_id, MAX(pricing_decision_id) AS pricing_decision_id
      FROM pricing_decision GROUP BY marketplace_listing_id) latest
  ON latest.pricing_decision_id = pd.pricing_decision_id
LEFT JOIN marketplace_listing_sync_request sr ON sr.pricing_decision_id = pd.pricing_decision_id
ORDER BY pd.marketplace_listing_id;

-- The sync queue
SELECT sync_request_status_code, sync_request_type_code, COUNT(*), MIN(next_attempt_at)
FROM marketplace_listing_sync_request
GROUP BY sync_request_status_code, sync_request_type_code;

-- What the shared crawler knows about one item (BrickLink type + number)
SELECT ci.item_type_code, ci.item_number, ct.query_condition_code, ct.target_status_code, ct.next_due_at,
       ct.last_success_at, ct.consecutive_failure_count, ct.last_error_message
FROM marketplace_pricing.canonical_item ci
JOIN marketplace_pricing.crawl_target ct ON ct.canonical_item_id = ci.canonical_item_id
WHERE ci.item_type_code = 'S' AND ci.item_number = '6390-1';

-- Who wants it, and its latest snapshots
SELECT w.environment_code, w.condition_code, w.completeness_code, w.last_confirmed_at
FROM marketplace_pricing.want_list_item w
JOIN marketplace_pricing.canonical_item ci ON ci.canonical_item_id = w.canonical_item_id
WHERE ci.item_number = '6390-1';

SELECT ms.market_snapshot_id, ms.query_condition_code, ms.listing_count, ms.captured_at
FROM marketplace_pricing.market_snapshot ms
JOIN marketplace_pricing.canonical_item ci ON ci.canonical_item_id = ms.canonical_item_id
WHERE ci.item_number = '6390-1'
ORDER BY ms.captured_at DESC LIMIT 5;
```

## Configuration

Defaults live in `application.yml`; each environment's overrides in `application-<env>.yml`. The full key list
and defaults are in these classes (`src/main/java/io/legohunter/ingress/` unless noted):

| Prefix | Class | Notes |
| --- | --- | --- |
| `lego.environment` | lego-data starter | `code`, `production`; checked against the database at startup |
| `lego.bricklink.pricing.want-list` | `pricing/BricklinkWantListSyncProperties` | |
| `lego.bricklink.pricing.crawl` | `pricing/BricklinkPricingCrawlProperties` | cadence, backoff, blackout, batch size |
| `lego.marketplace-pricing.schema` | lego-data | the shared schema's name (`marketplace_pricing`) |
| `lego.pricing.reprice` | `pricing/RepriceProperties` | mode, batch, hand-off limit |
| `lego.pricing.reprice.algorithm` | `pricing/RepriceAlgorithmProperties` | the formula's levers |
| `lego.pricing.reprice.guard` | `pricing/RepriceGuardProperties` | |
| `lego.bricklink.marketplace-sync` | `marketplace/bricklink/BricklinkMarketplaceSyncProperties` | mode, stockroom, backoff, stale claims |
| `lego.bricklink.inventory-reconciliation` | `marketplace/bricklink/BricklinkInventoryReconciliationProperties` | |
| `lego.bricklink.lot-audit` | `marketplace/bricklink/BricklinkLotAuditProperties` | |
| `lego.bricklink.orders.sync` | `orders/BricklinkOrderSyncProperties` | |
| `lego.fulfillment`, `shipstation.rest` | `io/legohunter/egress/fulfillment/FulfillmentSyncProperties`, `.../config/FulfillmentShipStationRestProperties` | |
| `lego.image-hosting` | `io/legohunter/egress/imagehosting/ImageHostingSyncProperties` | |
| `lego.s3` | `config/storage/S3ClientProperties`, `S3BucketProperties` | |
| `kafka` | `config/kafka/KafkaCustomProperties` | topics and consumer groups |
| `bricklink.rest`, `bricklink.ajax` | the bricklink-rest / bricklink-ajax client libraries | timeouts, AJAX rate limit (keep it on) |

`RepriceConfigurationBindingTest` checks that the SANDBOX and DEV profiles bind the guard and selection settings.
If you rename a key, that test fails rather than the key silently falling back to a default.
