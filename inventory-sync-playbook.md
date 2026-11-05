# Inventory Sync Playbook

This is the operator guide for moving one physical LEGO item from acquisition intake to a live BrickLink inventory listing, and for understanding how the Pricing Plane participates in that journey.

It describes the behavior implemented across:

- `lego-data-service`: inventory intake, corrections, sale-intent changes, marketplace drafts, readiness, and local sync-request APIs.
- `lego-data`: shared DTOs, DAOs, MyBatis mappings, and the shared BrickLink color policy.
- `lego-data-ingress`: BrickLink pricing crawl, pricing decisions, apply-readiness, local price application, and the BrickLink marketplace-sync worker.
- `lego-database-deployer`: the nullable `marketplace_listing.unit_price` schema prerequisite for unpriced draft onboarding.
- `lego-data-migration`: the operational schema preflight and Liquibase rollout checkpoint.

The [runbook](runbook.md) remains the detailed implementation reference. This document is the shorter, procedural map for answering: “What must be true before this item can become a BrickLink listing, and where did it stop?”

## The one-minute version

The complete path is:

```text
POST /api/v1/inventory
        |
        v
item_inventory: active + AVAILABLE + KEEP + for_sale=false
        |
        |  explicitly promote sale intent to SELLABLE
        v
POST /api/v1/marketplace-listings
        |
        v
marketplace_listing: local BRICKLINK DRAFT
        |
        +--> supply a positive unitPrice manually
        |        |
        |        +--> readiness --> LISTING_CREATE request
        |
        +--> let Pricing Plane produce/apply the initial price
                  |
                  +--> crawl --> snapshot --> decision --> apply-readiness
                                                    |
                                                    +--> READY_TO_APPLY_INITIAL_PRICE
                                                    |
                                                    v
                                      local price + LISTING_CREATE request
        |
        v
marketplace_listing_sync_request: PENDING
        |
        v
lego-data-ingress worker: local safety -> BrickLink createInventory
        |
        v
BrickLink read-back and remote safety verification
        |
        v
local listing: ACTIVE + bricklink_inventory_id + last synchronization metadata
```

“Live” has an important environment qualifier. In `sandbox` and `dev`, the worker calls the real BrickLink account but forces the inventory into a stockroom and adds ownership remarks. It is live in the account, but it is not intended to be buyer-visible. In `prod`, buyer-visible inventory is allowed only when the production safety settings explicitly permit it.

## Rules that prevent most confusion

1. Acquisition intake is intentionally private. It creates inventory and accounting data; it does not create a marketplace listing or a sync request.
2. `saleIntentCode=SELLABLE` is the sale-intent gate. The legacy `forSale` flag is not the marketplace workflow gate and remains `false` for new acquisition intake.
3. `inventoryStateCode=AVAILABLE` is the physical-state gate. An item that is sold, held, damaged, or otherwise unavailable must not be listed.
4. A local marketplace draft is not a BrickLink listing. The local row stays `DRAFT` until BrickLink accepts the create request and the worker verifies the returned inventory.
5. A positive price is required before a `LISTING_CREATE` can be sent. An unpriced, non-fixed `DRAFT` is valid for Pricing Plane onboarding and reports `INITIAL_PRICE_PENDING` until the Pricing Plane applies its first price.
6. Photos are a warning, not a current BrickLink sync blocker. Listing quality may be poor without them, but the safety validator does not stop the create.
7. Pricing crawl captures market data only. It does not change a listing price and does not call a BrickLink mutation endpoint.
8. Pricing decisions are historical. Always review the latest decision for a listing, not all decisions mixed together.
9. A blocked sync request is terminal until it is investigated. Fixing code or data does not automatically reset a previously blocked/failed request.
10. A body-less manual sync POST is supported. If an old deployment returned HTTP 500 from that call, inspect the database before retrying: the transaction could have committed before the logging failure was returned.
11. Updating a marketplace listing through `lego-data-service` changes local state only. It does not automatically enqueue a BrickLink update. Description and human-remarks edits made before the first sync are included in `LISTING_CREATE`; edits made after the listing is `ACTIVE` are not currently propagated by an API workflow.

## Repository responsibilities

| Area | Repository | What it owns | What it does not do |
| --- | --- | --- | --- |
| Shared data model | `lego-data` | DTOs, DAOs, persistence mappings, sync-request persistence, and the `S`/`SET` color rule | It does not decide when to crawl, price, or sync |
| Inventory and listing API | `lego-data-service` | Intake transaction, item state/sale intent, local BrickLink draft, readiness response, and local sync-request creation | It does not call BrickLink to create the remote inventory |
| Scheduled automation and remote writes | `lego-data-ingress` | Crawl, snapshot, decision, readiness audit, apply, and final BrickLink API calls | It does not replace acquisition accounting or inventory correction APIs |
| Database schema and rollout | `lego-database-deployer` / `lego-data-migration` | Nullable `unit_price` DDL and the operator verification/rollout instructions | It does not create prices or publish marketplace inventory |

## Part I — Inventory intake to a live BrickLink listing

### 0. Confirm the prerequisites

Before starting, confirm:

- The item exists in the BrickLink catalog and the service can resolve its catalog mapping.
- The target runtime has the correct database, BrickLink credentials, Kafka/config imports, and ingress deployment.
- The target database allows `marketplace_listing.unit_price` to be null. Fresh schemas get this from
  `lego-database-deployer`; existing environments must apply Liquibase
  `1.0.3-marketplace-listing-unit-price-nullable.yaml`. Verify `information_schema.columns.is_nullable = 'YES'`
  before creating an unpriced draft. Do not seed a fake price to work around a schema mismatch.
- You know whether this is a manual-price path or a Pricing Plane path.
- You know the target environment and its stockroom: SANDBOX = `C`, DEV = `B`; `A` is PROD only (HOMELAB-20). The app refuses to start with a lower environment set to `A` or to another environment's stockroom.
- If this is a new listing in a non-production environment, the intended visibility is stockroom-only.

For a new inventory item, the important input fields are the BrickLink item number, `newOrUsed`, completeness, box number, condition codes, one unit of quantity, item-level costs, and a payment/accounting-complete transaction.

### 1. Intake the physical item

Call `lego-data-service`:

```http
POST /api/v1/inventory
Content-Type: application/json
```

The request creates one acquisition transaction and one `item_inventory` row per physical item. A request is accepted only when the accounting invariants hold:

- At least one inventory item is present.
- Every item has quantity exactly `1`.
- `forSale` must not be `true` during acquisition intake.
- Every item has item-level costs including exactly one `PRICE` cost.
- Transaction-level costs cannot use `PRICE`.
- Payments are present, use compatible currencies, and equal the sum of transaction-level and item-level costs.
- A supported transaction platform is supplied.
- The BrickLink item number resolves to a catalog item; intake creates the primary `item_inventory_external_catalog_item` link.

New inventory is deliberately initialized as follows:

| Field | Intake value | Meaning |
| --- | --- | --- |
| `active` | `true` | The row is operationally active |
| `forSale` | `false` | Legacy/private-collection flag; not the later listing gate |
| `inventoryStateCode` | `AVAILABLE` | The physical item is available unless corrected later |
| `saleIntentCode` | `KEEP` | Intake does not decide that the owner wants to sell |
| Catalog link | Primary BrickLink link | Supplies the item/type mapping used by later listing creation |
| Marketplace listing | None | No draft is created by intake |
| Sync request | None | No BrickLink mutation is queued by intake |

The response contains the persisted transaction tree. Save the returned `itemInventoryId`; it is the key used by the rest of this playbook.

### 2. Correct and inspect the inventory before selling

Use the service APIs to correct the item or transaction if needed:

```text
POST  /api/v1/inventory/search
GET   /api/v1/transactions/{transactionId}
PATCH /api/v1/inventory/{itemInventoryId}/details
PATCH /api/v1/inventory/{itemInventoryId}/state
PATCH /api/v1/inventory/{itemInventoryId}/sale-intent
```

