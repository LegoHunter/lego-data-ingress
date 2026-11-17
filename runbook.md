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
  Images:     ImageHostingScheduledSyncJob -> Flickr albums + Bitly links (via lego-imaging)
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
| `DESCRIPTION_UPDATE` | Writes only the lot's Description, from the listing's description or else its title. The read-back must show the text and unchanged remarks (HOMELAB-80). BrickLink caps the REST Description at 255 characters. |
| `LISTING_DELETE` | Seeded-item teardown (HOMELAB-40): deletes a lower-environment lot that is in this environment's stockroom and carries its ownership block. It is refused in PROD, and success is proven by the lot reading back as not found. The listing becomes `ENDED`. |

Request states: `PENDING → CLAIMED → SUCCEEDED | BLOCKED | FAILED`.
- **`BLOCKED`** is final. A safety check said no, and retrying would repeat an unsafe write. `last_error_message`
  starts with the check's code.
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

Mode `DRY_RUN` verifies and logs without claiming or writing. The `local` environment is always `DRY_RUN`.

## Store checks (read-only)

- **`BricklinkInventoryReconciliationJob`** reads the whole store once per run and reports drift against the
  local listings. It never writes. Drift types:
  - `MISSING_REMOTE_LOT` (usually sold);
  - `PRICE_DRIFT`, `QUANTITY_DRIFT`, `VISIBILITY_DRIFT`, `REMARKS_DRIFT`, `DESCRIPTION_DRIFT`;
  - `UNTRACKED_REMOTE_LOT` (the block names this environment, but no listing points at the lot).

  Changes still in flight are not reported. Each drift logs `bricklink.inventory_reconciliation.drift`.
- **`BricklinkLotAuditJob`** checks independently that no lower-environment lot is public
  (`LOWER_ENV_LOT_PUBLIC`), in stockroom A, or in another environment's stockroom. It logs
  `bricklink.lot_audit.violation` at ERROR and never moves a lot: move it back by hand, then find out how it got
  there.
- **Description backfill:** `POST /internal/bricklink/marketplace-sync/description-backfill?apply=false` lists
  the `DESCRIPTION_UPDATE` requests it would queue for every `DESCRIPTION_DRIFT`; `apply=true` queues them.
  Always run the dry run first.

## Orders and fulfillment

- **`BricklinkOpenOrderProbeJob`** reads open BrickLink orders. With `apply=true` it stages them in this
  environment's database (`marketplace_order`, `marketplace_order_item`, `marketplace_order_payload`, one
  `marketplace_order_sync_run` per run). It never writes to BrickLink.
- **`FulfillmentScheduledSyncJob`** maps invoiced, staged orders to ShipStation orders, records shipments and
  marks BrickLink orders shipped. It writes only when `lego.fulfillment.sync.scheduled.apply=true` **and**
  `lego.environment.production=true`. Elsewhere it logs `fulfillment.sync_job.apply_suppressed` and maps only.

## Photos, catalog and image hosting

S3 upload events arrive on Kafka (one topic per upload type, per environment). A photo is filed by its `uuid`
keyword onto an existing inventory item. A photo that cannot be filed is moved to `photos/rejected/` with the
reason logged as `photo.process.rejected`: no `uuid` or `bl` keyword, a uuid that matches no inventory item
(HOMELAB-97; it used to create a bare item), an unknown catalog number, an unreadable image, or a conflict
under the sealed / built-once rules below.

**What a photo may change on its item (HOMELAB-96).** A photo changes only what its keywords say: the three
condition grades (`item:`, `bc:`, `ic:`), and sealed, built once and New/Used under the rules below. Everything
else on the item stays as entered, including its description: a caption is stored on the photo
(`item_inventory_photo.caption`) and no longer overwrites `item_inventory.description`.

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

`ImageHostingScheduledSyncJob` keeps each item's photos in its Flickr album with Bitly links, through
lego-imaging. Manual endpoints are under `/internal/image-hosting/item-inventories/{id}/`: `sync-plan`,
`sync-plan/apply`, `repair-plan`, `repair-plan/apply` and `generated-description`. Prefer a plan, then apply it.

**Which album belongs to which item.** An album's title is `<item number> - <item name>`, so every copy of one
set has the same title. The item's UUID tells the copies apart (`ImageHostingAlbumIdentity`):
- Every album description the system writes ends with `Item reference: <uuid>`.
- An item with no album id is linked to an existing album only when that album's description carries the item's
  UUID. Legacy albums, whose whole description is the UUID, are recognised.
- No album carries the UUID: a new album is created, even when another album has the same title.
- Several albums carry the UUID: the plan is blocked as ambiguous. Remove or re-describe the duplicate on Flickr.

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