Corrections preserve accounting rules. They do not create marketplace listings or sync rows. In particular, changing a description, condition, cost, state, or sale intent does not itself call BrickLink.

For a sellable item, confirm these values before proceeding:

```text
active = true
inventoryStateCode = AVAILABLE
saleIntentCode = SELLABLE
newOrUsed = N or U
completeness = C or I
primary BrickLink catalog link exists
```

To promote the item explicitly:

```http
PATCH /api/v1/inventory/{itemInventoryId}/sale-intent
Content-Type: application/json

{
  "saleIntentCode": "SELLABLE",
  "saleIntentNote": "Approved for BrickLink listing"
}
```

`KEEP` and `UNDECIDED` are not listing-ready. A marketplace draft create can also perform this promotion with `updateSaleIntentToSellable=true`, but making the intent change explicit is easier to audit.

### 3. Create the local BrickLink draft

Call:

```http
POST /api/v1/marketplace-listings
Content-Type: application/json
```

#### The important catalog-link detail

`externalCatalogItemId` is the local database primary key from
`external_catalog_item.external_catalog_item_id`. It is not the BrickLink item number
(`6390-1`, for example), and it is not BrickLink's internal `idItem` used by the pricing
crawler.

The selected catalog item must already be linked to the inventory row through
`item_inventory_external_catalog_item`, and it must belong to BrickLink (`external_service_id=2`).
For the normal path, use the linked row marked `is_primary=1`.

Find the value to put in the POST payload with a read-only query:

```sql
select iieci.item_inventory_id,
       iieci.external_catalog_item_id,
       iieci.is_primary,
       eci.external_item_key as bricklink_item_number,
       eci.item_type_code,
       eci.external_unique_key as bricklink_internal_id
from item_inventory_external_catalog_item iieci
join external_catalog_item eci
  on eci.external_catalog_item_id = iieci.external_catalog_item_id
where iieci.item_inventory_id in (18097, 18098)
  and eci.external_service_id = 2
order by iieci.item_inventory_id, iieci.is_primary desc;
```

For each inventory item, copy the returned `external_catalog_item_id` into the request.
Do not copy `bricklink_item_number` into `externalCatalogItemId`.

If `externalCatalogItemId` is omitted, the service selects the primary BrickLink link
automatically. Omitting it does not solve a missing link: if the inventory has no linked
BrickLink catalog row, the POST fails with:

```text
Inventory item must have a BrickLink catalog link for the requested draft
```

The draft endpoint cannot create that relationship. New acquisition intake normally creates
the primary link; legacy or migrated inventory with no link needs a separate catalog-link data
repair before draft creation can succeed.

#### Copy-paste payload template

Replace `12345` with the local `external_catalog_item_id` returned by the query above. Replace
`18097` with the item being listed. For a Pricing Plane draft, leave `unitPrice` as `null` (or
omit it). For an immediate manually priced listing, replace `null` with a positive amount.

```json
{
  "itemInventoryId": 18097,
  "marketplaceCode": "BRICKLINK",
  "externalCatalogItemId": 12345,
  "updateSaleIntentToSellable": false,
  "saleIntentNote": "Approved for BrickLink listing",
  "title": "Optional listing title",
  "description": "Optional buyer-facing description",
  "privateNotes": "Optional operator notes",
  "unitPrice": null,
  "currencyCode": "USD",
  "fixedPrice": false,
  "bricklink": {
    "colorId": 0,
    "bulk": 1,
    "isRetain": false,
    "isStockRoom": true,
    "stockRoomId": "C",
    "saleRate": 0,
    "remarks": "Optional human remarks"
  }
}
```

The same request can promote the item instead of requiring a separate sale-intent PATCH by
setting `updateSaleIntentToSellable` to `true`. It does not bypass the active, available, or
catalog-link checks.

The `bricklink.colorId` value is item-type dependent:

- SET catalog items (`itemTypeCode` `S` or `SET`) must use `0` (Not Applicable); `null` is normalized to `0`.
- Color-specific catalog items require a positive color ID. Replace `0` with the intended BrickLink color ID.

SANDBOX uses stockroom `C` and DEV uses `B` (HOMELAB-20). Both services refuse to start if a lower
environment is configured with `A` or with another environment's stockroom, and the service
normalizes the stored draft to its own stockroom.

A representative request with a manually supplied price is:

```json
{
  "itemInventoryId": 18098,
  "marketplaceCode": "BRICKLINK",
  "externalCatalogItemId": 12345,
  "title": "Optional listing title",
  "description": "Optional buyer-facing description",
  "privateNotes": "Optional operator notes",
  "unitPrice": 12.34,
  "currencyCode": "USD",
  "fixedPrice": false,
  "bricklink": {
    "colorId": 0,
    "bulk": 1,
    "isRetain": false,
    "isStockRoom": true,
    "stockRoomId": "C",
    "saleRate": 0,
    "remarks": "Optional human remarks"
  }
}
```

The example IDs, price, color, and stockroom are placeholders. The request contract is:

- `marketplaceCode` must be `BRICKLINK`.
- `currencyCode` is required.
- `unitPrice` may be omitted for Pricing Plane onboarding, but it must be positive before sync.
- If `externalCatalogItemId` is omitted, the primary BrickLink catalog link is used; a missing primary link still blocks the request.
- If `externalCatalogItemId` is supplied, it must be the local ID of a BrickLink catalog link belonging to this inventory item.
- There can be only one open local listing for this item and marketplace. `REMOVED` and `ENDED` listings are not open.
- In non-production, the service enforces BrickLink stockroom details and the configured stockroom. The ingress worker repeats the safety check later.
- For a BrickLink SET (`itemTypeCode` `S` or `SET`), `colorId` is normalized to `0` (Not Applicable). A nonzero SET color is invalid.
- For color-specific item types, `colorId` must be positive.

The optional `updateSaleIntentToSellable=true` flag can promote the inventory as part of draft creation. It does not bypass the active/available/catalog checks.

At this point the expected local state is:

```text
item_inventory.sale_intent_code = SELLABLE
marketplace_listing.listing_status_code = DRAFT
marketplace_listing.external_listing_id = null
bricklink_marketplace_listing.bricklink_inventory_id = null
marketplace_listing_sync_request = no row yet
```

### 4. Evaluate marketplace readiness

Call:

```http
GET /api/v1/inventory/{itemInventoryId}/marketplace-readiness?marketplaceCode=BRICKLINK
```

`readyForMarketplaceSync=true` means the local listing-create preconditions are satisfied. It does not mean that a request has been queued or that BrickLink has been called.

The service blocks when any of these are true:

| Blocker | Required correction |
| --- | --- |
| `UNSUPPORTED_MARKETPLACE` | Use `BRICKLINK` |
| `INVENTORY_INACTIVE` | Reactivate the inventory item |
| `INVENTORY_NOT_SELLABLE` | Set sale intent to `SELLABLE` |
| `INVENTORY_NOT_AVAILABLE` | Set the physical inventory state to `AVAILABLE` |
| `MISSING_PRIMARY_BRICKLINK_CATALOG_LINK` | Repair the catalog mapping |
| `MISSING_MARKETPLACE_LISTING_DRAFT` | Create an open local BrickLink draft |
| `INITIAL_PRICE_PENDING` | Leave the non-fixed DRAFT unpriced and wait for Pricing Plane crawl, decision, readiness, and apply; do not create a fake seed price |
| `MISSING_FIXED_UNIT_PRICE` / `INVALID_UNIT_PRICE` | Supply a positive unit price for an active/fixed listing or repair the applied Pricing Plane price |
| `MISSING_BRICKLINK_LISTING_DETAILS` | Supply BrickLink details for a non-production draft |
| `NON_PROD_BRICKLINK_STOCKROOM_REQUIRED` | Set `isStockRoom=true` |
| `NON_PROD_BRICKLINK_STOCKROOM_ID_REQUIRED` | Use the configured non-production stockroom |
| `MISSING_BRICKLINK_COLOR_ID` / `INVALID_BRICKLINK_COLOR_ID` | Apply the shared SET/color-specific color rule |

`MISSING_ITEM_INVENTORY_PHOTOS` is a warning. It should prompt better listing preparation, but it is not currently a sync blocker.

### 5. Choose the price path

There are two valid paths from a local draft to a priced listing.

#### Path A: manually supplied price

Use this when the price is known and should be entered directly. Make sure `unitPrice` is positive and the currency is correct, evaluate readiness again, then go to sync-request creation.

If `fixedPrice=true`, the current unit price is authoritative for Pricing Plane purposes. The pricing decision job records `SKIPPED / FIXED_PRICE_OVERRIDE` and does not calculate a replacement price. Fixed price does not, by itself, prevent a manual `LISTING_CREATE`; the normal draft and sync safety checks still apply.

#### Path B: Pricing Plane initial price

Use this for competitive pricing:

```text
local DRAFT (unitPrice may be null)
    -> pricing crawl work
    -> immutable pricing snapshot/comparables
    -> pricing decision
    -> apply-readiness audit (READY_TO_APPLY_INITIAL_PRICE)
    -> local initial-price application
    -> LISTING_CREATE sync request
```

The details and rules are in Part II. The important operational point is that a DRAFT must be included in the effective crawl, decision, and apply-readiness candidate status sets. The shared, local, and sandbox profiles explicitly configure `ACTIVE,DRAFT`; if another profile is used, verify its `priceable-listing-status-codes` setting because the older singular `active-listing-status-code` key is only the fallback.

For this path, do not seed a fake price. The readiness row intentionally stores `current_price = NULL`, `proposed_price = <calculated price>`, and null delta fields because there is no meaningful baseline. The apply job rechecks that the listing is still an unpriced local DRAFT, writes the calculated price to `marketplace_listing.unit_price`, and enqueues `LISTING_CREATE` with `previousUnitPrice = NULL` and a positive `requestedUnitPrice`.

### 6. Preview and create the local sync request

Preview first:

```http
GET /api/v1/marketplace-listings/{marketplaceListingId}/sync-request-preview
```

If the preview has no blockers, create the request:

```http
POST /api/v1/marketplace-listings/{marketplaceListingId}/sync-requests
```

An empty body uses these defaults:

```text
syncRequestTypeCode = LISTING_CREATE
syncReasonCode      = MANUAL_LISTING_CREATE
status              = PENDING
```

The optional request body can set `syncRequestTypeCode`, `syncReasonCode`, and `maxAttempts`. `lego-data-service` currently creates only `LISTING_CREATE` requests through this endpoint. It does not expose a metadata-only `PRICE_UPDATE`, `DESCRIPTION_UPDATE`, or `REMARKS_UPDATE` request API. Pricing apply creates `PRICE_UPDATE` requests internally for remote listings and `LISTING_CREATE` requests for local drafts.

The service refuses to create a request when:

- readiness has blockers;
- the local listing already has a BrickLink remote inventory ID;
- an active `PENDING` or `CLAIMED` request of the same type already exists; or
- an unsupported request type is supplied.

Terminal `FAILED`, `BLOCKED`, or `CANCELLED` rows do not represent active work. After investigating the cause, create a fresh request through the normal workflow rather than assuming the old row will be retried.

After creation, verify it:

```http
GET /api/v1/marketplace-listings/{marketplaceListingId}/sync-requests
GET /api/v1/marketplace-listings/sync-requests/{syncRequestId}
```

If an older deployment returned HTTP 500 from a body-less POST, query these endpoints and the database before submitting another request. The prior null-safe logging defect could report an error after the insert had committed.

### 7. Let the ingress worker create and verify the BrickLink inventory

`BricklinkMarketplaceSyncJob` selects due `PENDING` requests of type `LISTING_CREATE` or `PRICE_UPDATE`.

In `APPLY` mode, the normal create sequence is:

1. Claim the request (`PENDING -> CLAIMED`).
2. Reload the local listing, BrickLink details, inventory, catalog mapping, and request.
3. Run final local listing-create safety checks.
4. Map the local data to a BrickLink `Inventory` create payload.
5. Call BrickLink `createInventory`.
6. Fetch the created remote inventory back.
7. Run remote ownership/visibility safety checks against the read-back object.
8. Persist the BrickLink inventory ID and remote metadata locally.
9. Set `marketplace_listing.listing_status_code=ACTIVE`, `published_at`, and `last_synchronized_at`.
10. Mark the request `SUCCEEDED`.

The local listing remains `DRAFT` until step 9. A successful HTTP create alone is not sufficient; the read-back verification is part of the safety contract.

In `DRY_RUN`, the worker builds and verifies the intended operation but does not claim the row or call a BrickLink mutation API. Local profile behavior is always effectively `DRY_RUN`, even if `mode=APPLY` is present.

### 8. Verify the completed listing

A successful listing-create should show all of the following:

```text
marketplace_listing.listing_status_code = ACTIVE
bricklink_marketplace_listing.bricklink_inventory_id is not null
marketplace_listing_sync_request.sync_request_status_code = SUCCEEDED
remote safety status = allowed/success
remote inventory is stockroom-only in sandbox/dev
system remarks identify the local listing and inventory UUID
```

The BrickLink account should contain the remote item in the expected stockroom. In sandbox/dev, “live” means present in the account’s stockroom, not public inventory.

### 9. Update listing description or BrickLink My Remarks

There are two different workflows depending on whether BrickLink has created the remote
inventory yet.

#### Local draft: update first, then create remotely

For a local `DRAFT`, update the local marketplace listing before creating the
`LISTING_CREATE` sync request:

```http
PATCH {{lego_data_service_base_url}}/api/v1/marketplace-listings/{marketplaceListingId}
Content-Type: application/json

{
  "description": "Updated buyer-facing BrickLink description"
}
```

The top-level `description` is the marketplace listing description. During
`LISTING_CREATE`, `lego-data-ingress` maps it to the BrickLink inventory description. A
blank description falls back to the local listing title for the create payload.

To update the BrickLink-side `My Remarks` value, use `bricklink.remarks`:

```http
PATCH {{lego_data_service_base_url}}/api/v1/marketplace-listings/{marketplaceListingId}
Content-Type: application/json

{
  "bricklink": {
    "colorId": 0,
    "colorName": "Not Applicable",
    "bulk": 1,
    "isRetain": false,
    "isStockRoom": true,
    "stockRoomId": "C",
    "saleRate": 0,
    "tierQuantity1": null,
    "tierPrice1": null,
    "tierQuantity2": null,
    "tierPrice2": null,
    "tierQuantity3": null,
    "tierPrice3": null,
    "myWeight": null,
    "remarks": "Updated human remarks"
  }
}
```

Important details:

- For a description-only change, omit `bricklink` from the PATCH.
- When `bricklink` is present, send the complete current BrickLink details block. The
  service overwrites the nested fields supplied in that object; omitting fields can clear
  stored color, bulk, sale-rate, tier, or weight values.
- Retrieve the current listing first with
  `GET {{lego_data_service_base_url}}/api/v1/marketplace-listings/{marketplaceListingId}`
  and copy only writable BrickLink detail fields into the PATCH.
- Send human remarks only. Do not manually author or copy the managed
  `[SYSTEM_BEGIN] ... [SYSTEM_END]` ownership block; `lego-data-ingress` generates and
  validates that block during remote synchronization.
- `privateNotes` are local operator notes and are not sent to BrickLink.

After the PATCH, verify the local values and then use the normal create workflow:

```http
GET {{lego_data_service_base_url}}/api/v1/marketplace-listings/{marketplaceListingId}
GET {{lego_data_service_base_url}}/api/v1/marketplace-listings/{marketplaceListingId}/sync-request-preview
POST {{lego_data_service_base_url}}/api/v1/marketplace-listings/{marketplaceListingId}/sync-requests
Content-Type: application/json

{}
```

The preview must have no blockers. The body-less POST defaults to `LISTING_CREATE`. The
ingress worker then builds the BrickLink create payload from the updated local description
and remarks, creates the stockroom listing, verifies the remote response, and changes the
local listing from `DRAFT` to `ACTIVE`.

#### Already-active listing: local update only in the current implementation

Once the listing is `ACTIVE` and has a BrickLink inventory ID, the same PATCH still updates
the local `marketplace_listing.description` and `bricklink_marketplace_listing.remarks`,
but it does not enqueue a sync request. The existing service sync endpoint is intentionally
limited to `LISTING_CREATE`, and `LISTING_CREATE` is invalid for a listing that already has
a remote BrickLink inventory.

The pricing-driven `PRICE_UPDATE` path is not a general metadata resynchronization API. It
can update the remote price and preserve/repair the managed remote remarks block when a
pricing decision is applied, but it does not copy the local marketplace description, title,
private notes, or a newly edited local human-remarks value to BrickLink. A local PATCH is
therefore not an automatic BrickLink update for an already-active listing.

For an active listing, the current supported behavior is:

```text
PATCH local description/remarks
        |
        +--> local database updated
        |
        +--> no automatic sync request
        |
        +--> BrickLink remains unchanged
```

There is currently no supported `lego-data-service` API for a description-only or
remarks-only update of an existing BrickLink inventory. Such a workflow would require a
new sync request type and corresponding ingress mapping/safety path. Do not change an
active listing back to `DRAFT` merely to force `LISTING_CREATE`; that could create a second
remote inventory or violate the existing remote-ownership safeguards.

## Part II — Pricing Playbook

### Pricing data model

The pricing tables form a history-preserving pipeline rather than one mutable price calculation:

| Table | Role | Mutates the listing price? |
| --- | --- | --- |
| `pricing_crawl_work_item` | Durable queue and result for one listing crawl attempt | No |
| `pricing_snapshot` | Immutable crawl header for one listing/condition/completeness | No |
| `pricing_snapshot_listing` | Immutable BrickLink comparable rows captured by that snapshot | No |
| `pricing_decision` | Historical recommendation, algorithm metadata, reason, confidence, and final price | No |
| `pricing_apply_readiness` | Durable audit of whether the latest decision passes apply gates | No |
| `marketplace_listing` | Local current unit price and listing state | Yes, only during apply |
| `marketplace_listing_sync_request` | Durable remote mutation queue | No, it schedules the remote mutation |

The current price does not become a BrickLink price merely because a `PROPOSED` decision exists. The normal write chain is:

```text
PROPOSED decision
    -> READY_TO_APPLY readiness row
    -> apply updates marketplace_listing.unit_price
    -> apply enqueues PRICE_UPDATE or LISTING_CREATE
    -> marketplace-sync calls BrickLink
```

### A. Crawl: capture current BrickLink market data

The crawl job runs only when both properties are true:

```yaml
lego.bricklink.pricing.crawl.enabled: true
lego.bricklink.pricing.crawl.scheduled.enabled: true
```

The crawler selects configured BrickLink listings that have:

- the configured BrickLink external service ID;
- a configured eligible listing status;
- a catalog mapping; and
- no active duplicate `PENDING`/`CLAIMED` crawl work or future cooling-down work.

The current implementation supports `ACTIVE` and local `DRAFT` pricing candidates when the effective status set includes them. The sandbox profile contains legacy `active-listing-status-code=ACTIVE`; if unpriced drafts are not being selected, inspect the bound `priceable-listing-status-codes`/fallback behavior before changing data.

The crawl sequence is:

1. Requeue stale claims whose claim age exceeds `claim-stale-after`, subject to attempt limits.
2. Schedule missing or expired work with spread/jitter and blackout rules.
3. Claim due work.
4. Load the local listing, inventory, catalog item, and condition/completeness.
5. Normalize condition values: `N`/`NEW -> N`, `U`/`USED -> U`.
6. Read the BrickLink item number from `external_catalog_item.external_item_key`.
7. If BrickLink’s internal `idItem` is missing/unparseable, call `searchproduct.ajax` to hydrate it.
8. Call `catalogifs.ajax` for the internal item ID and normalized new/used condition.
9. Persist an immutable `pricing_snapshot` and its `pricing_snapshot_listing` rows.
10. Complete the work item or schedule a retry.

The AJAX endpoints are:

```text
https://www.bricklink.com/ajax/clone/search/searchproduct.ajax
https://www.bricklink.com/ajax/clone/catalogifs.ajax
```

An empty `catalogifs.ajax` array is a successful zero-comparable snapshot, not an HTTP failure. It produces a snapshot with `comparable_count=0`, which later becomes a pricing decision reason of `NO_CURRENT_COMPARABLES`.

Common crawl statuses include:

```text
PENDING
CLAIMED
SUCCEEDED
SKIPPED_MISSING_LISTING
SKIPPED_MISSING_CATALOG
SKIPPED_MISSING_CONDITION
SKIPPED_MISSING_ITEM_NUMBER
FAILED_ITEM_ID_LOOKUP_NO_MATCH
FAILED_ITEM_ID_LOOKUP_AMBIGUOUS
FAILED_ITEM_ID_LOOKUP_HTTP_ERROR
FAILED_PRICING_HTTP_ERROR
FAILED_PRICING_PARSE_ERROR
```

The crawl is source acquisition only. It never sets `marketplace_listing.unit_price` and never creates a marketplace sync request.

### B. Decision: turn comparables into a recommendation

The decision job runs only when both properties are true:

```yaml
lego.bricklink.pricing.decision.enabled: true
lego.bricklink.pricing.decision.scheduled.enabled: true
```

Candidate requirements are:

- BrickLink listing service ID;
- an eligible listing status (the source default is `ACTIVE,DRAFT`);
- a populated catalog mapping;
- for non-fixed-price listings, nonblank condition and completeness; and
- when `require-current-snapshot=true`, a matching current snapshot for normalized condition and completeness.

Fixed-price listings are intentionally eligible even if condition/completeness is absent because they do not need a competitive replacement price.

#### Fixed-price rule

When `marketplace_listing.fixed_price=true`, the decision writes:

```text
status = SKIPPED
reason = FIXED_PRICE_OVERRIDE
final_price = current listing price
```

No snapshot is needed for that decision. Apply-readiness will not treat it as a price-change candidate.

#### Condition and completeness normalization

The decision algorithm normalizes:

```text
new_or_used: N / NEW -> N, U / USED -> U
completeness: SEALED / S -> S, COMPLETE / C -> C, INCOMPLETE / I / X -> X
```

It reads the latest matching snapshot and then selects exact comparables matching the target condition and completeness. The owned external listing is excluded when its external listing ID matches the comparable’s external listing ID.

#### Market-position price algorithm

`bricklink-market-position-v2`. The runbook section "Pricing formula and its levers" has the full description and every setting. After exact matching, own-listing exclusion, and dropping lots priced above `outlier-max-ratio-to-median` × the median:

| Exact comparable count / case | Recommendation |
| --- | --- |
| `0`, snapshot itself has zero comparables | `FAILED / NO_CURRENT_COMPARABLES` |
| `0`, snapshot has rows but none match exactly | `FAILED / NO_EXACT_COMPARABLES` |
| `1` | `min(price - min(price * 0.03, 10), max(price - 1, 1))` (`SINGLE_COMPARABLE_DISCOUNTED`) |
| `2` | `low + (high - low) * two-comparables-fraction`, default `0.25` (`TWO_COMPARABLES_WEIGHTED`) |
| 3 or more | The market's target percentile of the comparable prices, set by the `aggression` preset or `target-percentiles` (`MARKET_POSITION`). For used items, the target depends on the item's own box and instructions (BALANCED: 65th with both, 35th with one, 25th with neither) |

Small box and instructions grade factors (−15% to +10%) then apply to used items. An optional weekly step-down (`step-down.*`, off by default) lowers the price of an active listing that stays unsold. The price then moves at most `maximum-change-per-run` (default 25%) from the listing's current price.

Instruction factors:

```text
M 1.20, E 1.00, VG 0.95, G 0.90, F 0.80,
P 0.70, CC 0.50, BW 0.45, MS 0.40, NA 1.00
```

Box factors:

```text
SL 1.30, M 1.20, E 1.00, VG 0.95, G 0.90,
F 0.85, P 0.70, MS 0.50, NA 1.00
```

Computed and final prices are rounded to two decimals. Confidence is based on exact comparable count:

```text
5 or more: 0.90
3-4:       0.75
2:         0.60
1:         0.40
0:         0.00
```

Configured global minimum/maximum price clamps produce `BELOW_MIN_PRICE_CLAMPED` or `ABOVE_MAX_PRICE_CLAMPED` and preserve the original calculation in decision metadata.

Decision statuses:

| Status | Meaning |
| --- | --- |
| `PROPOSED` | A price was calculated and is eligible for later readiness review |
| `SKIPPED` | No replacement price was calculated, currently fixed-price override |
| `FAILED` | Required source data was missing or the calculation was unsafe |

Important reason codes:

```text
MARKET_POSITION
SINGLE_COMPARABLE_DISCOUNTED
TWO_COMPARABLES_WEIGHTED
FIXED_PRICE_OVERRIDE
NO_CURRENT_SNAPSHOT
NO_CURRENT_COMPARABLES
NO_EXACT_COMPARABLES
MISSING_INVENTORY
MISSING_CONDITION
MISSING_COMPLETENESS
BELOW_MIN_PRICE_CLAMPED
ABOVE_MAX_PRICE_CLAMPED
```

### C. Apply-readiness: decide whether an automatic price movement is safe

Apply-readiness is deliberately read-only. It writes `pricing_apply_readiness` audit rows, but it does not update the listing price, mark a decision applied, or enqueue remote work.

The service evaluates the latest proposed, unapplied decision for each listing. Its effective gate order is:

1. Fixed price? Block as `FIXED_PRICE`.
2. Is there a newer snapshot than the decision used? Block as stale.
3. Is the decision status `PROPOSED`?
4. Is this an unpriced local DRAFT with no external listing ID? If yes, enter the initial-price path; otherwise, a current price is required.
5. Is the final proposed price present and positive?
6. Do current and decision currencies match? Blank currency is treated as USD where the implementation allows it.
7. Is the reason in the blocked-reason set?
8. Is the reason in the eligible-reason set?
9. For an initial price, does confidence and exact comparable count meet their minimums? For a reprice, is the absolute price delta at least the minimum?
10. For a reprice, if enabled, does the delta meet the percentage guard? The required money movement is rounded upward to cents.
11. For a reprice, is the absolute movement below the configured maximum?
12. For a reprice, is the percentage movement below the configured maximum?

Common readiness outcomes:

```text
READY_TO_APPLY
READY_TO_APPLY_INITIAL_PRICE
BLOCKED_FIXED_PRICE
BLOCKED_STALE_DECISION
BLOCKED_MISSING_CURRENT_PRICE
BLOCKED_MISSING_FINAL_PRICE
BLOCKED_CURRENCY_MISMATCH
BLOCKED_UNSUPPORTED_DECISION_STATUS
BLOCKED_REASON_CODE
BLOCKED_INELIGIBLE_REASON
BLOCKED_BELOW_MINIMUM_DELTA
BLOCKED_BELOW_MINIMUM_DELTA_PERCENT
BLOCKED_BELOW_MINIMUM_CONFIDENCE
BLOCKED_BELOW_MINIMUM_COMPARABLE_COUNT
BLOCKED_ABOVE_MAXIMUM_ABSOLUTE_DELTA
BLOCKED_ABOVE_MAXIMUM_PERCENT_DELTA
```

The read-only diagnostic endpoints are:

```http
GET /internal/bricklink/pricing/maintenance-report?limit=100
GET /internal/bricklink/pricing/data-quality-report?limit=100
GET /internal/bricklink/pricing/apply-preview?readinessStatusCode=READY_TO_APPLY&limit=100
GET /internal/bricklink/pricing/apply-preview?blockReasonCode=NO_CURRENT_SNAPSHOT&limit=100
GET /internal/bricklink/pricing/apply-selection/dry-run?limit=100
```

Treat `/internal/*` as administrative endpoints and keep them behind trusted access controls.

### D. Apply: accept the decision and enqueue the correct remote work

The apply job consumes current, unapplied `READY_TO_APPLY` and `READY_TO_APPLY_INITIAL_PRICE` rows and rechecks the listing and decision defensively. It skips a decision that was already applied. Initial-price rows must still point to an unpriced local DRAFT with no external listing ID; if an operator priced or published the draft after readiness was evaluated, the row is treated as stale and is not allowed to overwrite the newer state.

Supported modes:

| Mode | Local listing price | Sync request |
| --- | --- | --- |
| `DRY_RUN` | No | No |
| `APPLY_LOCAL_ONLY` | Yes; decision `APPLIED` with `applied_at` | No |
| `APPLY_LOCAL_AND_ENQUEUE_SYNC` | Only for a `DRAFT` with no lot; otherwise the sync worker sets it after BrickLink confirms | Yes; decision `ACCEPTED` |

The sync type depends on local listing state:

| Local listing state | Remote BrickLink ID | Apply result |
| --- | --- | --- |
| `ACTIVE` or other existing listing | Present | Accept the decision and insert `PRICE_UPDATE`; the local price stays until BrickLink confirms it |
| `DRAFT` with a current positive price | Absent | Update the draft price and create `LISTING_CREATE` |
| `DRAFT` with `unitPrice=NULL` and initial readiness | Absent | Apply calculated initial price and create `LISTING_CREATE` |
| No remote ID, not `DRAFT` | Absent | Skip the row and log the reason; the local price is not changed |

A listing with a `PENDING` or `CLAIMED` sync request is skipped until that request finishes, and apply never overwrites an existing sync request for the same decision. Each row's writes run in one transaction.

Decision status: `PROPOSED` → `ACCEPTED` (apply queued the sync) → `APPLIED` (BrickLink confirmed the price, `applied_at` set). A blocked or failed sync leaves the decision `ACCEPTED` and the local price unchanged; the next crawl produces a new decision measured from the price BrickLink actually holds.

This is the bridge that lets an unpriced local draft enter the Pricing Plane. The draft can be created without `unitPrice`; once the initial decision is ready and apply runs, the price is written locally and the create request is queued. Manual sync-request creation remains blocked while the draft reports `INITIAL_PRICE_PENDING`.

### E. Marketplace sync: execute the queued remote mutation

The worker handles two request types:

#### `PRICE_UPDATE`

1. Load the local listing and remote ID.
2. Fetch the remote inventory.
3. Verify remote ownership, environment, visibility, remarks, and ID relationships.
4. Call BrickLink `updateInventory` with the requested price and the safety-preserved remote
   remarks. This path does not copy local marketplace description, title, private notes, or
   newly edited local human remarks.
5. Read the lot back. It must hold the requested price, this environment's stockroom (outside
   PROD) and the system remarks block that was written. A mismatch blocks the request with
   `REMOTE_PRICE_MISMATCH`, `REMOTE_STOCKROOM_MISMATCH`, `REMOTE_REMARKS_MISMATCH` or
   `REMOTE_INVENTORY_MISSING`, and leaves the local price as it was.
6. Only on a match, in one transaction: set `marketplace_listing.unit_price` and
   `last_synchronized_at`, move the decision from `ACCEPTED` to `APPLIED` with `applied_at`, save
   verification metadata and mark the request `SUCCEEDED` with `applied_local_at`.

#### `LISTING_CREATE`

1. Verify local create safety.
2. Map item number, item type, color, quantity, condition, completeness, price, remarks, stockroom, and listing metadata into the BrickLink payload.
3. In non-production, force stockroom-only visibility and the expected stockroom.
4. Merge the managed system remarks block.
5. Call BrickLink `createInventory`.
6. Fetch the new remote inventory.
7. Verify the returned inventory matches the local ownership contract.
8. Only after verification, in one transaction, make the local listing `ACTIVE`, move a pricing
   decision on the request from `ACCEPTED` to `APPLIED`, and mark the request `SUCCEEDED`. (The
   remote ID itself is saved as soon as `createInventory` returns, so a retry never creates a
   second lot.)

## Part III — Every rule that can prevent a BrickLink sync

### Intake and inventory blockers

These prevent the item from becoming a valid local listing candidate:

- missing/invalid BrickLink item number or catalog mapping;
- missing item-level `PRICE` cost;
- transaction-level `PRICE` cost;
- missing payment or payment/cost total mismatch;
- mixed currencies without a valid exchange rate;
- quantity other than `1`;
- intake `forSale=true`;
- inactive inventory;
- `inventoryStateCode` other than `AVAILABLE`;
- `saleIntentCode` other than `SELLABLE`.

### Local draft/readiness blockers

These are enforced by `lego-data-service` before a create request can be queued:

```text
UNSUPPORTED_MARKETPLACE
INVENTORY_INACTIVE
INVENTORY_NOT_SELLABLE
INVENTORY_NOT_AVAILABLE
MISSING_PRIMARY_BRICKLINK_CATALOG_LINK
MISSING_MARKETPLACE_LISTING_DRAFT
MISSING_FIXED_UNIT_PRICE
INVALID_UNIT_PRICE
MISSING_BRICKLINK_LISTING_DETAILS
NON_PROD_BRICKLINK_STOCKROOM_REQUIRED
NON_PROD_BRICKLINK_STOCKROOM_ID_REQUIRED
MISSING_BRICKLINK_COLOR_ID
INVALID_BRICKLINK_COLOR_ID
```

Sync-request preview adds:

```text
UNSUPPORTED_SYNC_REQUEST_TYPE
BRICKLINK_REMOTE_INVENTORY_ALREADY_EXISTS
ACTIVE_SYNC_REQUEST_ALREADY_EXISTS
```

### Pricing blockers

Pricing can fail or intentionally decline to produce a price when:

- crawl work has no listing, catalog, item number, condition, or current BrickLink internal item ID;
- BrickLink item-ID hydration returns no match, an ambiguous match, or an HTTP error;
- pricing AJAX fails or cannot be parsed;
- no current snapshot exists;
- the snapshot has zero comparables;
- no exact comparables remain after condition/completeness filtering and own-listing exclusion;
- a used-item outlier spread is greater than 3x;
- inventory condition/completeness is missing or unrecognized;
- the listing is fixed price (`FIXED_PRICE_OVERRIDE`), which is intentional rather than an error;
- apply-readiness rejects the decision for stale data, currency, reason, confidence, comparable count, or movement limits.

### Final local listing-create safety blockers

The ingress worker rechecks data immediately before a remote create. It can block with:

```text
LOCAL_CONTEXT_MISSING
UNSUPPORTED_SYNC_TYPE
NON_BRICKLINK_LISTING
LISTING_NOT_DRAFT
REMOTE_INVENTORY_ALREADY_EXISTS
INVENTORY_INACTIVE
INVENTORY_NOT_SELLABLE
INVENTORY_NOT_AVAILABLE
MISSING_BRICKLINK_CATALOG_ITEM
MISSING_BRICKLINK_ITEM_NUMBER
MISSING_BRICKLINK_ITEM_TYPE
MISSING_BRICKLINK_COLOR_ID
INVALID_BRICKLINK_COLOR_ID
MISSING_NEW_OR_USED
MISSING_COMPLETENESS
INVALID_UNIT_PRICE
INVALID_REQUESTED_UNIT_PRICE
REQUEST_PRICE_MISMATCH
NON_PROD_PUBLIC_VISIBILITY_FORBIDDEN
REMARKS_TOO_LONG
```

These checks are intentionally repeated after request creation so that a stale queue row cannot bypass a later data change or an unsafe deployment configuration.

### Remote safety blockers

For both create read-back and price update, the worker can block with:

```text
REMOTE_INVENTORY_MISSING
REMOTE_INVENTORY_ID_MISMATCH
REMOTE_NOT_STOCKROOM
REMOTE_STOCKROOM_MISMATCH
REMOTE_SYSTEM_BLOCK_MISSING
REMOTE_SYSTEM_BLOCK_UNMANAGED
REMOTE_SYSTEM_BLOCK_ENV_MISMATCH
REMOTE_SYSTEM_BLOCK_LISTING_MISMATCH
REMOTE_SYSTEM_BLOCK_INVENTORY_MISMATCH
REMOTE_REMARKS_TOO_LONG
```

The non-production system block has this shape:

```text
[SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true; LEGOHUNTER_ENV=sandbox; MARKETPLACE_LISTING_ID=123; ITEM_INVENTORY_UUID=e1dcb9cd5838e81dbb55f28f74ab8069 [SYSTEM_END]
```

Human remarks outside the block are preserved. The block must identify the correct environment, local marketplace listing, and item-inventory UUID. Missing, malformed, duplicated, or mismatched ownership data blocks a remote write.

### State and retry behavior

For sync requests, the normal state path is:

```text
PENDING -> CLAIMED -> SUCCEEDED
                    \-> BLOCKED
                    \-> FAILED
```

Unexpected/transient failures return to `PENDING` with the configured retry backoff until `maxAttempts` is reached. Safety failures are blocked/terminal because blindly retrying them would repeat an unsafe operation. A successful `LISTING_CREATE` that fails remote read-back verification retains the remote ID for investigation and is blocked rather than silently creating another item.

## Part IV — Sandbox operating procedure

### Current sandbox safety posture

The active deployment configuration is more authoritative than a checked-in profile. The two relevant repository states currently differ:

| Configuration source | Pricing apply mode | Marketplace sync | Non-prod stockroom |
| --- | --- | --- | --- |
| `origin/develop` profile at the time this playbook was written | `DRY_RUN` | `APPLY` | `A` (superseded: `C` since HOMELAB-20) |
| `feature/force-sandbox-redeploy` profile used for the sandbox redeploy workflow | `APPLY_LOCAL_AND_ENQUEUE_SYNC` | `APPLY` | `C` |

The redeploy branch is the one intended to kick off the current sandbox deployment. Verify the running ConfigMap/environment and ingress image before relying on either row. A branch file is not proof of what Kubernetes has loaded.

The sandbox redeploy profile enables all four stages and uses these important values:

| Stage | Key settings |
| --- | --- |
| Crawl | enabled; batch `100`; worker batch `1`; cadence `7d`; spread `72h`; retry `6h`; stale claim `2h`; blackout 21:30–08:30 America/New_York on weekdays |
| Decision | enabled; batch `100`; current snapshot required; `bricklink-market-position-v2`; aggression `BALANCED` |
| Apply-readiness | enabled; minimum delta `$0.01`; percentage guard `2%`; minimum confidence `.75`; minimum exact comparables `3`; maximum absolute delta `$50`; maximum percentage delta `50%` |
| Apply | enabled; batch `25`; max sync attempts `3`; `APPLY_LOCAL_AND_ENQUEUE_SYNC` |
| Marketplace sync | enabled; `APPLY`; batch `5`; retry backoff `6h`; `production=false`; system remarks required; stockroom `C`; remarks max `1024` |

All scheduled jobs use a ten-minute fixed delay in this profile, with initial delays of 30 seconds for crawl, 120 seconds for decision, 180 seconds for readiness, 240 seconds for apply, and 300 seconds for marketplace sync. A newly created draft is therefore not expected to appear in BrickLink immediately. It must also pass crawl timing, blackout/spread, decision, readiness, and queue timing.

### Log checkpoints

Search ingress logs for these events in order:

```text
bricklink.pricing.crawl.job.completed
bricklink.pricing.decision.job.completed
bricklink.pricing.apply_readiness.ready
bricklink.pricing.apply.job.completed
bricklink.pricing.apply.local_updated
bricklink.pricing.apply.listing_create_sync_enqueued
bricklink.marketplace_sync.listing_create_dry_run
bricklink.marketplace_sync.listing_created
bricklink.marketplace_sync.listing_create_blocked
bricklink.marketplace_sync.listing_create_remote_verification_blocked
bricklink.marketplace_sync.remote_updated
bricklink.marketplace_sync.blocked
bricklink.marketplace_sync.failed
bricklink.marketplace_sync.job.completed
```

For Kubernetes, adapt the deployment/namespace names to the cluster:

```powershell
kubectl -n <namespace> logs deploy/lego-data-ingress --since=60m |
    Select-String 'bricklink\.pricing|bricklink\.marketplace_sync'
```

For one item, search by `itemInventoryId`, `marketplaceListingId`, `pricingDecisionId`, or `syncRequestId`. Do not enable high-volume BrickLink HTTP-body logging for normal operation.

### Read-only investigation queries

Substitute the real IDs. These queries are diagnostic; they do not repair or requeue anything.

#### Inventory and catalog

```sql
select item_inventory_id,
       uuid,
       active,
       for_sale,
       inventory_state_code,
       sale_intent_code,
       new_or_used,
       completeness
from item_inventory
where item_inventory_id = 18098;

select iieci.item_inventory_id,
       iieci.external_catalog_item_id,
       iieci.is_primary,
       eci.external_service_id,
       eci.external_item_key,
       eci.item_type_code,
       eci.external_unique_key
from item_inventory_external_catalog_item iieci
join external_catalog_item eci
  on eci.external_catalog_item_id = iieci.external_catalog_item_id
where iieci.item_inventory_id = 18098;
```

#### Local listing and remote identity

```sql
select marketplace_listing_id,
       item_inventory_id,
       listing_external_service_id,
       external_catalog_item_id,
       listing_status_code,
       unit_price,
       currency_code,
       fixed_price,
       external_listing_id
from marketplace_listing
where item_inventory_id = 18098
order by marketplace_listing_id desc;

select marketplace_listing_id,
       bricklink_inventory_id,
       color_id,
       is_stock_room,
       stock_room_id,
       environment_code,
       last_remote_verified_at,
       last_remote_safety_status_code,
       last_remote_safety_message
from bricklink_marketplace_listing
where marketplace_listing_id = <marketplace_listing_id>;
```

#### Crawl, snapshot, and decision state

```sql
select pricing_crawl_work_item_id,
       marketplace_listing_id,
       work_status_code,
       attempt_count,
       max_attempts,
       next_attempt_at,
       last_error_message
from pricing_crawl_work_item
where marketplace_listing_id = <marketplace_listing_id>
order by pricing_crawl_work_item_id desc;

select pricing_snapshot_id,
       marketplace_listing_id,
       item_condition_code,
       completeness_code,
       comparable_count,
       captured_at
from pricing_snapshot
where marketplace_listing_id = <marketplace_listing_id>
order by pricing_snapshot_id desc;

select pricing_decision_id,
       marketplace_listing_id,
       pricing_snapshot_id,
       decision_status_code,
       reason_code,
       computed_price,
       final_price,
       comparable_count,
       confidence,
       applied_at
from pricing_decision
where marketplace_listing_id = <marketplace_listing_id>
order by pricing_decision_id desc;

select pricing_apply_readiness_id,
       marketplace_listing_id,
       pricing_decision_id,
       readiness_status_code,
       block_reason_code,
       current_price,
       proposed_price,
       delta_amount,
       delta_percent,
       comparable_count,
       confidence
from pricing_apply_readiness
where marketplace_listing_id = <marketplace_listing_id>
order by pricing_apply_readiness_id desc;
```

#### Sync queue

```sql
select marketplace_listing_sync_request_id,
       marketplace_listing_id,
       pricing_decision_id,
       pricing_apply_readiness_id,
       sync_request_type_code,
       sync_request_status_code,
       requested_unit_price,
       remote_inventory_id,
       remote_visibility_scope_code,
       remote_visibility_container_id,
       remote_is_publicly_available,
       environment_code,
       attempt_count,
       max_attempts,
       next_attempt_at,
       last_error_message
from marketplace_listing_sync_request
where marketplace_listing_id = <marketplace_listing_id>
order by marketplace_listing_sync_request_id desc;
```

For a global queue view:

```sql
select sync_request_status_code, count(*) as request_count
from marketplace_listing_sync_request
group by sync_request_status_code
order by sync_request_status_code;
```

### Operator decision tree

Use this order when an item is not appearing in BrickLink:

1. Is `item_inventory` present, active, `AVAILABLE`, and `SELLABLE`?
2. Is the primary BrickLink catalog link present and correct?
3. Is there exactly one open local BrickLink listing, and is it `DRAFT` or `ACTIVE` as expected?
4. Does the local listing have a positive price? If it is an unpriced non-fixed DRAFT, expect `INITIAL_PRICE_PENDING` until Pricing Plane apply completes; do not seed a fake price.
5. For an initial-price candidate, is there a latest `pricing_decision` with a positive `final_price` and a `READY_TO_APPLY_INITIAL_PRICE` readiness row?
6. Does marketplace readiness report blockers or only the photo warning?
7. Does a sync-request preview report an active duplicate or remote inventory already present?
8. Is there a due `PENDING` sync request, or is it still waiting for `next_attempt_at`?
9. Is the ingress worker enabled, scheduled, and in `APPLY` rather than `DRY_RUN`?
10. If the request is `BLOCKED`, fix the specific safety violation and create a fresh request when appropriate.
11. If the request is `FAILED`, inspect attempt count/error and retry only after determining whether it is transient.
12. If BrickLink create returned success but the local row is not `ACTIVE`, inspect remote read-back safety and the retained remote ID before doing anything else.

## Context consolidation: operator checkpoints from the completed workflow

Use [`lego-data-service/runbook.md`](../lego-data-service/runbook.md) for the full
service API contract and this playbook for the end-to-end ingress sequence. The
following points capture the operational lessons that caused the most confusion during
the initial sandbox listings.

### Canonical initial-price request

For an item that should receive its first price algorithmically, create the local draft
with a nullable price and `fixedPrice=false`:

```http
POST {{lego_data_service_base_url}}/api/v1/marketplace-listings
Content-Type: application/json

{
  "itemInventoryId": <item_inventory_id>,
  "marketplaceCode": "BRICKLINK",
  "externalCatalogItemId": <linked_external_catalog_item_id>,
  "updateSaleIntentToSellable": false,
  "saleIntentNote": "Approved for BrickLink listing",
  "title": "Optional listing title",
  "description": "Optional buyer-facing description",
  "privateNotes": "Optional operator notes",
  "unitPrice": null,
  "currencyCode": "USD",
  "fixedPrice": false,
  "bricklink": {
    "colorId": 0,
    "bulk": 1,
    "isRetain": false,
    "isStockRoom": true,
    "stockRoomId": "C",
    "saleRate": 0,
    "remarks": "Optional human remarks"
  }
}
```

The live database must have `marketplace_listing.unit_price` nullable. A 500 containing
`Column 'unit_price' cannot be null` means the schema is behind the application
contract; apply the database migration/deployer change and verify
`information_schema.columns.is_nullable='YES'`. Do not put a fake `$0.01` or `$1.00`
price into the request merely to bypass the schema.

The local response should be a `DRAFT` with readiness blocker
`INITIAL_PRICE_PENDING`. That blocker is expected until apply writes the first positive
price. The service code currently uses `INITIAL_PRICE_PENDING`; the discussed
`INITIAL_FIXED_PRICE_PENDING` spelling is not implemented.

### Catalog link and BrickLink internal ID

The requested `externalCatalogItemId` must be linked to the inventory item and must
identify a BrickLink catalog row. Check the mapping with:

```sql
select iieci.item_inventory_id,
       iieci.external_catalog_item_id,
       iieci.is_primary,
       eci.external_item_key as bricklink_item_number,
       eci.item_type_code,
       eci.external_unique_key as bricklink_internal_id
from item_inventory_external_catalog_item iieci
join external_catalog_item eci
  on eci.external_catalog_item_id = iieci.external_catalog_item_id
where iieci.item_inventory_id in (<item_inventory_id>)
  and eci.external_service_id = 2
order by iieci.item_inventory_id, iieci.is_primary desc;
```

`external_item_key` is the user-facing item number. `external_unique_key` is BrickLink's
internal `idItem` used by `catalogifs.ajax`. A null internal key can be hydrated by the
ingress crawler through `searchproduct.ajax`; it is not a reason to invent a key or to
assume the draft API will perform the lookup. If the lookup has no match, is ambiguous,
or fails over HTTP, inspect the crawl work-item status and error message.

### How to read the pricing stages

Trace one `marketplace_listing_id` through the tables in this order:

```text
pricing_crawl_work_item
    -> pricing_snapshot / pricing_snapshot_listing
    -> pricing_decision
    -> pricing_apply_readiness
    -> marketplace_listing.unit_price
    -> marketplace_listing_sync_request
    -> BrickLink remote inventory
```

The meanings are different:

| Observation | What it proves | What it does not prove |
| --- | --- | --- |
| Crawl work `SUCCEEDED` | BrickLink source data was captured and persisted. | No price was necessarily calculated, applied, or synced. |
| Decision `PROPOSED` with positive `final_price` | A price recommendation exists. | The local listing price has not necessarily changed. |
| `READY_TO_APPLY_INITIAL_PRICE` | An unpriced local DRAFT passed initial-price gates. | The apply job has not necessarily run. |
| `marketplace_listing.unit_price` is positive | Local price apply occurred or an operator set it. | BrickLink has not necessarily received it. |
| Sync request `PENDING` | Remote work is queued. | The remote call has not completed. |
| Sync request `SUCCEEDED` + listing `ACTIVE` + remote ID | Remote create/update passed read-back safety. | Nothing further is implied about future repricing. |

For an initial price, `current_price`, `delta_amount`, and `delta_percent` are normally
null because there is no baseline. The proposed positive amount is the value to explain
to an operator.

Useful read-only trace query:

```sql
select marketplace_listing_id, listing_status_code, unit_price,
       fixed_price, external_listing_id
from marketplace_listing
where marketplace_listing_id = <marketplace_listing_id>;

select pricing_decision_id, decision_status_code, reason_code,
       computed_price, final_price, comparable_count, confidence, applied_at
from pricing_decision
where marketplace_listing_id = <marketplace_listing_id>
order by pricing_decision_id desc;

select pricing_apply_readiness_id, readiness_status_code, block_reason_code,
       current_price, proposed_price, delta_amount, delta_percent,
       comparable_count, confidence
from pricing_apply_readiness
where marketplace_listing_id = <marketplace_listing_id>
order by pricing_apply_readiness_id desc;
```

### Manual sync and batching

Manual sync is one listing per request:

```http
GET  {{lego_data_service_base_url}}/api/v1/marketplace-listings/{marketplaceListingId}/sync-request-preview
POST {{lego_data_service_base_url}}/api/v1/marketplace-listings/{marketplaceListingId}/sync-requests
Content-Type: application/json

{}
```

An empty body defaults to `LISTING_CREATE`. The service endpoint cannot accept multiple
item inventory IDs; a batch client must loop over listing IDs and must not skip the
readiness/duplicate check for any item.

If the body-less POST returns HTTP 500, query
`marketplace_listing_sync_request` before retrying. A previous null-safe logging bug
could return an error after the transaction committed, so blindly retrying can create a
duplicate pending request.

### Description and BrickLink My Remarks

Before the first create:

```http
PATCH {{lego_data_service_base_url}}/api/v1/marketplace-listings/{marketplaceListingId}
Content-Type: application/json

{
  "description": "Updated buyer-facing description",
  "bricklink": {
    "colorId": 0,
    "bulk": 1,
    "isRetain": false,
    "isStockRoom": true,
    "stockRoomId": "C",
    "saleRate": 0,
    "remarks": "Updated human remarks"
  }
}
```

Omit `bricklink` for a description-only PATCH. If it is present, send the complete
writable nested block so omitted fields are not cleared. Never hand-author the managed
`[SYSTEM_BEGIN] ... [SYSTEM_END]` block. Preview and queue `LISTING_CREATE` after the
PATCH; the ingress mapper will include the local description and human remarks.

After the listing is `ACTIVE`, the PATCH remains local-only. The pricing `PRICE_UPDATE`
path can change price and preserve/repair managed system remarks, but it does not
propagate local description, title, private notes, or a newly edited human-remarks
value. There is no current metadata-only sync request workflow, and changing an active
listing back to `DRAFT` is unsafe.

### Sandbox timing and mode checkpoint

The sandbox redeploy branch used for the verified end-to-end test had:

```text
crawl:             enabled, initial delay 30s, fixed delay 10m
decision:          enabled, initial delay 120s, fixed delay 10m
apply-readiness:   enabled, initial delay 180s, fixed delay 10m
apply:             APPLY_LOCAL_AND_ENQUEUE_SYNC, initial delay 240s, fixed delay 10m
marketplace-sync:  APPLY, production=false, stockroom C, initial delay 300s, fixed delay 10m
```

This means a newly created DRAFT is not expected to appear in BrickLink immediately.
The crawl spread/jitter, blackout window, decision gates, apply gates, queue due time,
and remote read-back all still apply. Local is always effectively `DRY_RUN`; sandbox
and dev use the real account but must remain stockroom-only with managed non-production
remarks.

Verify the active ConfigMap/environment and pod image after each redeploy. A checked-in
profile or force-redeploy branch is not proof of what Kubernetes loaded.

### Operator sequence when a listing is missing

1. Confirm the item is active, `AVAILABLE`, and `SELLABLE`.
2. Confirm the primary BrickLink link and selected catalog link.
3. Confirm the listing is an open local `DRAFT` or the expected `ACTIVE` row.
4. If the DRAFT is non-fixed and unpriced, expect `INITIAL_PRICE_PENDING`; do not seed a
   price or manually queue a sync request.
5. Check the latest crawl, snapshot, decision, and readiness rows.
6. Confirm apply mode and marketplace-sync mode from the running ingress configuration.
7. Inspect the sync queue, `next_attempt_at`, attempts, and last error.
8. If a remote create may have succeeded, inspect the stored BrickLink inventory ID and
   remote safety state before retrying.

## References and related context


- [Ingress runbook](runbook.md) — full configuration tables, maintenance reports, SQL checks, and failure modes.
- [lego-data-service README](../lego-data-service/README.md) — intake, correction, listing, readiness, and sync-request API contracts.
- [lego-data README](../lego-data/README.md) — shared BrickLink color policy and persistence-layer responsibilities.
- [Inventory intake chat backup](../inventory-intake-chat-backup.md) — historical intake/listing decisions and API evolution.
- [Pricing Plane chat backup](../pricing-plane-chat-backup.md) — historical pricing/apply/sync implementation context.

When implementation and this playbook ever disagree, use the running configuration plus the source/runbook as the authority, then update this playbook as part of the same change.
