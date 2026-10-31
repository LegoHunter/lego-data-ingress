# Lego Data Ingress Runbook

This runbook documents the operational surface of `lego-data-ingress`: controllers, scheduled jobs, event-driven ingestion flows, configuration settings, rollout switches, logging, metrics, and validation steps.

The service defaults to the `local,sandbox` profiles unless overridden by `spring.profiles.active`. Runtime OpenAPI is available at `/swagger-ui/index.html` and `/v3/api-docs` when the service is running.

## Contents

- [Service Scope](#service-scope)
- [Profiles And External Config](#profiles-and-external-config)
- [REST Controllers](#rest-controllers)
- [Scheduled Jobs](#scheduled-jobs)
- [Kafka And S3 Event Pipelines](#kafka-and-s3-event-pipelines)
- [Configuration Reference](#configuration-reference)
- [Readiness And Actuator](#readiness-and-actuator)
- [Metrics](#metrics)
- [Structured Logs](#structured-logs)
- [Rollout Procedures](#rollout-procedures)
- [Operational Checks](#operational-checks)

## Service Scope

`lego-data-ingress` handles these operational workflows:

| Area | Mode | Purpose |
| --- | --- | --- |
| Photo ingestion | Kafka/S3 event driven | Process uploaded MinIO photo objects into durable DB photo metadata and object-storage state. |
| Photo delete reconciliation | Kafka/S3 event driven | React to final photo object removals. |
| BrickLink catalog ingestion | Kafka/S3 event driven | Parse BrickLink XML catalog/category files and upsert external catalog/category tables. |
| Rebrickable catalog ingestion | Kafka/S3 event driven | Parse Rebrickable gzipped CSV catalog/theme files and upsert external catalog/category tables. |
| Image-hosting sync | REST and scheduled job | Reconcile item inventory photos and albums to Flickr through `lego-imaging`. |
| Image-hosting repair | REST | Repair DB links from current remote image-hosting state. |
| BrickLink pricing crawl | Scheduled job | Crawl configured active and draft BrickLink marketplace listings, hydrate missing BrickLink internal catalog ids, and persist immutable pricing snapshots/listings for later pricing decisions. |
| BrickLink pricing decision | Scheduled job | Read latest BrickLink pricing snapshots, compute competitive prices for active listings and unpriced drafts, and persist auditable non-applied pricing decisions. |
| BrickLink pricing apply readiness | Scheduled job | Dry-run review of latest proposed pricing decisions, including first-price decisions for unpriced drafts; writes no listing prices and triggers no marketplace sync. |
| BrickLink order sync | Scheduled job | Poll BrickLink open orders and, when apply mode is enabled, sync marketplace order staging tables. |
| Fulfillment sync | Scheduled job | Map canonically projected, invoiced BrickLink orders to ShipStation drafts, then persist confirmed shipments and reconcile them back to BrickLink when apply mode is enabled. |

## Core Data Semantics

### `item_inventory.sale_intent_code`

The inventory model separates physical inventory state from the owner's intent for that inventory item. `item_inventory.inventory_state_code` answers "what happened to this owned item operationally?", while `item_inventory.sale_intent_code` answers "is this owned item intended to be sold?".

`sale_intent_code` is stored on `item_inventory` and references the `item_inventory_sale_intent` lookup table. In the Java model this is exposed as `ItemInventory.saleIntentCode`. The mappers default missing values to `UNDECIDED` on insert/upsert, and `ItemInventoryDao.updateSaleIntent(...)` updates the code, update timestamp, and optional note together.

Valid values:

| Value | Lookup name | Meaning | Operational effect |
| --- | --- | --- | --- |
| `SELLABLE` | Sellable | The owned item may be listed for sale when its inventory state also allows normal inventory workflows. | This is the positive sale-intent signal. A listing workflow may consider the item eligible only when this is paired with an available inventory state, usually `inventory_state_code='AVAILABLE'`. |
| `KEEP` | Keep | The owned item is part of the personal collection and should not be listed for sale. | Listing automation should treat this as a hard "do not list" signal. Existing listings should be reviewed because the owner's intent says the item should stay in the collection. |
| `UNDECIDED` | Undecided | The owned item has not been classified for sale intent. This is the default when no explicit value is supplied. | Automation should not list the item automatically. This protects newly migrated or newly entered inventory until the owner explicitly marks it sellable. |

Related columns:

| Column | Meaning |
| --- | --- |
| `item_inventory.sale_intent_code` | Current sale-intent classification. Defaults to `UNDECIDED` when omitted by insert/upsert mapper paths. |
| `item_inventory.sale_intent_updated_at` | Timestamp of the latest sale-intent update. Defaults to the current timestamp when omitted. |
| `item_inventory.sale_intent_note` | Optional human note explaining why the item is sellable, being kept, or undecided. |

Sale intent should not be confused with marketplace listing status. `sale_intent_code='SELLABLE'` means the owned inventory item is allowed to be listed; it does not mean there is already an active marketplace listing. Active sale exposure is represented by marketplace listing rows such as `marketplace_listing.listing_status_code='ACTIVE'`.

Pricing Plane jobs operate from configured `ACTIVE` and `DRAFT` `marketplace_listing` rows. An unpriced non-fixed `DRAFT` is an intentional first-price onboarding state; an active listing or a draft with a current price follows the normal repricing path. Once an item has an active listing, pricing crawl, decision, readiness, apply, and sync behavior is driven mainly by listing state, condition/completeness, fixed-price settings, and Pricing Plane configuration. Sale intent remains important upstream because it should prevent non-sellable inventory from becoming listed in the first place.

## Profiles And External Config

### Default Profiles

`src/main/resources/application.yml` sets:

```yaml
spring:
  profiles:
    default: sandbox,local
```

If no profile is supplied, Spring loads `application.yml`, `application-sandbox.yml`, and `application-local.yml`.
`local` is intentionally last so its one-minute worker cadence overrides the
shared SANDBOX settings used by local development.

### Local Profile

`application-local.yml` sets:

| Setting | Default | Purpose |
| --- | --- | --- |
| `import-path` | `/dev/config` | Base path for optional sandbox credential/config imports. |
| `management.endpoints.web.exposure.include` | `*` | Exposes all actuator endpoints locally. |
| `management.tracing.enabled` | `false` | Disables tracing locally. |
| `management.defaults.metrics.export.enabled` | `false` | Disables default metrics export locally. |
| `logging.level.root` | `INFO` | Default local log level. |
| `logging.level.com.bricklink.api` | `DEBUG` | Enables BrickLink REST debug logs locally. |
| `lego.minio.url` | `https://api.minio.dev.internal.legohunter.io` | Local MinIO URL. |

### Sandbox Profile

`application-sandbox.yml` imports these external files from `${import-path}`:

| File                            | Required for | Typical contents |
|---------------------------------| --- | --- |
| `database-configuration.yml`    | DB access | `lego.data`, `lego.databases`, datasource-related settings. |
| `kafka-configuration.yml`       | Kafka auth | `lego.kafka.consumer.username/password`, `lego.kafka.producer.username/password`. |
| `minio-configuration.yml`       | S3/MinIO access | `lego.minio.access-key`, `lego.minio.secret-key`. |
| `flickr-configuration.yml`      | Flickr image hosting | `flickr.user-id`, `flickr.secrets.*`, optional debug flags. |
| `bitly-configuration.yml`       | Short URLs for image-hosting apply | `bitly.base-url`, `bitly.access-token`, `bitly.group-guid`. |
| `bricklink-client-api-keys.yml` | BrickLink REST order sync | `bricklink.rest.consumer.*`, `bricklink.rest.token.*`. |
| `shipstation-configuration.yml` | ShipStation fulfillment sync | `shipstation.rest.api-key`, `shipstation.rest.api-secret`, optional `shipstation.rest.*` logging settings. Optional import; required when fulfillment scheduled sync is enabled. |

All imports are plain `file:` imports, so missing files fail startup unless the runtime supplies them.

### Kubernetes Profile

`application-kubernetes.yml` sets:

| Setting | Value | Purpose |
| --- | --- | --- |
| `import-path` | `/etc/.credentials` | Location where external secrets are projected. |
| `spring.main.banner-mode` | `off` | Quiet startup banner. |
| `management.endpoints.web.exposure.include` | `health,info,prometheus,metrics` | Restricts actuator exposure. |
| `management.endpoint.health.probes.enabled` | `true` | Enables Kubernetes liveness/readiness probes. |
| `management.endpoint.health.group.liveness.include` | `livenessState` | Liveness group contents. |
| `management.endpoint.health.group.readiness.include` | `readinessState,db,imageHostingReadiness` | Readiness group contents. |
| `management.tracing.sampling.probability` | `1.0` | Samples all traces when tracing is enabled by the runtime. |
| `kafka.properties.security.protocol` | `SASL_PLAINTEXT` | Cluster-internal Kafka security protocol override. |
| `lego.minio.url` | `http://minio.minio-dev.svc.cluster.local` | Cluster-internal MinIO URL. |

## REST Controllers

All current business controllers are internal image-hosting controllers under `/internal/image-hosting`. There are no request bodies; inputs are path variables and query parameters.

Detailed response examples and workflow guidance for the image-hosting endpoints are also maintained in [docs/image-hosting-api.md](docs/image-hosting-api.md).

### Provider Selection Parameters

Most image-hosting endpoints accept the same provider selection parameters.

| Parameter | Required | Default | Valid values | Description |
| --- | --- | --- | --- | --- |
| `provider` | No | `lego.image-hosting.default-provider` | A configured key under `lego.image-hosting.providers`, currently `flickr` | Selects provider-specific defaults. |
| `externalServiceId` | No | Provider `external-service-id`, otherwise `lego.image-hosting.sync.external-service-id` | Integer matching an `external_service` row | Overrides the external service id used for DB lookups/writes. |

### Remote Read Parameters

Remote snapshot, sync-plan, and repair-plan endpoints can read remote provider state.

| Parameter | Required | Default | Valid values | Description |
| --- | --- | --- | --- | --- |
| `userId` | No | Provider/client configuration where supported | Flickr user id, for example `144144385@N08` | Explicit remote account/user selection. |
| `albumPageSize` | No | `500` | Positive integer accepted by the provider | Page size for remote album listing. |
| `photoPageSize` | No | `500` | Positive integer accepted by the provider | Page size for remote album photo listing. |

### Controller Summary

| Controller | Method | Path | Writes remote provider | Writes DB | Purpose |
| --- | --- | --- | --- | --- | --- |
| `ImageHostingSyncController` | `POST` | `/internal/image-hosting/item-inventories/{itemInventoryId}/sync` | Yes when `dryRun=false` | Yes when `dryRun=false` | Legacy direct image-hosting sync workflow for one item inventory. Prefer sync-plan endpoints for controlled operations. |
| `GeneratedDescriptionController` | `GET` | `/internal/image-hosting/item-inventories/{itemInventoryId}/generated-description` | No | No | Preview the generated DB-backed title/description used by image-hosting sync. |
| `ImageHostingRemoteSnapshotController` | `GET` | `/internal/image-hosting/remote/albums/{albumId}/snapshot` | No | No | Read the current remote album/photos state for inspection. |
| `ImageHostingSyncPlanController` | `GET` | `/internal/image-hosting/item-inventories/{itemInventoryId}/sync-plan` | No | No | Build a dry-run reconciliation plan comparing DB desired state to remote image-hosting state. |
| `ImageHostingSyncPlanController` | `POST` | `/internal/image-hosting/item-inventories/{itemInventoryId}/sync-plan/apply` | Yes, depending on plan actions | Yes, depending on plan actions | Rebuild and apply the current sync plan. |
| `ImageHostingDbRepairController` | `GET` | `/internal/image-hosting/item-inventories/{itemInventoryId}/repair-plan` | No | No | Build a DB repair plan from current remote image-hosting state. |
| `ImageHostingDbRepairController` | `POST` | `/internal/image-hosting/item-inventories/{itemInventoryId}/repair-plan/apply` | Usually no, but safe provider membership operations may occur | Yes | Apply DB link repair actions. |

### Endpoint Parameters

| Endpoint | Additional parameters |
| --- | --- |
| `POST /item-inventories/{itemInventoryId}/sync` | `dryRun` defaults to `true`; `retryFailed` defaults to `false`; also accepts `provider` and `externalServiceId`. |
| `GET /item-inventories/{itemInventoryId}/generated-description` | Accepts `provider` and `externalServiceId`. |
| `GET /remote/albums/{albumId}/snapshot` | Accepts `provider`, `externalServiceId`, `userId`, `albumPageSize`, `photoPageSize`. |
| `GET /item-inventories/{itemInventoryId}/sync-plan` | Accepts `provider`, `externalServiceId`, `userId`, `albumPageSize`, `photoPageSize`. |
| `POST /item-inventories/{itemInventoryId}/sync-plan/apply` | Same as sync-plan plus `allowReviewRequired`, default `false`. |
| `GET /item-inventories/{itemInventoryId}/repair-plan` | Accepts `provider`, `externalServiceId`, `userId`, `albumPageSize`, `photoPageSize`. |
| `POST /item-inventories/{itemInventoryId}/repair-plan/apply` | Same as repair-plan plus `allowReviewRequired`, default `false`. |

Examples:

```http
GET /internal/image-hosting/item-inventories/335/generated-description
GET /internal/image-hosting/item-inventories/335/sync-plan
POST /internal/image-hosting/item-inventories/335/sync-plan/apply
POST /internal/image-hosting/item-inventories/335/sync-plan/apply?allowReviewRequired=true
GET /internal/image-hosting/remote/albums/72157713151696202/snapshot?provider=flickr
GET /internal/image-hosting/item-inventories/335/repair-plan
POST /internal/image-hosting/item-inventories/335/repair-plan/apply
POST /internal/image-hosting/item-inventories/335/sync?dryRun=false&retryFailed=true
```

Operational notes:

| Behavior | Detail |
| --- | --- |
| Plan-first safety | `POST /sync-plan/apply` rebuilds the plan before applying. Re-check if remote state may have changed since the last `GET /sync-plan`. |
| Review-required actions | Blocked unless `allowReviewRequired=true`. Do not use this switch blindly in automation. |
| Scheduled job integration | The scheduled image-hosting job uses the same planner/executor path. |
| Repair endpoints | Use when Flickr already has the album/photos but DB rows in `external_image`, `external_image_album`, or `external_image_album_image` are missing or stale. |

## Scheduled Jobs

Scheduling is enabled globally by `io.legohunter.ingress.scheduling.config.SchedulingConfiguration` using `@EnableScheduling`. ShedLock is configured by `SchedulerConfiguration`, so scheduled jobs should have lock rows available in the database configured by the application.

### BrickLink Order and Fulfillment Cadence

Both the BrickLink order-ingestion and ShipStation fulfillment jobs use a fixed
delay measured from the end of the preceding run. Their effective schedules are
profile-owned; `application-kubernetes.yml` deliberately does not override
either cadence.

| Active profile(s) | Order-ingestion delay | Fulfillment delay | Order-ingestion apply | Fulfillment apply | `lego.environment.production` |
| --- | --- | --- | --- | --- | --- |
| Base configuration before a profile override | 1 hour | 1 hour | Disabled (safe base default) | Disabled | `false` |
| Default local `sandbox,local` | 1 minute | 1 minute | Enabled (local DB only) | **Disabled (dry-run)** | `false` |
| `sandbox,kubernetes` | 5 minutes | 5 minutes | Enabled (local DB only) | **Disabled (dry-run)** | `false` |
| `dev,kubernetes` | 5 minutes | 5 minutes | Enabled (local DB only) | **Disabled (dry-run)** | `false` |
| `prod,kubernetes` | 1 hour | 1 hour | Enabled | Enabled | `true` |

`application-prod.yml` makes the production cadence explicit. For a controlled
dry run, override either job's `apply` property to `false`; setting `enabled` to
`false` stops that job entirely.

#### Orders are PROD-only writes

All LCMS environments share one BrickLink account and one ShipStation account, so
every order is a real buyer order. **Only PROD may write to orders**: ShipStation
order create/update, BrickLink order shipping/status updates, and Drive Thru emails
to buyers. Lower environments (local, sandbox, dev) may only read orders.

- Order ingestion (`BricklinkOpenOrderProbeJob`) only reads from BrickLink. With
  `apply=true` it writes staged orders into the environment's own database.
- Fulfillment (`FulfillmentSyncService`) performs the external writes. It applies
  only when **both** `lego.fulfillment.sync.scheduled.apply=true` **and**
  `lego.environment.production=true`. Otherwise it runs dry-run mapping. If apply
  is requested outside production, every run logs
  `fulfillment.sync_job.apply_suppressed` and skips candidates with
  `SKIPPED_NON_PRODUCTION`.
- `lego.environment.production` defaults to `false` and is set to `true` only in
  `application-prod.yml`. Never set it in another profile or through an
  environment override.

### `BricklinkOpenOrderProbeJob`

Class: `io.legohunter.ingress.source.bricklink.orders.BricklinkOpenOrderProbeJob`

Condition:

```yaml
lego.bricklink.orders.sync.scheduled.enabled: true
```

Schedule:

| Setting | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.bricklink.orders.sync.scheduled.fixed-delay-ms` | `3600000` in code and base YAML; profile cadence above overrides it | Long milliseconds, >= 0 | Delay between the end of one run and the start of the next. |
| `lego.bricklink.orders.sync.scheduled.initial-delay-ms` | `30000` in code and base YAML | Long milliseconds, >= 0 | Delay after app startup before first run. |
| `lego.bricklink.orders.sync.scheduled.lock-at-most-for` | `10m` | ShedLock duration, for example `30s`, `10m`, `1h` | Maximum distributed lock duration. |
| `lego.bricklink.orders.sync.scheduled.lock-at-least-for` | `0s` | ShedLock duration | Minimum distributed lock duration. |

Operational modes:

| `apply` | Behavior |
| --- | --- |
| `false` | Probe mode. Authenticates to BrickLink, lists configured open order statuses, fetches order details and items, logs coverage/counters, writes no marketplace sync rows. |
| `true` | Write-side sync mode. Creates a sync-run row, upserts marketplace orders, syncs marketplace order items, stores raw payload audit rows, and deletes stale item rows no longer present in the latest response. |

Data written when `apply=true`:

| Table | Write behavior |
| --- | --- |
| `marketplace_order_sync_run` | Inserted at run start as `STARTED`; updated at completion as `SUCCESS`, `PARTIAL_FAILURE`, `FAILED`, or `NO_WORK`. |
| `marketplace_order` | Upserted by marketplace/order natural key. Tracks status, buyer/shipping/payment/cost fields, counts, weights, flags, payload hash, and last seen time. |
| `marketplace_order_item` | Existing rows are updated by stable generated external order item id; new rows are inserted; stale rows for the order are deleted. |
| `marketplace_order_payload` | Inserts raw JSON audit payloads for order detail and order items responses with SHA-256 hashes. |

BrickLink API calls per run:

| Step | Description |
| --- | --- |
| List orders | Calls order list endpoint once per configured status. |
| Include cancelled | If `include-unfiled-cancelled=true`, also lists `CANCELLED` with `filed=false` unless already included in `statuses`. |
| Fetch detail | Calls get-order detail for each unique discovered order id. |
| Fetch items | Calls get-order-items for each fetched order. |

Important logs:

| Event | Meaning |
| --- | --- |
| `bricklink.order_sync.probe.started` | Job started with configured direction/statuses/apply mode. |
| `bricklink.order_sync.probe.order_query_failed` | A status query failed; job continues with other statuses. |
| `bricklink.order_sync.probe.order_fetched` | One order detail and item list were fetched. |
| `bricklink.order_sync.probe.coverage` | Field coverage for one fetched order. |
| `bricklink.order_sync.probe.item_coverage` | Field coverage across fetched order items. |
| `bricklink.order_sync.probe.completed` | Job completed with counts and elapsed time. |
| `bricklink.order_sync.probe.failed` | Job-level failure. |

### `BricklinkPricingCrawlJob`

Class: `io.legohunter.ingress.source.bricklink.pricing.BricklinkPricingCrawlJob`

Condition:

```yaml
lego.bricklink.pricing.crawl.enabled: true
lego.bricklink.pricing.crawl.scheduled.enabled: true
```

`lego.bricklink.pricing.crawl.enabled=true` creates the crawl service. The scheduled job bean is created only when both `enabled` and `scheduled.enabled` are true.

Schedule and selection settings:

| Setting | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.bricklink.pricing.crawl.scheduled.fixed-delay-ms` | `300000` | Long milliseconds, >= 0 | Delay between scheduler/worker runs. Keep conservative for live BrickLink calls. |
| `lego.bricklink.pricing.crawl.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | Delay after startup before first run. |
| `lego.bricklink.pricing.crawl.scheduled.lock-at-most-for` | `30m` | ShedLock duration | Maximum distributed lock duration. |
| `lego.bricklink.pricing.crawl.scheduled.lock-at-least-for` | `0s` | ShedLock duration | Minimum distributed lock duration. |
| `lego.bricklink.pricing.crawl.batch-size` | `25` | Integer; effective value is at least `1` | Maximum eligible marketplace listings considered for scheduling per run. |
| `lego.bricklink.pricing.crawl.worker-batch-size` | `1` | Integer; effective value is at least `1` | Maximum due `pricing_crawl_work_item` rows claimed and processed per run. Keep small for live BrickLink calls. |
| `lego.bricklink.pricing.crawl.results-per-page` | `500` | Integer; effective value is at least `1` | BrickLink `catalogifs.ajax` `rpp` parameter. Current default asks for up to 500 comparables per item/condition. |
| `lego.bricklink.pricing.crawl.max-attempts` | `3` | Integer; effective value is at least `1` | Stored on each work item. Retryable lookup/pricing failures are requeued until this count is reached. |
| `lego.bricklink.pricing.crawl.crawl-cadence` | `7d` | Spring `Duration`, positive | Cooling period before the same listing is eligible to be scheduled again after success, skip, or terminal failure. |
| `lego.bricklink.pricing.crawl.schedule-spread-window` | `72h` | Spring `Duration`, zero or positive | Window used to spread newly scheduled work item `next_attempt_at` values across time. |
| `lego.bricklink.pricing.crawl.schedule-jitter` | `0s` | Spring `Duration`, zero or positive | Optional random positive jitter added to newly scheduled work item due times. |
| `lego.bricklink.pricing.crawl.retry-backoff` | `6h` | Spring `Duration`, positive | Delay before retrying a retryable failed work item. |
| `lego.bricklink.pricing.crawl.claim-stale-after` | `2h` | Spring `Duration`, positive | Age after which abandoned `CLAIMED` work is requeued to `PENDING`. |
| `lego.bricklink.pricing.crawl.priceable-listing-status-codes` | `ACTIVE,DRAFT` | Set of listing status codes | Listing statuses eligible for pricing crawl onboarding. This allows newly created local drafts to get crawl work before marketplace sync. If blank/empty, the legacy `active-listing-status-code` fallback is used. |
| `lego.bricklink.pricing.crawl.marketplace-listing-allowlist` | empty | Set of integer `marketplace_listing_id` values | Optional sandbox guard. When populated, only those listings are scheduled. |
| `lego.bricklink.pricing.crawl.blackout.enabled` | `false` | `true`, `false` | When true, scheduled due times and retry due times are moved out of the configured local blackout period. |
| `lego.bricklink.pricing.crawl.blackout.zone-id` | `America/New_York` | Java time zone id | Time zone used to evaluate blackout windows. |
| `lego.bricklink.pricing.crawl.blackout.start` | `21:30` | `HH:mm` local time | Start of the blackout window. |
| `lego.bricklink.pricing.crawl.blackout.end` | `08:30` | `HH:mm` local time | End of the blackout window. |
| `lego.bricklink.pricing.crawl.blackout.weekdays-only` | `true` | `true`, `false` | When true, blackout adjustment is applied only on Monday through Friday local dates. |

Candidate selection:

| Requirement | Detail |
| --- | --- |
| Marketplace listing service | `marketplace_listing.listing_external_service_id` must equal `lego.bricklink.pricing.crawl.bricklink-external-service-id`. |
| Marketplace listing status | `marketplace_listing.listing_status_code` must be in `lego.bricklink.pricing.crawl.priceable-listing-status-codes`, defaulting to `ACTIVE` and `DRAFT`. |
| Catalog link | `marketplace_listing.external_catalog_item_id` must be populated. |
| Already queued work | Listings with `PENDING` or `CLAIMED` work are not scheduled again. |
| Cooling period | Listings with any work item whose `next_attempt_at` is still in the future are not scheduled again. |
| Batch limit | The scheduling query orders by `marketplace_listing_id` and applies `lego.bricklink.pricing.crawl.batch-size`. |
| Allowlist | If `marketplace-listing-allowlist` is populated, candidates outside the allowlist are ignored after DB selection. |

Crawler behavior:

| Step | Description |
| --- | --- |
| Recover stale claims | Requeues old `CLAIMED` work back to `PENDING` when `claimed_at` is older than `claim-stale-after` and attempts remain. |
| Schedule work | Inserts `pricing_crawl_work_item` rows with `PENDING`, `attempt_count=0`, and `next_attempt_at` spread across `schedule-spread-window` plus optional jitter/blackout adjustment. |
| Claim due work | Atomically transitions due `PENDING` rows to `CLAIMED`, increments `attempt_count`, and limits processing to `worker-batch-size`. |
| Load inventory | Loads `item_inventory` for the listing so condition and completeness can be captured. |
| Resolve condition | Maps `item_inventory.new_or_used` values `N`/`NEW` to BrickLink `N`, and `U`/`USED` to BrickLink `U`. |
| Resolve item number | Uses `external_catalog_item.external_item_key`, for example `6390-1`. |
| Hydrate `idItem` | If `external_catalog_item.external_unique_key` is missing or not parseable as an integer, calls `searchproduct.ajax` through `BricklinkAjaxClient.findCatalogItem(itemNumber, itemType)`. The item type comes from `external_catalog_item.item_type_code` when present, falling back to `lego.bricklink.pricing.crawl.catalog-item-type` only when the catalog row has no type. |
| Persist `idItem` | Stores the returned BrickLink internal catalog id in `external_catalog_item.external_unique_key` so future runs skip search hydration. |
| Crawl comparables | Calls `catalogifs.ajax` through `BricklinkAjaxClient.catalogItemsForSaleByInternalItemId(itemId, condition, resultsPerPage)`. |
| Persist snapshot | Inserts one immutable `pricing_snapshot` per successful listing crawl. |
| Persist snapshot listings | Inserts one immutable `pricing_snapshot_listing` per comparable listing returned by BrickLink. |
| Complete work item | Updates the work item to `SUCCEEDED` or a failure/skip status, clears `claimed_at`, and sets `next_attempt_at` using `crawl-cadence`. |
| Retry work item | Retryable lookup/pricing failures return the work item to `PENDING`, clear `claimed_at`, and set `next_attempt_at` using `retry-backoff`. |

BrickLink AJAX endpoints used:

| Endpoint | Purpose | Parameters |
| --- | --- | --- |
| `/ajax/clone/search/searchproduct.ajax` | Find BrickLink internal `idItem` for a public item number when `external_unique_key` is missing. | `q=<item number>`, `type=<external_catalog_item.item_type_code or configured fallback>` |
| `/ajax/clone/catalogifs.ajax` | Fetch active BrickLink listings for one internal item id and condition. | `itemid=<idItem>`, `cond=N|U`, `rpp=<resultsPerPage>`, `iconly=0` |

Condition and completeness semantics:

| Field | Meaning |
| --- | --- |
| `pricing_snapshot.item_condition_code` | Target condition requested for the crawl, derived from the owned `item_inventory.new_or_used`. |
| `pricing_snapshot.completeness_code` | Target completeness from the owned `item_inventory.completeness`. |
| `pricing_snapshot_listing.item_condition_code` | Comparable listing condition returned by BrickLink AJAX. |
| `pricing_snapshot_listing.completeness_code` | Comparable listing completeness/sub-condition returned by BrickLink AJAX. |

The BrickLink pricing AJAX request currently filters by condition only. The crawler intentionally persists all returned rows for that condition. Later pricing logic must select exact comparables where comparable condition and comparable completeness match the target snapshot condition and completeness.

Data written:

| Table | Write behavior |
| --- | --- |
| `pricing_crawl_work_item` | Inserted as durable scheduled work. Due rows are claimed before AJAX calls, updated with request metadata, completion/retry status, next due time, and last error. |
| `external_catalog_item` | Updated only when missing BrickLink internal `idItem` is successfully hydrated into `external_unique_key`. |
| `pricing_snapshot` | Inserted once for each successful pricing crawl. Captures source item number, internal `idItem`, requested condition, inventory completeness, request metadata, payload hash, comparable count, and capture time. |
| `pricing_snapshot_listing` | Inserted once per comparable listing returned by BrickLink. Captures external listing id, seller, country, condition, completeness, quantity, unit price, currency, description, and raw comparable payload. |

Work item statuses:

| Status | Meaning |
| --- | --- |
| `PENDING` | Work is scheduled and eligible to be claimed when `next_attempt_at <= now` and attempts remain. |
| `CLAIMED` | Worker claimed the row for processing. `attempt_count` has already been incremented. |
| `SUCCEEDED` | Pricing snapshot and returned comparable rows were persisted. |
| `SKIPPED_MISSING_LISTING` | The queued `marketplace_listing` row no longer exists. |
| `SKIPPED_MISSING_CATALOG` | The queued listing no longer has a linked catalog item. |
| `SKIPPED_MISSING_CONDITION` | `item_inventory.new_or_used` was absent or not recognized as New/Used. No AJAX calls are made. |
| `SKIPPED_MISSING_ITEM_NUMBER` | `external_catalog_item.external_item_key` was absent. No pricing AJAX call is made. |
| `FAILED_ITEM_ID_LOOKUP_NO_MATCH` | `searchproduct.ajax` found no exact catalog item match. |
| `FAILED_ITEM_ID_LOOKUP_AMBIGUOUS` | `searchproduct.ajax` found multiple possible catalog items. |
| `FAILED_ITEM_ID_LOOKUP_HTTP_ERROR` | Item id lookup failed due to client/runtime error. |
| `FAILED_PRICING_HTTP_ERROR` | Pricing AJAX call failed due to client/runtime error. |
| `FAILED_PRICING_PARSE_ERROR` | Pricing payload serialization/parsing failed during persistence. |

BrickLink can return an empty JSON array from `catalogifs.ajax` when the catalog item has no current for-sale comparables. The crawler treats that as a successful source observation, not as an HTTP failure: it writes a `pricing_snapshot` with `comparable_count=0`, writes no `pricing_snapshot_listing` rows, increments the `zero_comparable_snapshot` metric, and marks the work item `SUCCEEDED`.

Outcomes:

| Outcome | Meaning |
| --- | --- |
| `NO_WORK` | No work was scheduled, claimed, processed, or requeued. |
| `SCHEDULED` | Work items were scheduled or stale claims were requeued, but no due work was claimed in this run. |
| `SUCCESS` | At least one due work item was claimed and no listing failed. Skipped listings do not currently make the run outcome partial. |
| `PARTIAL_SUCCESS` | At least one claimed work item failed. |

Important logs:

| Event | Meaning |
| --- | --- |
| `bricklink.pricing.crawl.schedule.skipped_missing_catalog` | Scheduling candidate had no attached `ExternalCatalogItem`; no work item is created for that listing. |
| `bricklink.pricing.crawl.skipped_missing_catalog` | Claimed work item pointed to a listing with no attached `ExternalCatalogItem`; work item is skipped. |
| `bricklink.pricing.crawl.job.completed` | Scheduled run completed and logs `BricklinkPricingCrawlResult` with selected, scheduled, claimed, stale requeue, snapshot, listing, hydration, skip, failure, and elapsed counters. |

Operational boundaries:

| Boundary | Detail |
| --- | --- |
| Crawl only captures source data | The crawl job only persists pricing history. Competitive price calculation is handled by `BricklinkPricingDecisionJob`; neither job updates listing prices or triggers marketplace sync in Phase 4. |
| Immutable history | `pricing_snapshot` and `pricing_snapshot_listing` are append-only observations for each crawl. The same BrickLink listing can appear in many snapshots over time. |
| No latest competitor table yet | Current market views should query the latest relevant snapshot and its listings. A mutable latest competitor table is intentionally deferred. |
| Durable queue | The scheduler creates `PENDING` work and the worker processes only due rows it can claim. This is not strict FIFO, but due rows are selected by `next_attempt_at` then work item id. |
| AJAX rate limit protection | Outbound AJAX calls are protected by `bricklink-ajax` rate limiting. Keep it enabled for live runs. |

### Pricing Plane Table And Timing Mental Model

The Pricing Plane has two separate responsibilities:

1. Crawling captures source data from BrickLink and stores immutable pricing history.
2. Decisioning reads stored crawl data and writes read-only pricing recommendations.

The decision job does not call BrickLink. It only reads data already persisted by the crawl job.

Table responsibilities:

| Table | Mental model | Primary question it answers |
| --- | --- | --- |
| `pricing_crawl_work_item` | Durable crawl queue. One row means "crawl BrickLink pricing for this marketplace listing at or after `next_attempt_at`." | What needs to be crawled, when can it run, and what happened last time? |
| `pricing_snapshot` | Immutable crawl header. One row means "at this time, BrickLink pricing data was captured for this listing/item/condition/completeness target." | What was the latest successful source-data capture for this listing and target condition? |
| `pricing_snapshot_listing` | Immutable comparable rows inside a snapshot. One row is one competitor listing returned by BrickLink. | Which competitor listings and prices were available in that captured source data? |
| `pricing_decision` | Historical pricing calculation output. One row means "at this time, the algorithm evaluated this listing and wrote a proposed, skipped, or failed decision." | What did the pricing algorithm decide, and why? |

The normal flow:

```text
active marketplace_listing
        |
        v
pricing_crawl_work_item
        |
        | when PENDING and next_attempt_at <= now
        v
BrickLink AJAX crawl
        |
        v
pricing_snapshot
        |
        v
pricing_snapshot_listing rows
        |
        v
pricing_decision
```

`pricing_crawl_work_item` controls pacing. A large batch size does not mean all listings are crawled immediately. The worker only processes due rows:

```sql
work_status_code = 'PENDING'
and next_attempt_at <= current_timestamp
```

The scheduler may spread newly created work across `schedule-spread-window`, add `schedule-jitter`, and move due times out of the configured blackout window. The `bricklink-ajax` client still enforces the process-local minimum delay between AJAX request starts.

Current snapshot:

| Term | Meaning |
| --- | --- |
| Current snapshot | The newest `pricing_snapshot` for the marketplace listing whose `item_condition_code` and `completeness_code` match the listing's normalized `item_inventory.new_or_used` and `item_inventory.completeness`. |
| Not available | No matching `pricing_snapshot` exists yet. The listing can be valid and priceable, but the crawler has not stored matching source data yet. |
| Zero-comparable snapshot | A matching `pricing_snapshot` exists and proves BrickLink returned no current for-sale comparable rows for that crawl. This is different from no snapshot. |
| Historical snapshot | Any older `pricing_snapshot` for the same listing. Historical rows are retained for audit and trend analysis. |

There is no separate mutable "current snapshot" table. "Current" is a query-time concept: select the latest matching snapshot by `captured_at` and `pricing_snapshot_id`.

Example:

| Owned listing state | Matching snapshot needed by decision job |
| --- | --- |
| `item_inventory.new_or_used = U`, `item_inventory.completeness = C` | latest `pricing_snapshot` where `item_condition_code = 'U'` and `completeness_code = 'C'` for the same `marketplace_listing_id` |
| `item_inventory.new_or_used = N`, `item_inventory.completeness = S` | latest `pricing_snapshot` where `item_condition_code = 'N'` and `completeness_code = 'S'` for the same `marketplace_listing_id` |

Why a snapshot may not be available:

| Cause | What to check |
| --- | --- |
| Crawl work is waiting | `pricing_crawl_work_item.work_status_code = 'PENDING'` and `next_attempt_at > current_timestamp`. |
| Crawl worker has not run long enough | The worker processes only due rows and respects `worker-batch-size` plus the AJAX rate limiter. |
| Missing BrickLink internal `idItem` still needs hydration | `external_catalog_item.external_unique_key` is null. The crawler must call `searchproduct.ajax` before `catalogifs.ajax`. |
| Crawl failed or is waiting for retry | Check `pricing_crawl_work_item.work_status_code`, `attempt_count`, `next_attempt_at`, and `last_error_message`. |
| Snapshot exists for another target | Check `pricing_snapshot.item_condition_code` and `pricing_snapshot.completeness_code`; a `N/S` snapshot does not satisfy a `U/C` listing. |

Decision outcomes that commonly indicate timing or source-data state:

| Decision reason | Meaning | Next action |
| --- | --- | --- |
| `NO_CURRENT_SNAPSHOT` | No latest matching snapshot exists for the listing condition/completeness. | Let the crawl scheduler/worker run, or inspect pending crawl work. |
| `NO_CURRENT_COMPARABLES` | A latest matching snapshot exists, but BrickLink returned zero current comparable listings in that snapshot. | No crawl timing problem exists; review manually, wait for market listings, or use a later fallback strategy. |
| `NO_EXACT_COMPARABLES` | A matching snapshot exists, but after exact condition/completeness filtering and own-listing exclusion there are no usable competitor rows. | Inspect `pricing_snapshot_listing` rows for that snapshot and confirm BrickLink has comparable listings. |
| `FIXED_PRICE_OVERRIDE` | Listing is marked fixed price, so no snapshot is needed and the current price remains authoritative. | No action unless the fixed-price flag should change. |
| `PROPOSED` reason codes | A snapshot and exact comparables were available and the algorithm produced a recommendation. | Review latest decision rows before any future apply phase. |

Timing example:

| Time | Event | Result |
| --- | --- | --- |
| 8:00 PM | Crawl scheduler finds 100 active listings. | Inserts `PENDING` work items spread across the configured window. |
| 8:01 PM | First work item becomes due. | Worker claims it, calls BrickLink, and writes a snapshot/listings. |
| 8:05 PM | Decision job runs. | Listings with snapshots can produce `PROPOSED`; listings still waiting on crawl work may produce `NO_CURRENT_SNAPSHOT` unless `require-current-snapshot=true`. |
| Later | More work items become due and are crawled. | Later decision runs can supersede earlier failed decisions with new `PROPOSED` rows. |

Important review rule:

`pricing_decision` is historical. Do not judge current pricing health by all rows mixed together. Review the latest decision per `marketplace_listing_id`. A listing can have an older `NO_CURRENT_SNAPSHOT` row and a newer `PROPOSED` row; the newer row is the current review state.

### `BricklinkPricingDecisionJob`

Class: `io.legohunter.ingress.source.bricklink.pricing.BricklinkPricingDecisionJob`

Condition:

```yaml
lego.bricklink.pricing.decision.enabled: true
lego.bricklink.pricing.decision.scheduled.enabled: true
```

`lego.bricklink.pricing.decision.enabled=true` creates the decision service. The scheduled job bean is created only when both `enabled` and `scheduled.enabled` are true.

Schedule and selection settings:

| Setting | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.bricklink.pricing.decision.scheduled.fixed-delay-ms` | `300000` | Long milliseconds, >= 0 | Delay between decision calculation runs. |
| `lego.bricklink.pricing.decision.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | Delay after startup before first run. |
| `lego.bricklink.pricing.decision.scheduled.lock-at-most-for` | `10m` | ShedLock duration | Maximum distributed lock duration. |
| `lego.bricklink.pricing.decision.scheduled.lock-at-least-for` | `0s` | ShedLock duration | Minimum distributed lock duration. |
| `lego.bricklink.pricing.decision.batch-size` | `25` | Integer; effective value is at least `1` | Maximum pricing decision candidates selected per run after eligibility filtering. |
| `lego.bricklink.pricing.decision.require-current-snapshot` | `false` | `true`, `false` | When true, non-fixed candidates must already have a matching pricing snapshot for their normalized condition/completeness before they are selected. Fixed-price listings remain eligible. |
| `lego.bricklink.pricing.decision.priceable-listing-status-codes` | `ACTIVE,DRAFT` | Set of listing status codes | Listing statuses eligible for pricing decisions. This lets unpriced local drafts receive a Pricing Plane decision before any marketplace sync. If blank/empty, the legacy `active-listing-status-code` fallback is used. |
| `lego.bricklink.pricing.decision.algorithm-version` | `bricklink-competitive-v1` | Non-blank string | Stored on each `pricing_decision` row for algorithm traceability. |
| `lego.bricklink.pricing.decision.strategy-code` | `LEGACY_COMPETITIVE` | Non-blank string; code trims and uppercases | Stored on each `pricing_decision` row. |
| `lego.bricklink.pricing.decision.minimum-price` | null | Decimal money amount or null | Optional lower bound. If computed price is below this value, final price is clamped and reason is `BELOW_MIN_PRICE_CLAMPED`. |
| `lego.bricklink.pricing.decision.maximum-price` | null | Decimal money amount or null | Optional upper bound. If computed price is above this value, final price is clamped and reason is `ABOVE_MAX_PRICE_CLAMPED`. |

Candidate selection:

| Requirement | Detail |
| --- | --- |
| Marketplace listing service | `marketplace_listing.listing_external_service_id` must equal `lego.bricklink.pricing.decision.bricklink-external-service-id`. |
| Marketplace listing status | `marketplace_listing.listing_status_code` must be in `lego.bricklink.pricing.decision.priceable-listing-status-codes`, defaulting to `ACTIVE` and `DRAFT`. |
| Catalog mapping | `marketplace_listing.external_catalog_item_id` must be populated. |
| Fixed-price eligibility | `marketplace_listing.fixed_price=true` listings are candidates even when condition/completeness are missing because fixed price is authoritative. |
| Non-fixed eligibility | Non-fixed listings must have non-blank `item_inventory.new_or_used` and non-blank `item_inventory.completeness`. |
| Optional snapshot gate | When `require-current-snapshot=true`, non-fixed listings must also have an existing `pricing_snapshot` matching normalized condition/completeness. Use this after crawl data is flowing to avoid filling review batches with `NO_CURRENT_SNAPSHOT` decisions. |
| Batch limit | The selection query filters to eligible candidates, orders by `marketplace_listing_id`, and applies `lego.bricklink.pricing.decision.batch-size`. |

Decision behavior:

| Step | Description |
| --- | --- |
| Fixed price override | If `marketplace_listing.fixed_price=true`, writes a `SKIPPED` decision with reason `FIXED_PRICE_OVERRIDE`, preserves current listing price as `final_price`, and does not read snapshots. |
| Load inventory | Loads `item_inventory` for condition, completeness, box condition, and instructions condition. |
| Normalize codes | Maps `N`/`NEW` to `N`, `U`/`USED` to `U`, `SEALED`/`S` to `S`, `COMPLETE`/`C` to `C`, and `INCOMPLETE`/`I`/`X` to `X`. |
| Select latest snapshot | Reads the latest `pricing_snapshot` for the marketplace listing, normalized condition, and normalized completeness. |
| Select exact comparables | Reads `pricing_snapshot_listing` rows whose condition and completeness match the target snapshot. |
| Exclude own listing | Removes the owned marketplace listing by matching `pricing_snapshot_listing.external_listing_id` to `marketplace_listing.external_listing_id`. |
| Compute price | Runs the ported legacy algorithm and writes a `PROPOSED` decision when successful. |
| Persist metadata | Stores algorithm version, strategy, reason code, computed price, final price, previous price, currency, exact comparable count, confidence, source summary JSON, and notes. |
| No apply | Phase 3 writes decisions only. It does not update `marketplace_listing.unit_price` and does not trigger marketplace sync. |

Legacy algorithm behavior:

| Exact comparable count / case | Price behavior |
| --- | --- |
| `0`, snapshot `comparable_count=0` | Writes a `FAILED` decision with reason `NO_CURRENT_COMPARABLES`. |
| `0`, snapshot had rows but none exact after filtering | Writes a `FAILED` decision with reason `NO_EXACT_COMPARABLES`. |
| `1` | Prices below the only comparable: `min(price - min(price * 0.03, 10), max(price - 1, 1))`. |
| `2` | Prices 75% from low to high: `low + (high - low) * 0.75`. |
| `>2`, Used | If highest/second-highest is greater than `3`, writes `FAILED` with `OUTLIER_SPREAD_TOO_HIGH`; otherwise uses `mean + sampleStandardDeviation`. |
| `>2`, New, US comparables exist | Uses the lowest US comparable, then applies the one-comparable discount. |
| `>2`, New, no US comparables | Uses `mean + sampleStandardDeviation`. |
| Box/instructions adjustment | Multiplies by the average of the legacy box-condition and instructions-condition adjustment tables. Missing condition ids or unknown codes default to `1.0`. |
| Rounding | `computed_price` and `final_price` are rounded to two decimals. |

Decision statuses:

| Status | Meaning |
| --- | --- |
| `PROPOSED` | Competitive price was computed and persisted, but not applied. |
| `SKIPPED` | Decision intentionally did not compute a replacement price, currently only fixed-price override. |
| `FAILED` | Required data was missing or the algorithm could not safely compute a price. |

Reason codes:

| Reason | Meaning |
| --- | --- |
| `FIXED_PRICE_OVERRIDE` | Listing has fixed price enabled; current price remains authoritative. |
| `MATCHED_LOWEST_COMPETITOR` | New item used the lowest US comparable and one-comparable discount path. |
| `SINGLE_COMPARABLE_DISCOUNTED` | One exact comparable was available after own-listing exclusion. |
| `TWO_COMPARABLES_WEIGHTED` | Two exact comparables were available. |
| `MEAN_PLUS_STDDEV` | More than two comparables used mean plus sample standard deviation. |
| `NO_CURRENT_SNAPSHOT` | No latest pricing snapshot exists for the listing condition/completeness. Run the crawl first. |
| `NO_CURRENT_COMPARABLES` | A latest matching snapshot exists, but BrickLink returned no comparable listings for that crawl. |
| `NO_EXACT_COMPARABLES` | Snapshot exists, but no exact comparable remains after filtering and own-listing exclusion. |
| `MISSING_INVENTORY` | Marketplace listing points to missing `item_inventory`. |
| `MISSING_CONDITION` | Inventory condition is missing or not recognized. |
| `MISSING_COMPLETENESS` | Inventory completeness is missing. |
| `OUTLIER_SPREAD_TOO_HIGH` | Highest comparable is more than 3x the second-highest comparable. |
| `BELOW_MIN_PRICE_CLAMPED` | Computed price was below configured `minimum-price`; final price was clamped. |
| `ABOVE_MAX_PRICE_CLAMPED` | Computed price was above configured `maximum-price`; final price was clamped. |

Important logs:

| Event | Meaning |
| --- | --- |
| `bricklink.pricing.decision.job.completed` | Scheduled run completed and logs `BricklinkPricingDecisionResult` with selected, written, proposed, skipped, failed, and elapsed counters. |

### `BricklinkPricingApplyReadinessJob`

Class: `io.legohunter.ingress.source.bricklink.pricing.BricklinkPricingApplyReadinessJob`

Condition:

```yaml
lego.bricklink.pricing.apply-readiness.enabled: true
lego.bricklink.pricing.apply-readiness.scheduled.enabled: true
```

`lego.bricklink.pricing.apply-readiness.enabled=true` creates the dry-run readiness service. The scheduled job bean is created only when both `enabled` and `scheduled.enabled` are true.

This job is intentionally read-only. It does not update `marketplace_listing.unit_price`, does not mark `pricing_decision.applied_at`, and does not trigger marketplace sync. It writes one durable `pricing_apply_readiness` row per evaluated `pricing_decision`, keyed by decision, so the latest apply state can be reviewed without parsing logs. A readiness row is current only when it belongs to the latest `pricing_decision` for that marketplace listing; older readiness rows remain audit history but are excluded from current gauges, apply previews, and dry-run apply selection.

An unpriced, non-fixed local `DRAFT` is evaluated through the initial-price path. It may become `READY_TO_APPLY_INITIAL_PRICE` when the decision has a positive final price, matching currency, an eligible reason, sufficient confidence, and sufficient exact comparables. Because no current price exists, the persisted readiness row intentionally has `current_price`, `delta_amount`, `delta_percent`, and `minimum_required_delta` set to null. Current-price movement guards apply only to active or already-priced listings.

Schedule and selection settings:

| Setting | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.bricklink.pricing.apply-readiness.scheduled.fixed-delay-ms` | `300000` | Long milliseconds, >= 0 | Delay between dry-run readiness scans. |
| `lego.bricklink.pricing.apply-readiness.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | Delay after startup before first run. |
| `lego.bricklink.pricing.apply-readiness.scheduled.lock-at-most-for` | `10m` | ShedLock duration | Maximum distributed lock duration. |
| `lego.bricklink.pricing.apply-readiness.scheduled.lock-at-least-for` | `0s` | ShedLock duration | Minimum distributed lock duration. |
| `lego.bricklink.pricing.apply-readiness.batch-size` | `25` | Integer; effective value is at least `1` | Maximum latest proposed decisions reviewed per run. |
| `lego.bricklink.pricing.apply-readiness.priceable-listing-status-codes` | `ACTIVE,DRAFT` | Set of listing status codes | Listing statuses eligible for readiness evaluation. Include `DRAFT` to support initial pricing before BrickLink publication; blank/empty falls back to `active-listing-status-code`. |
| `lego.bricklink.pricing.apply-readiness.minimum-price-delta` | `0.01` | Decimal money amount, zero or positive | Minimum absolute difference between current listing price and proposed final price required to count as ready. |
| `lego.bricklink.pricing.apply-readiness.minimum-delta.enabled` | `false` | `true`, `false` | Enables the configurable percentage-based minimum movement guard. |
| `lego.bricklink.pricing.apply-readiness.minimum-delta.percent` | `0.02` | Decimal ratio, zero or positive | Minimum relative movement required when enabled. `0.02` means 2%. The required money delta is computed with `BigDecimal` and rounded up to the penny. |
| `lego.bricklink.pricing.apply-readiness.minimum-confidence` | `0.00` | Decimal, zero or positive | Minimum decision confidence required before a proposed decision counts as ready. |
| `lego.bricklink.pricing.apply-readiness.minimum-comparable-count` | `1` | Integer, effective value at least `0` | Minimum exact comparable count required before a proposed decision counts as ready. |
| `lego.bricklink.pricing.apply-readiness.maximum-absolute-delta` | null | Decimal money amount or null | Optional maximum absolute price movement allowed by the dry-run readiness scan. Null disables the guard. |
| `lego.bricklink.pricing.apply-readiness.maximum-percent-delta` | null | Decimal ratio or null | Optional maximum relative price movement allowed by the dry-run readiness scan. `0.50` means 50%. Null disables the guard. |
| `lego.bricklink.pricing.apply-readiness.blocked-reason-codes` | empty | Set of reason code strings | Proposed decisions with these reason codes are never counted as ready, even if they also appear in `apply-eligible-reason-codes`. |
| `lego.bricklink.pricing.apply-readiness.apply-eligible-reason-codes` | successful algorithm reason codes | Set of reason code strings | Only proposed decisions with these reason codes are counted as ready. |

Candidate selection:

| Requirement | Detail |
| --- | --- |
| Marketplace listing service | `marketplace_listing.listing_external_service_id` must equal `lego.bricklink.pricing.apply-readiness.bricklink-external-service-id`. |
| Marketplace listing status | `marketplace_listing.listing_status_code` must be in `lego.bricklink.pricing.apply-readiness.priceable-listing-status-codes`, normally `ACTIVE,DRAFT`. |
| Latest decision only | The query selects only the newest `pricing_decision` per `marketplace_listing_id`, using `pricing_decision_id` as the tiebreaker. |
| Proposed only | The latest decision must match `lego.bricklink.pricing.apply-readiness.proposed-decision-status-code`, normally `PROPOSED`. |
| Unapplied only | `pricing_decision.applied_at` must be null. Phase 6 never changes it, but the filter protects future phases. |
| Batch limit | The selection query orders by latest decision recency and applies `lego.bricklink.pricing.apply-readiness.batch-size`. |

Readiness behavior:

| Outcome bucket | Meaning |
| --- | --- |
| `readyToApply` | Latest proposed decision is non-fixed, has a positive final price, has matching currency, uses an eligible non-blocked reason code, meets the confidence/comparable thresholds, and either follows the normal current-price movement guards or is an eligible initial-price DRAFT. |
| `READY_TO_APPLY_INITIAL_PRICE` | An unpriced non-fixed local DRAFT has a positive algorithmic initial price. Delta and movement-limit checks are intentionally not evaluated because there is no current-price baseline. |
| `skippedFixedPrice` | Listing is currently marked fixed price, so the proposed decision is not considered apply-ready. |
| `skippedMissingCurrentPrice` | Current listing price is missing on a listing that is not an eligible unpriced local DRAFT. |
| `skippedMissingFinalPrice` | Proposed final price is missing. |
| `skippedCurrencyMismatch` | Current listing currency and decision currency differ. |
| `skippedUnsupportedDecisionStatus` | Defensive bucket for a selected decision whose status no longer matches the configured proposed status. |
| `skippedBlockedReasonCode` | Proposed decision reason code appears in `blocked-reason-codes`. |
| `skippedIneligibleReason` | Proposed decision reason code is not in `apply-eligible-reason-codes`. |
| `skippedBelowMinimumDelta` | Proposed price equals current price or differs by less than `minimum-price-delta`. |
| `skippedBelowMinimumDeltaPercent` | Proposed price movement is less than the configured percentage threshold. Persisted rows use readiness status `BLOCKED_BELOW_MINIMUM_DELTA_PERCENT` and block reason `BELOW_MINIMUM_DELTA_PERCENT`. |
| `skippedBelowMinimumConfidence` | Decision confidence is lower than `minimum-confidence`. |
| `skippedBelowMinimumComparableCount` | Decision comparable count is lower than `minimum-comparable-count`. |
| `skippedAboveMaximumAbsoluteDelta` | Absolute proposed price movement is greater than `maximum-absolute-delta`. |
| `skippedAboveMaximumPercentDelta` | Relative proposed price movement is greater than `maximum-percent-delta`. |
| `skippedStaleDecision` | A newer `pricing_snapshot` exists for the listing than the snapshot used by the latest proposed decision. Persisted rows use readiness status `BLOCKED_STALE_DECISION` and block reason `STALE_DECISION`. |

Important logs:

| Event | Meaning |
| --- | --- |
| `bricklink.pricing.apply_readiness.ready` | DEBUG-level detail for one latest proposed decision that would change a listing if a later apply phase existed. Logs listing id, decision id, readiness status, initial-price flag, current price, proposed price, delta, currency, reason, and algorithm version. Initial-price rows explicitly show a null current-price baseline. |
| `bricklink.pricing.apply_readiness.job.completed` | Scheduled run completed and logs `BricklinkPricingApplyReadinessResult` with selected, ready, skipped, and elapsed counters. |

### BrickLink Pricing Maintenance Report

Class: `io.legohunter.ingress.source.bricklink.pricing.BricklinkPricingMaintenanceReportController`

Endpoint:

```text
GET /internal/bricklink/pricing/maintenance-report?limit=100
```

This is a dry-run diagnostic report. It does not delete, requeue, archive, or otherwise mutate Pricing Plane data.

### BrickLink Pricing Apply Preview Reports

Class: `io.legohunter.ingress.source.bricklink.pricing.BricklinkPricingMaintenanceReportController`

Endpoints:

```text
GET /internal/bricklink/pricing/apply-preview?readinessStatusCode=READY_TO_APPLY&limit=100
GET /internal/bricklink/pricing/apply-preview?blockReasonCode=BELOW_MINIMUM_DELTA_PERCENT&limit=100
GET /internal/bricklink/pricing/apply-selection/dry-run?limit=100
```

These reports read `pricing_apply_readiness` and select readiness rows attached to the latest unapplied `pricing_decision` per marketplace listing. They do not update prices, mark decisions applied, or enqueue marketplace sync. `apply-preview` supports optional `readinessStatusCode` and `blockReasonCode` filters. `apply-selection/dry-run` returns the current unapplied `READY_TO_APPLY` and `READY_TO_APPLY_INITIAL_PRICE` rows that a future apply job would consume. If a newer failed, skipped, or already-applied pricing decision exists after an older ready row, that older readiness row is no longer current and will not appear eligible.

The requested `limit` is bounded to the range `1..500`.

`apply-preview` returns a `summary` block with returned row count, ready count, blocked count, readiness-status counts, and block-reason counts. Row details remain in `readinessReviews` and include current price, proposed price, absolute delta, percent delta, minimum required delta, confidence, comparable count, decision reason, algorithm version, and snapshot timing.

### `BricklinkPricingApplyJob`

Class: `io.legohunter.ingress.source.bricklink.pricing.BricklinkPricingApplyJob`

Condition:

```yaml
lego.bricklink.pricing.apply.enabled: true
lego.bricklink.pricing.apply.scheduled.enabled: true
```

The apply job is the first Pricing Plane job that can mutate the local marketplace listing gold copy. It consumes only current unapplied `READY_TO_APPLY` and `READY_TO_APPLY_INITIAL_PRICE` rows selected from `pricing_apply_readiness`, re-checks the current `marketplace_listing` and `pricing_decision`, and then applies according to `lego.bricklink.pricing.apply.mode`. An initial-price row must still point to an unpriced local DRAFT with no external listing ID; if the draft was manually priced or published after readiness evaluation, the row is rejected as stale rather than overwriting the newer state. If an already-applied decision is encountered during rollout or after stale data is produced, the row is skipped and does not count as a failed apply row.

Modes:

| Mode | Behavior |
| --- | --- |
| `DRY_RUN` | Logs the decisions that would be applied. Does not update `marketplace_listing`, does not mark `pricing_decision.applied_at`, and does not enqueue sync requests. |
| `APPLY_LOCAL_ONLY` | Updates `marketplace_listing.unit_price` and marks `pricing_decision.applied_at`. Does not enqueue remote marketplace sync. |
| `APPLY_LOCAL_AND_ENQUEUE_SYNC` | Updates the local listing price and marks the pricing decision applied. For listings with an existing BrickLink inventory id, it upserts a `PRICE_UPDATE` sync request. For priced local `DRAFT` listings without a BrickLink inventory id, it upserts a `LISTING_CREATE` sync request so the marketplace sync worker can create the stockroom/public listing according to environment guardrails. Initial-price requests use `previous_unit_price=NULL`, a positive `requested_unit_price`, and sync reason `INITIAL_PRICE_APPLIED`. |

Settings:

| Setting | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.bricklink.pricing.apply.enabled` | `false` | `true`, `false` | Creates the pricing apply service. |
| `lego.bricklink.pricing.apply.mode` | `DRY_RUN` | `DRY_RUN`, `APPLY_LOCAL_ONLY`, `APPLY_LOCAL_AND_ENQUEUE_SYNC` | Controls whether the job only reports, mutates local prices, or also queues remote sync. |
| `lego.bricklink.pricing.apply.batch-size` | `25` | Integer; effective value at least `1` | Maximum latest ready decisions processed per run. |
| `lego.bricklink.pricing.apply.bricklink-external-service-id` | `2` | Integer external service id | Marketplace id used on enqueued BrickLink sync requests. |
| `lego.bricklink.pricing.apply.sync-request-max-attempts` | `3` | Integer; effective value at least `1` | Max attempts copied to new sync requests. |
| `lego.bricklink.pricing.apply.environment-code` | `local` | Short environment code | Stored on sync requests for downstream safety checks. |
| `lego.bricklink.pricing.apply.scheduled.enabled` | `false` | `true`, `false` | Creates the scheduled job only when `enabled=true`. |
| `lego.bricklink.pricing.apply.scheduled.fixed-delay-ms` | `300000` | Long milliseconds, >= 0 | Delay between apply scans. |
| `lego.bricklink.pricing.apply.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | Startup delay before first apply scan. |
| `lego.bricklink.pricing.apply.scheduled.lock-at-most-for` | `10m` | ShedLock duration string | Maximum distributed lock time. |
| `lego.bricklink.pricing.apply.scheduled.lock-at-least-for` | `0s` | ShedLock duration string | Minimum distributed lock time. |

Important logs:

| Event | Meaning |
| --- | --- |
| `bricklink.pricing.apply.dry_run` | A ready decision would have changed the local price, but apply mode is `DRY_RUN`. |
| `bricklink.pricing.apply.local_updated` | Local `marketplace_listing.unit_price` was updated and the pricing decision was marked applied. |
| `bricklink.pricing.apply.sync_enqueued` | A durable `PRICE_UPDATE` `marketplace_listing_sync_request` row was inserted or refreshed for an existing BrickLink inventory row. |
| `bricklink.pricing.apply.listing_create_sync_enqueued` | A durable `LISTING_CREATE` `marketplace_listing_sync_request` row was inserted or refreshed for a priced local `DRAFT` listing with no BrickLink inventory id. |
| `bricklink.pricing.apply.sync_skipped` | Local price was applied but remote sync enqueue was skipped, typically because the listing has no BrickLink inventory id and is not a local `DRAFT`. |
| `bricklink.pricing.apply.skipped` | A selected row was intentionally skipped, such as an already-current price or a stale readiness row for a pricing decision that was already applied. |
| `bricklink.pricing.apply.failed` | One selected readiness row failed defensive re-checks or sync-request preconditions. |
| `bricklink.pricing.apply.job.completed` | Scheduled run completed and logs `BricklinkPricingApplyResult`. |

### `BricklinkMarketplaceSyncJob`

Class: `io.legohunter.ingress.source.bricklink.pricing.BricklinkMarketplaceSyncJob`

Condition:

```yaml
lego.bricklink.marketplace-sync.enabled: true
lego.bricklink.marketplace-sync.scheduled.enabled: true
```

The marketplace sync job consumes due `marketplace_listing_sync_request` rows for BrickLink `PRICE_UPDATE` and `LISTING_CREATE` work. This is the first LegoHunter path that can mutate the live BrickLink account from local system-of-record data, so every write path is guarded by final safety checks in the worker.

`PRICE_UPDATE` verifies the existing remote BrickLink inventory before mutating price or remarks. `LISTING_CREATE` verifies local create eligibility, builds a BrickLink `Inventory` create payload, calls BrickLink `createInventory`, fetches the created remote inventory back, verifies the remote safety contract, and only then marks the local `marketplace_listing` `ACTIVE`.

Local profile is always dry-run for marketplace sync. `BricklinkMarketplaceSyncProperties.effectiveMode()` returns `DRY_RUN` when `environment-code=local` even if the configured mode says `APPLY`.

Modes:

| Mode | Behavior |
| --- | --- |
| `DRY_RUN` | Selects due pending sync requests. For `PRICE_UPDATE`, fetches/verifies remote inventory. For `LISTING_CREATE`, builds and logs the create payload. Does not claim request rows and does not call BrickLink mutation APIs. |
| `APPLY` | Claims due pending rows. For `PRICE_UPDATE`, verifies remote inventory, calls BrickLink `updateInventory`, records local safety metadata, and completes or blocks/retries the sync request. For `LISTING_CREATE`, verifies local create safety, calls BrickLink `createInventory`, verifies the created remote inventory, records local safety metadata, and completes or blocks/retries the sync request. |

Environment behavior:

| Runtime | Behavior |
| --- | --- |
| `local` | Dry-run only. No BrickLink mutation API is called even if `mode=APPLY` is configured. |
| `sandbox` | Applies to the live BrickLink account when configured, but `production=false` forces non-prod stockroom-only visibility and non-prod system remarks. |
| `dev` | Applies to the live BrickLink account when configured, but `production=false` forces non-prod stockroom-only visibility and non-prod system remarks. |
| `prod` | May create/update buyer-visible BrickLink inventory only when `production=true` and the sync request/listing request public visibility. Public production listings are expected to have `is_stock_room=false` and may have no `stock_room_id`. |

Non-prod safety contract:

| Requirement | Meaning |
| --- | --- |
| Production flag | When `lego.bricklink.marketplace-sync.production=false` or the property is omitted, the worker treats the runtime as non-production and applies the non-prod safety requirements. |
| Stockroom-only | In non-production, remote BrickLink inventory must have `is_stock_room=true`. `LISTING_CREATE` also forces the create payload to stockroom-only regardless of request visibility. |
| Expected stockroom | `stock_room_id` must match `lego.bricklink.marketplace-sync.non-prod-stock-room-id`, default `A`. |
| System remarks block | Remote `remarks` must contain exactly one valid `[SYSTEM_BEGIN] ... [SYSTEM_END]` block when `require-system-remarks-block=true`. For `LISTING_CREATE`, the worker generates and merges this block into the create payload before calling BrickLink. |
| Managed marker | `LEGOHUNTER_MANAGED=true` must be present in the system block. |
| Environment match | `LEGOHUNTER_ENV` must match `lego.bricklink.marketplace-sync.environment-code`. |
| Listing match | `MARKETPLACE_LISTING_ID` must match the local `marketplace_listing.marketplace_listing_id`. |
| Inventory match | `ITEM_INVENTORY_UUID` must match the local `item_inventory.uuid`. |
| Remarks length | Generated remarks, including preserved human remarks and the system block, must be at most `lego.bricklink.marketplace-sync.remarks-max-length`, default `1024`. |

### BrickLink Listing Create Color Checks

BrickLink requires `color_id=0` (Not Applicable) when creating SET inventory. The create preflight resolves a null
SET color to `0`, rejects a nonzero SET color, and blocks color-specific item types when their color is missing or
not positive. Blocked requests do not call the BrickLink mutation API.

Before retrying a SET create that previously failed with `PARAMETER_MISSING_OR_INVALID`, confirm the database
repair has run:

```sql
select ml.marketplace_listing_id,
       eci.external_item_key,
       eci.item_type_code,
       bl.color_id
from marketplace_listing ml
join bricklink_marketplace_listing bl
  on bl.marketplace_listing_id = ml.marketplace_listing_id
join external_catalog_item eci
  on eci.external_catalog_item_id = ml.external_catalog_item_id
where upper(eci.item_type_code) in ('S', 'SET')
  and bl.color_id is null;
```

The result must be empty. Deploying the repair does not reset terminal sync requests. Review the failed request,
then explicitly create or reset one `LISTING_CREATE` request through the normal operator workflow. Start in
`DRY_RUN` and verify the mapped payload includes `"color_id":0` before enabling `APPLY`.

System block format:

```text
[SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true; LEGOHUNTER_ENV=sandbox; MARKETPLACE_LISTING_ID=123; ITEM_INVENTORY_UUID=e1dcb9cd5838e81dbb55f28f74ab8069 [SYSTEM_END]
```

Human remarks outside the system block are preserved. The sync worker parses only the text between the markers and ignores unknown keys. Missing, malformed, duplicated, or mismatched system blocks block non-prod remote writes.

`bricklink_marketplace_listing.stock_room_id` is nullable in the current schema. Sandbox and dev require the configured stockroom through application guardrails, while production public inventory must be able to omit a stockroom id so BrickLink can make the listing buyer-visible.

Settings:

| Setting | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.bricklink.marketplace-sync.enabled` | `false` | `true`, `false` | Creates the BrickLink marketplace sync service and imports the BrickLink REST client. |
| `lego.bricklink.marketplace-sync.mode` | `DRY_RUN` | `DRY_RUN`, `APPLY` | Controls whether remote inventory is only verified or actually updated/created. The effective mode is forced to `DRY_RUN` when `environment-code=local`. |
| `lego.bricklink.marketplace-sync.batch-size` | `5` | Integer; effective value at least `1` | Maximum due sync requests processed per run. |
| `lego.bricklink.marketplace-sync.retry-backoff` | `6h` | Spring `Duration` | Delay before retrying transient sync failures. |
| `lego.bricklink.marketplace-sync.environment-code` | `local` | Short environment code | Runtime environment used for safety checks and remarks block generation. |
| `lego.bricklink.marketplace-sync.production` | `false` | `true`, `false` | Explicitly marks the runtime as production. Omitted or `false` means non-production safety checks apply, regardless of the environment name. |
| `lego.bricklink.marketplace-sync.require-system-remarks-block` | `true` | `true`, `false` | Requires matching system remarks ownership data before remote writes. |
| `lego.bricklink.marketplace-sync.non-prod-require-stock-room` | `true` | `true`, `false` | Requires stockroom-only inventory in non-prod environments. |
| `lego.bricklink.marketplace-sync.non-prod-stock-room-id` | `A` | BrickLink stockroom id | Expected non-prod stockroom. |
| `lego.bricklink.marketplace-sync.remarks-max-length` | `1024` | Integer; effective value at least `1` | Local maximum generated BrickLink remarks length. |
| `lego.bricklink.marketplace-sync.scheduled.enabled` | `false` | `true`, `false` | Creates the scheduled sync worker only when `enabled=true`. |
| `lego.bricklink.marketplace-sync.scheduled.fixed-delay-ms` | `300000` | Long milliseconds, >= 0 | Delay between sync scans. |
| `lego.bricklink.marketplace-sync.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | Startup delay before first sync scan. |
| `lego.bricklink.marketplace-sync.scheduled.lock-at-most-for` | `10m` | ShedLock duration string | Maximum distributed lock time. |
| `lego.bricklink.marketplace-sync.scheduled.lock-at-least-for` | `0s` | ShedLock duration string | Minimum distributed lock time. |

Important logs:

| Event | Meaning |
| --- | --- |
| `bricklink.marketplace_sync.dry_run_verified` | A due `PRICE_UPDATE` request passed remote safety verification, but sync mode is `DRY_RUN`. |
| `bricklink.marketplace_sync.listing_create_dry_run` | A due `LISTING_CREATE` request passed local create safety and mapped to a BrickLink create payload, but sync mode is `DRY_RUN`. |
| `bricklink.marketplace_sync.listing_created` | BrickLink `createInventory` succeeded, remote safety verification passed, and the local listing was marked `ACTIVE`. |
| `bricklink.marketplace_sync.listing_create_blocked` | A `LISTING_CREATE` request failed final local create safety checks. |
| `bricklink.marketplace_sync.listing_create_remote_verification_blocked` | BrickLink inventory was created, but the read-back remote safety check failed. The local BrickLink inventory id is retained for investigation and the request is blocked. |
| `bricklink.marketplace_sync.remote_updated` | BrickLink `updateInventory` was called for a price update. |
| `bricklink.marketplace_sync.blocked` | A request failed safety checks and was marked blocked in apply mode. |
| `bricklink.marketplace_sync.failed` | A transient or unexpected failure occurred. In apply mode, the request retries with backoff until max attempts is reached. |
| `bricklink.marketplace_sync.job.completed` | Scheduled run completed and logs `BricklinkMarketplaceSyncResult`. |

Final-phase smoke queries:

```sql
select sync_request_status_code, count(*) as request_count
from marketplace_listing_sync_request
group by sync_request_status_code
order by sync_request_status_code;

select marketplace_listing_sync_request_id,
       marketplace_listing_id,
       pricing_decision_id,
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
order by marketplace_listing_sync_request_id desc
limit 50;

select marketplace_listing_id,
       bricklink_inventory_id,
       is_stock_room,
       stock_room_id,
       environment_code,
       last_remote_verified_at,
       last_remote_safety_status_code,
       last_remote_safety_message
from bricklink_marketplace_listing
where last_remote_verified_at is not null
order by last_remote_verified_at desc
limit 50;
```

Report sections:

| Section | Meaning |
| --- | --- |
| `workItemSummary` | Aggregate crawl queue health, including pending, retryable, due, claimed, stale claimed, succeeded, skipped, failed, and duplicate counts. |
| `duplicateWorkItems` | Marketplace listings with more than one active crawl work item. Only `PENDING` and `CLAIMED` rows are included so historical successful/skipped/failed crawl rows do not look like active queue defects. |
| `recentFailures` | Recent crawl work items with `last_error_message` populated or a `FAILED%` status. Includes listing, catalog, inventory condition/completeness, request metadata, attempt counts, and the stored error message. |
| `hydrationGaps` | Active BrickLink marketplace listings whose catalog item still lacks BrickLink's internal `idItem` in `external_catalog_item.external_unique_key`. |

Use this endpoint before considering any manual maintenance. If the report finds stale or duplicate rows, inspect the matching SQL checks first and prefer code-level idempotency fixes over ad hoc deletes.

### `ImageHostingScheduledSyncJob`

Class: `io.legohunter.egress.imagehosting.ImageHostingScheduledSyncJob`

Condition:

```yaml
lego.image-hosting.sync.scheduled.enabled: true
```

Schedule and selection settings:

| Setting | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.image-hosting.sync.scheduled.fixed-delay-ms` | `300000` | Long milliseconds, >= 0 | Delay between job runs. |
| `lego.image-hosting.sync.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | Delay after startup before first run. |
| `lego.image-hosting.sync.scheduled.lock-at-most-for` | `10m` | ShedLock duration | Maximum distributed lock duration. |
| `lego.image-hosting.sync.scheduled.lock-at-least-for` | `0s` | ShedLock duration | Minimum distributed lock duration. |
| `lego.image-hosting.sync.scheduled.batch-size` | `25` | Integer; effective value is at least `1` | Maximum candidate item inventories selected per run. |
| `lego.image-hosting.sync.scheduled.concurrency` | `2` in code, `5` in base YAML, `2` in Kubernetes | Integer; effective value is at least `1` | Thread pool size for inventory sync work. |
| `lego.image-hosting.sync.scheduled.retry-failed` | `true` | `true`, `false` | Whether failed sync rows are eligible for selection. |
| `lego.image-hosting.sync.scheduled.apply` | `false` | `true`, `false` | If `false`, build/log plans only. If `true`, execute applicable plan actions. |

Candidate reasons logged by the job:

| Reason | Meaning |
| --- | --- |
| `missingAlbumLink` | Inventory item needs an external album link. |
| `missingPhotoLink` | One or more photos need external image links. |
| `failedSync` | Prior sync rows failed and retry is enabled. |
| `pendingSync` | Rows are pending sync. |
| `metadataChanged` | Local metadata differs from metadata last synced to the provider. |

Outcomes:

| Outcome | Meaning |
| --- | --- |
| `NO_WORK` | No candidate item inventories selected. |
| `SUCCESS` | Candidates were processed and none failed. |
| `PARTIAL_FAILURE` | At least one candidate succeeded and at least one failed. |
| `FAILED` | All selected candidates failed. |

Important logs:

| Event | Meaning |
| --- | --- |
| `image_hosting.sync_job.started` | Job started with provider, batch size, concurrency, retry, and apply mode. |
| `image_hosting.sync_job.candidates_selected` | Candidate count breakdown by reason. |
| `image_hosting.sync_job.no_work` | No candidates selected. |
| `image_hosting.sync_job.inventory_no_actions` | Plan had no actions for one inventory item. |
| `image_hosting.sync_job.inventory_planned` | Apply is false and actions were planned but not executed. |
| `image_hosting.sync_job.inventory_completed` | One inventory item completed in apply mode. |
| `image_hosting.sync_job.inventory_failed` | One inventory item failed; job continues. |
| `image_hosting.sync_job.completed` | Run-level result. |

### `FulfillmentScheduledSyncJob`

Class: `io.legohunter.egress.fulfillment.FulfillmentScheduledSyncJob`

Condition:

```yaml
lego.fulfillment.sync.scheduled.enabled: true
```

Schedule and selection settings:

| Setting | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.fulfillment.sync.scheduled.fixed-delay-ms` | `3600000` in code and base YAML; profile cadence above overrides it | Long milliseconds, >= 0 | Delay between job runs. |
| `lego.fulfillment.sync.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | Delay after startup before first run. |
| `lego.fulfillment.sync.scheduled.lock-at-most-for` | `10m` | ShedLock duration | Maximum distributed lock duration. |
| `lego.fulfillment.sync.scheduled.lock-at-least-for` | `0s` | ShedLock duration | Minimum distributed lock duration. |
| `lego.fulfillment.sync.scheduled.batch-size` | `25` | Integer; effective value is at least `1` | Maximum marketplace orders selected per run. |
| `lego.fulfillment.sync.scheduled.apply` | `false` | `true`, `false` | If `false`, maps staged orders only. If `true` **and** `lego.environment.production=true`, performs ShipStation and BrickLink mutations. |
| `lego.environment.production` | `false` | `true` only in `application-prod.yml` | Production gate for all real-order writes. When `false`, apply is suppressed even if requested. |

Candidate requirements:

| Requirement | Detail |
| --- | --- |
| Marketplace order status | `marketplace_order.external_status_code` must match `lego.fulfillment.statuses`. |
| Canonical invoice projection | Candidate selection starts from `transactions` and requires the matching order-level `marketplace_order_transaction_link` to be `INVOICED`, the staged `is_invoiced` flag to be true, and at least one staged order item. Open orders are not sent to ShipStation. |
| Raw payloads | Latest `ORDER_RESPONSE` and `ORDER_ITEMS_RESPONSE` payloads must exist in `marketplace_order_payload`. |
| Order item image URLs | Uses the configured image-hosting external service id. Primary photo wins; otherwise any available photo is used; if no photo exists, no image URL is assigned. |

Operational modes:

| `apply` | `lego.environment.production` | Behavior |
| --- | --- | --- |
| `false` | any | Dry run. Loads staged marketplace orders, maps ShipStation order payloads, logs counters, emits metrics, and makes no ShipStation or BrickLink client calls. Candidates are skipped as `SKIPPED_DRY_RUN`. |
| `true` | `false` | **Suppressed** dry run. Same as above, plus a `fulfillment.sync_job.apply_suppressed` warning each run; candidates are skipped as `SKIPPED_NON_PRODUCTION`. Lower environments must never write to real orders. |
| `true` | `true` | Live fulfillment sync (PROD only). Finds an existing ShipStation order by order number, creates or updates unshipped ShipStation orders, or reconciles shipped ShipStation orders back to BrickLink. |

Live fulfillment behavior when `apply=true`:

| ShipStation state | Behavior |
| --- | --- |
| No existing order for `BL-{orderId}` | Creates the ShipStation draft from the preserved BrickLink order/items payloads after the canonical invoice projection is confirmed. |
| Exactly one existing unshipped order | Updates that ShipStation draft by setting the existing ShipStation `orderId` on the mapped order. |
| Exactly one existing shipped order | Fetches ShipStation shipments, chooses the first non-voided shipment with both an ID and tracking, maps its carrier to an authoritative local carrier code, persists its ID/service/date/tracking and active canonical transaction-item links, then writes tracking/date shipped to BrickLink, marks the BrickLink order `SHIPPED`, sends Drive Thru when needed, and marks the local marketplace order as shipped. |
| More than one existing order | Fails that candidate and leaves other candidates running. |
| Shipped order without a non-voided tracked shipment ID | Fails that candidate because neither canonical shipment persistence nor BrickLink reconciliation can be performed safely. |

Important boundaries:

| Boundary | Detail |
| --- | --- |
| ShipStation linkage | Draft linkage is idempotent by `orderNumber`/`orderKey`, using the configured prefix plus BrickLink order id. A confirmed label is additionally persisted by ShipStation `shipmentId` in the canonical `shipment` table. |
| Canonical lifecycle gate | Candidate selection requires the Phase 2 transaction projection to be invoiced. The active canonical transaction-item links receive the confirmed shipment association. |
| Carrier authority | ShipStation carrier values resolve only to configured `carrier` table codes. The built-in aliases cover `stamps*`/`usps*` → `USPS`, `ups*` → `UPS`, `fedex*` → `FEDEX`, and `dhl*` → `DHL`; an unmapped value fails safely before local or BrickLink updates. |
| Source of order details | Fulfillment maps ShipStation's integration document from staged `marketplace_order_payload` JSON captured by the BrickLink order sync; the canonical transaction controls eligibility and shipment persistence. |
| Inventory state | BrickLink order sync marks linked active order items as `RESERVED_FOR_ORDER`. Fulfillment shipped reconciliation marks linked item inventory rows as `SOLD`. |
| Deferred accounting work | Local transaction finalization, payment/cost/shipping rows, and marketplace order transaction links are intentionally not performed by this job yet. |

Outcomes:

| Outcome | Meaning |
| --- | --- |
| `NO_WORK` | No marketplace order candidates selected. |
| `SUCCESS` | Candidates were processed and none failed. |
| `PAYLOADS_MISSING` | Candidates were selected, but none could be mapped because required raw payloads were absent. |
| `PARTIAL_FAILURE` | At least one mapped candidate succeeded and at least one failed. |
| `FAILED` | All mapped candidates failed. |

Important logs:

| Event | Meaning |
| --- | --- |
| `fulfillment.sync_job.started` | Job started with provider, marketplace code, statuses, batch size, effective `apply`, `applyRequested`, and `production`. |
| `fulfillment.sync_job.apply_suppressed` | WARN. Apply was requested in a non-production runtime and was forced to dry-run. Fix the profile; lower environments must not request fulfillment apply. |
| `fulfillment.sync_job.payload_missing` | A candidate lacks one or both required raw payloads. |
| `fulfillment.sync_job.order_mapped` | One candidate mapped successfully and reports action, ShipStation order id when available, and tracking presence. |
| `fulfillment.sync_job.order_failed` | One candidate failed; job continues. |
| `fulfillment.sync_job.completed` | Run-level result with discovered, loaded, mapped, created, updated, shipped-reconciled, skipped, and failed counters. |

### `TestCronJob`

Class: `io.legohunter.ingress.scheduling.job.TestCronJob`

This job is intentionally disabled in code:

```java
@Scheduled(cron = "-")
```

It has a ShedLock declaration but will not execute unless the cron expression is changed in code.

## Kafka And S3 Event Pipelines

These flows are event-driven rather than `@Scheduled`, but operationally they behave like asynchronous batch pipelines.

### Dynamic Kafka Bean Registration

`KafkaDynamicBeanRegistrar` reads `kafka.topic-configuration.*` and creates beans using these naming conventions:

| Topic config id | Consumer factory bean | Kafka template bean | Container factory bean |
| --- | --- | --- | --- |
| `upload-photo` | `uploadPhotoConsumerFactory` | `uploadPhotoKafkaTemplate` if producer configured | `uploadPhotoContainerFactory` |
| `bricklink-catalog-entry` | `bricklinkCatalogEntryConsumerFactory` | `bricklinkCatalogEntryKafkaTemplate` if producer configured | `bricklinkCatalogEntryContainerFactory` |

Rules:

| Configuration | Behavior |
| --- | --- |
| `kafka.topic-configuration.<id>.consumer` present | Registers a consumer factory and listener container factory. |
| `kafka.topic-configuration.<id>.producer` present | Registers a producer factory and Kafka template. |
| Hyphenated Kafka property keys | Converted to dotted Kafka client keys, for example `max-poll-records` becomes `max.poll.records`. |
| Sensitive properties | `sasl.jaas.config`, SSL passwords, and key passwords are masked in registrar logs. |

### MinIO S3 Event Router

Listener: `MinioS3EventListener`

Input topic:

```yaml
kafka.topic-configuration.minio-s3.topics
```

Group id:

```yaml
kafka.topic-configuration.minio-s3.consumer.group-id
```

Container factory: `minioS3ContainerFactory`

Routing rules:

| Event/key | Routed to topic setting | Notes |
| --- | --- | --- |
| `s3:ObjectCreated:*`, key starts `photos/` and not `photos/rejected/` or `photos/duplicate/` | `kafka.topic-configuration.upload-photo.topic` | Photo processing pipeline. |
| `s3:ObjectCreated:*`, key starts `bricklink/catalog/` | `kafka.topic-configuration.upload-bricklink-catalog.topic` | BrickLink catalog XML pipeline. |
| `s3:ObjectCreated:*`, key starts `bricklink/category/` | `kafka.topic-configuration.upload-bricklink-category.topic` | BrickLink category XML pipeline. |
| `s3:ObjectCreated:*`, key starts `rebrickable/catalog/` | `kafka.topic-configuration.upload-rebrickable-catalog.topic` | Rebrickable catalog CSV gzip pipeline. |
| `s3:ObjectCreated:*`, key starts `rebrickable/category/` | `kafka.topic-configuration.upload-rebrickable-theme.topic` | Rebrickable theme CSV gzip pipeline. |
| `s3:ObjectRemoved:*`, bucket `lego-photos-sandbox`, key shape `<dir>/<dir>/<32-hex-md5>.jpg` | `kafka.topic-configuration.delete-photo.topic` | Final photo deletion pipeline. |

Unsupported object events are logged as warnings and are not routed.

### Kafka Pipeline Summary

| Pipeline | Listener | Input | Output/write |
| --- | --- | --- | --- |
| Photo upload | `PhotoUploadS3EventListener` | `upload-photo.topic` | Calls `PhotoProcessingService.process(PhotoUploadEvent)`. |
| Photo delete | `PhotoDeletedS3EventListener` | `delete-photo.topic` | Calls `PhotoDeletionService.process(ObjectDeletedEvent)`. |
| BrickLink catalog file split | `BricklinkCatalogS3EventListener` | `upload-bricklink-catalog.topic` | Reads XML from MinIO and publishes one `CatalogEntry` per `ITEM` to `bricklink-catalog-entry.topic`. |
| BrickLink catalog DB upsert | `BricklinkCatalogEntryConsumer` | `bricklink-catalog-entry.topic` | Upserts `external_catalog_item` and category link when category exists. |
| BrickLink category file split | `BricklinkCategoryS3EventListener` | `upload-bricklink-category.topic` | Reads XML from MinIO and publishes one `CategoryEntry` per `ITEM` to `bricklink-category-entry.topic`. |
| BrickLink category DB upsert | `BricklinkCategoryEntryConsumer` | `bricklink-category-entry.topic` | Upserts `external_category`. |
| Rebrickable catalog file split | `RebrickableCatalogS3EventListener` | `upload-rebrickable-catalog.topic` | Reads gzipped CSV and publishes one `RebrickableCatalogEntry` per row to `rebrickable-catalog-entry.topic`. |
| Rebrickable catalog DB upsert | `RebrickableCatalogEntryConsumer` | `rebrickable-catalog-entry.topic` | Upserts `external_catalog_item` and category link when category exists. |
| Rebrickable theme file split | `RebrickableThemeS3EventListener` | `upload-rebrickable-theme.topic` | Reads gzipped CSV and publishes one `RebrickableThemeEntry` per row to `rebrickable-theme-entry.topic`. |
| Rebrickable theme DB upsert | `RebrickableThemeEntryConsumer` | `rebrickable-theme-entry.topic` | Upserts `external_category` and resolves parent category when present. |

Notes:

| Area | Detail |
| --- | --- |
| Photo upload concurrency | Configurable through `kafka.topic-configuration.upload-photo.consumer.concurrency`, default `1`. |
| Photo delete concurrency | Hard-coded to `6` in the listener annotation; not currently YAML-configurable. |
| BrickLink external service id | Catalog/category consumers currently hard-code `2`. |
| Rebrickable external service id | Catalog/theme consumers currently hard-code `9`. |
| Rebrickable catalog category lookup | The current catalog category lookup uses external service id `2`; review before relying on Rebrickable category links. |

Expected Rebrickable CSV headers:

| File type | Headers |
| --- | --- |
| Catalog | `SET_NUM`, `NAME`, `YEAR`, `THEME_ID`, `NUM_PARTS`, `IMG_URL` |
| Theme/category | `ID`, `NAME`, `PARENT_ID` |

## Configuration Reference

### `lego.bricklink.orders.sync.*`

Backed by `BricklinkOrderSyncProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.bricklink.orders.sync.marketplace-code` | `BRICKLINK` | Non-blank string; normalized to uppercase | Marketplace code written to marketplace sync tables. |
| `lego.bricklink.orders.sync.metrics-tag` | `bricklink` | Non-blank string | Low-cardinality provider tag for metrics. |
| `lego.bricklink.orders.sync.direction` | `in` | BrickLink API direction, normally `in` for seller/inbound orders or `out` for buyer/outbound orders | Direction sent to BrickLink order list calls and written to marketplace orders. |
| `lego.bricklink.orders.sync.statuses` | `PENDING`, `UPDATED`, `READY`, `PROCESSING`, `PAID`, `PACKED` | BrickLink API order status strings; code trims, uppercases, and de-duplicates | Statuses queried every run. |
| `lego.bricklink.orders.sync.include-unfiled-cancelled` | `true` | `true`, `false` | Also query unfiled cancelled orders using `filed=false&status=CANCELLED`. |
| `lego.bricklink.orders.sync.scheduled.enabled` | `false` in code and base YAML; environment profiles enable it | `true`, `false` | Creates scheduled job/service beans when true. |
| `lego.bricklink.orders.sync.scheduled.apply` | `false` | `true`, `false` | Enables database writes when true. Keep false for probe-only validation. |
| `lego.bricklink.orders.sync.scheduled.fixed-delay-ms` | `3600000` in code and base YAML; profile cadence above overrides it | Long milliseconds, >= 0 | Delay between runs. |
| `lego.bricklink.orders.sync.scheduled.initial-delay-ms` | `30000` in code and base YAML | Long milliseconds, >= 0 | First-run startup delay. |
| `lego.bricklink.orders.sync.scheduled.lock-at-most-for` | `10m` | ShedLock duration string | Maximum distributed lock time. |
| `lego.bricklink.orders.sync.scheduled.lock-at-least-for` | `0s` | ShedLock duration string | Minimum distributed lock time. |

Recommended safe defaults:

```yaml
lego:
  bricklink:
    orders:
      sync:
        marketplace-code: BRICKLINK
        metrics-tag: bricklink
        direction: in
        statuses: [PENDING, UPDATED, READY, PROCESSING, PAID, PACKED]
        include-unfiled-cancelled: true
        scheduled:
          enabled: true
          apply: false
```

### `lego.bricklink.pricing.crawl.*`

Backed by `BricklinkPricingCrawlProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.bricklink.pricing.crawl.enabled` | `false` | `true`, `false` | Creates the BrickLink pricing crawl service bean when true. |
| `lego.bricklink.pricing.crawl.bricklink-external-service-id` | `2` | Integer external service id | External service id for BrickLink rows in `external_service`, `marketplace_listing`, and `external_catalog_item`. |
| `lego.bricklink.pricing.crawl.active-listing-status-code` | `ACTIVE` | Non-blank listing status string; code trims and uppercases | Marketplace listing status selected for pricing crawl. |
| `lego.bricklink.pricing.crawl.catalog-item-type` | `S` | BrickLink catalog item type; code trims and uppercases | Fallback type sent to `searchproduct.ajax` only when `external_catalog_item.item_type_code` is blank. Normal hydration uses the catalog row type, for example `S` for sets, `G` for gear, or `B` for books. |
| `lego.bricklink.pricing.crawl.batch-size` | `25` | Integer; effective value at least `1` | Maximum eligible BrickLink marketplace listings considered for work scheduling per run. |
| `lego.bricklink.pricing.crawl.worker-batch-size` | `1` | Integer; effective value at least `1` | Maximum due work items claimed and processed per run. |
| `lego.bricklink.pricing.crawl.results-per-page` | `500` | Integer; effective value at least `1` | `rpp` sent to `catalogifs.ajax`. |
| `lego.bricklink.pricing.crawl.max-attempts` | `3` | Integer; effective value at least `1` | Stored on `pricing_crawl_work_item.max_attempts`; retryable failures requeue until attempts are exhausted. |
| `lego.bricklink.pricing.crawl.crawl-cadence` | `7d` | Spring `Duration`, positive | Cooling period before the same listing becomes eligible to schedule again. |
| `lego.bricklink.pricing.crawl.schedule-spread-window` | `72h` | Spring `Duration`, zero or positive | Window used to spread newly created work item due times. |
| `lego.bricklink.pricing.crawl.schedule-jitter` | `0s` | Spring `Duration`, zero or positive | Optional random positive jitter added to new work item due times. |
| `lego.bricklink.pricing.crawl.retry-backoff` | `6h` | Spring `Duration`, positive | Delay before a retryable failed work item becomes due again. |
| `lego.bricklink.pricing.crawl.claim-stale-after` | `2h` | Spring `Duration`, positive | Requeue age for abandoned `CLAIMED` work. |
| `lego.bricklink.pricing.crawl.marketplace-listing-allowlist` | empty | Set of integer ids | Optional sandbox guard limiting scheduling to specific `marketplace_listing_id` values. |
| `lego.bricklink.pricing.crawl.blackout.enabled` | `false` | `true`, `false` | Enables blackout adjustment for scheduled and retry due times. |
| `lego.bricklink.pricing.crawl.blackout.zone-id` | `America/New_York` | Java time zone id | Time zone used for blackout evaluation. |
| `lego.bricklink.pricing.crawl.blackout.start` | `21:30` | `HH:mm` local time | Blackout start time. |
| `lego.bricklink.pricing.crawl.blackout.end` | `08:30` | `HH:mm` local time | Blackout end time. |
| `lego.bricklink.pricing.crawl.blackout.weekdays-only` | `true` | `true`, `false` | Applies blackout adjustment only Monday through Friday when true. |
| `lego.bricklink.pricing.crawl.scheduled.enabled` | `false` | `true`, `false` | Creates the scheduled job bean only when `lego.bricklink.pricing.crawl.enabled=true` is also set. |
| `lego.bricklink.pricing.crawl.scheduled.fixed-delay-ms` | `300000` | Long milliseconds, >= 0 | Delay between scheduled crawl runs. |
| `lego.bricklink.pricing.crawl.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | First-run startup delay. |
| `lego.bricklink.pricing.crawl.scheduled.lock-at-most-for` | `30m` | ShedLock duration string | Maximum distributed lock time. |
| `lego.bricklink.pricing.crawl.scheduled.lock-at-least-for` | `0s` | ShedLock duration string | Minimum distributed lock time. |

Recommended safe defaults:

```yaml
lego:
  bricklink:
    pricing:
      crawl:
        enabled: true
        bricklink-external-service-id: 2
        active-listing-status-code: ACTIVE
        catalog-item-type: S
        batch-size: 25
        worker-batch-size: 1
        crawl-cadence: 7d
        schedule-spread-window: 72h
        schedule-jitter: 30s
        retry-backoff: 6h
        claim-stale-after: 2h
        results-per-page: 500
        max-attempts: 3
        blackout:
          enabled: true
          zone-id: America/New_York
          start: "21:30"
          end: "08:30"
          weekdays-only: true
        scheduled:
          enabled: false
```

Enable `scheduled.enabled` only for a controlled local/sandbox run. Keep `worker-batch-size` small; the scheduler can create many future-due rows while the worker processes only the rows that are currently due.

### `lego.bricklink.pricing.decision.*`

Backed by `BricklinkPricingDecisionProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.bricklink.pricing.decision.enabled` | `false` | `true`, `false` | Creates the BrickLink pricing decision service bean when true. |
| `lego.bricklink.pricing.decision.bricklink-external-service-id` | `2` | Integer external service id | External service id for BrickLink marketplace listings selected for pricing decisions. |
| `lego.bricklink.pricing.decision.active-listing-status-code` | `ACTIVE` | Non-blank listing status string; code trims and uppercases | Marketplace listing status selected for pricing decisions. |
| `lego.bricklink.pricing.decision.batch-size` | `25` | Integer; effective value at least `1` | Maximum pricing decision candidates selected per run after eligibility filtering. |
| `lego.bricklink.pricing.decision.require-current-snapshot` | `false` | `true`, `false` | When true, non-fixed candidates must already have a matching `pricing_snapshot`; this suppresses `NO_CURRENT_SNAPSHOT` review rows and keeps batches focused on calculable or fixed-price listings. |
| `lego.bricklink.pricing.decision.algorithm-version` | `bricklink-competitive-v1` | Non-blank string | Stored on `pricing_decision.algorithm_version`. |
| `lego.bricklink.pricing.decision.strategy-code` | `LEGACY_COMPETITIVE` | Non-blank string; code trims and uppercases | Stored on `pricing_decision.strategy_code`. |
| `lego.bricklink.pricing.decision.minimum-price` | null | Decimal money amount or null | Optional lower bound for computed decisions. Produces `BELOW_MIN_PRICE_CLAMPED` when applied. |
| `lego.bricklink.pricing.decision.maximum-price` | null | Decimal money amount or null | Optional upper bound for computed decisions. Produces `ABOVE_MAX_PRICE_CLAMPED` when applied. |
| `lego.bricklink.pricing.decision.scheduled.enabled` | `false` | `true`, `false` | Creates the scheduled job bean only when `lego.bricklink.pricing.decision.enabled=true` is also set. |
| `lego.bricklink.pricing.decision.scheduled.fixed-delay-ms` | `300000` | Long milliseconds, >= 0 | Delay between scheduled decision runs. |
| `lego.bricklink.pricing.decision.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | First-run startup delay. |
| `lego.bricklink.pricing.decision.scheduled.lock-at-most-for` | `10m` | ShedLock duration string | Maximum distributed lock time. |
| `lego.bricklink.pricing.decision.scheduled.lock-at-least-for` | `0s` | ShedLock duration string | Minimum distributed lock time. |

Recommended safe defaults:

```yaml
lego:
  bricklink:
    pricing:
      decision:
        enabled: true
        bricklink-external-service-id: 2
        active-listing-status-code: ACTIVE
        batch-size: 25
        require-current-snapshot: false
        algorithm-version: bricklink-competitive-v1
        strategy-code: LEGACY_COMPETITIVE
        scheduled:
          enabled: false
```

Run pricing decisions after a successful crawl has populated `pricing_snapshot` and `pricing_snapshot_listing`. The decision job selects fixed-price overrides and non-fixed listings with populated condition/completeness data; legacy listings missing those fields do not consume the configured decision batch. Phase 3 decisions are non-applying; review `pricing_decision` rows before any future apply/sync phase.

### `lego.bricklink.pricing.apply-readiness.*`

Backed by `BricklinkPricingApplyReadinessProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.bricklink.pricing.apply-readiness.enabled` | `false` | `true`, `false` | Creates the BrickLink pricing apply-readiness dry-run service bean when true. |
| `lego.bricklink.pricing.apply-readiness.bricklink-external-service-id` | `2` | Integer external service id | External service id for BrickLink marketplace listings selected for readiness review. |
| `lego.bricklink.pricing.apply-readiness.active-listing-status-code` | `ACTIVE` | Non-blank listing status string; code trims and uppercases | Marketplace listing status selected for readiness review. |
| `lego.bricklink.pricing.apply-readiness.proposed-decision-status-code` | `PROPOSED` | Non-blank decision status string; code trims and uppercases | Latest decision status eligible for dry-run apply review. |
| `lego.bricklink.pricing.apply-readiness.batch-size` | `25` | Integer; effective value at least `1` | Maximum latest proposed decisions reviewed per run. |
| `lego.bricklink.pricing.apply-readiness.minimum-price-delta` | `0.01` | Decimal money amount, zero or positive | Minimum absolute price delta required before a proposed decision is counted as ready. |
| `lego.bricklink.pricing.apply-readiness.minimum-delta.enabled` | `false` | `true`, `false` | Enables percentage-based minimum movement gating. |
| `lego.bricklink.pricing.apply-readiness.minimum-delta.percent` | `0.02` | Decimal ratio, zero or positive | Minimum relative movement required when percentage gating is enabled. The required money delta is rounded up to the penny. |
| `lego.bricklink.pricing.apply-readiness.minimum-confidence` | `0.00` | Decimal, zero or positive | Minimum pricing decision confidence required before a proposed decision is counted as ready. |
| `lego.bricklink.pricing.apply-readiness.minimum-comparable-count` | `1` | Integer; effective value at least `0` | Minimum exact comparable count required before a proposed decision is counted as ready. |
| `lego.bricklink.pricing.apply-readiness.maximum-absolute-delta` | null | Decimal money amount or null | Optional maximum absolute price movement allowed by readiness review. Null disables this guard. |
| `lego.bricklink.pricing.apply-readiness.maximum-percent-delta` | null | Decimal ratio or null | Optional maximum relative price movement allowed by readiness review. `0.50` means 50 percent. Null disables this guard. |
| `lego.bricklink.pricing.apply-readiness.blocked-reason-codes` | empty | Set of reason code strings | Reason codes that are never counted as ready, even if also present in `apply-eligible-reason-codes`. |
| `lego.bricklink.pricing.apply-readiness.apply-eligible-reason-codes` | successful algorithm reason codes | Set of reason code strings | Proposed decisions outside this set are skipped as ineligible. |
| `lego.bricklink.pricing.apply-readiness.scheduled.enabled` | `false` | `true`, `false` | Creates the scheduled job bean only when `lego.bricklink.pricing.apply-readiness.enabled=true` is also set. |
| `lego.bricklink.pricing.apply-readiness.scheduled.fixed-delay-ms` | `300000` | Long milliseconds, >= 0 | Delay between scheduled readiness scans. |
| `lego.bricklink.pricing.apply-readiness.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | First-run startup delay. |
| `lego.bricklink.pricing.apply-readiness.scheduled.lock-at-most-for` | `10m` | ShedLock duration string | Maximum distributed lock time. |
| `lego.bricklink.pricing.apply-readiness.scheduled.lock-at-least-for` | `0s` | ShedLock duration string | Minimum distributed lock time. |

Recommended safe defaults:

```yaml
lego:
  bricklink:
    pricing:
      apply-readiness:
        enabled: true
        bricklink-external-service-id: 2
        active-listing-status-code: ACTIVE
        proposed-decision-status-code: PROPOSED
        batch-size: 25
        minimum-price-delta: 0.01
        minimum-delta:
          enabled: false
          percent: 0.02
        minimum-confidence: 0.00
        minimum-comparable-count: 1
        maximum-absolute-delta:
        maximum-percent-delta:
        blocked-reason-codes: []
        scheduled:
          enabled: false
```

Enable this only after the crawl and decision jobs are producing recent `PROPOSED` decisions. Phase 6 is read-only, so a successful run means the system found rows that would be eligible for a later apply phase; no prices are changed.

### `bricklink.rest.*`

Backed by `bricklink-rest` dependency `BricklinkRestProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `bricklink.rest.uri` | None in properties; `https://api.bricklink.com/api/store/v1` in base YAML | Absolute URI | BrickLink REST base URI. Use `/api/store/v1`, not `/v2`. |
| `bricklink.rest.consumer.key` | None | BrickLink API consumer key | OAuth consumer key. Secret, externalized. |
| `bricklink.rest.consumer.secret` | None | BrickLink API consumer secret | OAuth consumer secret. Secret, externalized. |
| `bricklink.rest.token.value` | None | BrickLink API token value | OAuth access token. Secret, externalized. |
| `bricklink.rest.token.secret` | None | BrickLink API token secret | OAuth token secret. Secret, externalized. |
| `bricklink.rest.http-logging.enabled` | `false` in dependency, `true` in base YAML | `true`, `false` | Adds request/response logging interceptor when true. |
| `bricklink.rest.http-logging.include-headers` | `false` in dependency, `true` in base YAML | `true`, `false` | Includes headers in debug logs. Sensitive headers/cookies are redacted by the interceptor. |
| `bricklink.rest.http-logging.include-body` | `true` | `true`, `false` | Includes request/response body in debug logs. |
| `bricklink.rest.http-logging.max-body-length` | `-1` | `-1` for unlimited, or non-negative max characters | Truncates logged bodies when non-negative. |

Operational notes:

| Symptom | Likely cause | Action |
| --- | --- | --- |
| HTTP `302` to `/v2/error_404.page` | Wrong base URI such as `https://api.bricklink.com/v2` | Set `bricklink.rest.uri` to `https://api.bricklink.com/api/store/v1`. |
| `401`/OAuth errors | Missing or invalid consumer/token secrets | Check `${import-path}/bricklink-client-api-keys.yml`. |
| Very large logs | `include-body=true` and `max-body-length=-1` | Set a finite max body length or disable body logging. |

### `bricklink.ajax.*`

Backed by the `bricklink-ajax` dependency.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `bricklink.ajax.uri` | `https://www.bricklink.com` in base YAML | Absolute URI | BrickLink website base URI used for internal AJAX endpoints. |
| `bricklink.ajax.rate-limit.enabled` | `true` in base YAML | `true`, `false` | Enables the client-side limiter around outbound AJAX requests. Keep true for live BrickLink calls. |
| `bricklink.ajax.rate-limit.minimum-delay-ms` | `2000` in base YAML | Long milliseconds, >= 0 | Minimum delay between outbound BrickLink AJAX requests. The user observed BrickLink bans external IPs when calls are more frequent than roughly one request every 1.5 seconds. |
| `bricklink.ajax.http-logging.enabled` | `false` in base YAML | `true`, `false` | Enables AJAX HTTP logging when supported by the dependency. Keep disabled for long-running crawls unless debugging. |

Operational notes:

| Symptom | Likely cause | Action |
| --- | --- | --- |
| Repeated BrickLink AJAX failures after recent high-frequency tests | External IP may be rate-limited or banned by BrickLink | Stop the job, keep `rate-limit.enabled=true`, use at least `minimum-delay-ms=2000`, and wait for the ban window to expire. |
| `searchproduct.ajax` finds no item | Public item number does not match the configured catalog item type or BrickLink search result | Confirm `external_catalog_item.external_item_key` and `catalog-item-type`. |
| Pricing rows are written but not exact for completeness | BrickLink pricing AJAX filters by condition only | Use exact condition/completeness DAO queries in pricing calculation; do not infer price from all returned rows blindly. |

### `lego.fulfillment.*`

Backed by `FulfillmentSyncProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.fulfillment.marketplace-code` | `BRICKLINK` | Non-blank string; normalized to uppercase | Marketplace code used to select staged orders. |
| `lego.fulfillment.metrics-tag` | `fulfillment` | Non-blank string | Low-cardinality provider tag for metrics. |
| `lego.fulfillment.statuses` | `PENDING`, `UPDATED`, `READY`, `PROCESSING`, `PAID`, `PACKED` | Marketplace external status strings; code trims, uppercases, and de-duplicates | Candidate marketplace-order statuses after the canonical-invoice eligibility gate. |
| `lego.fulfillment.sync.scheduled.enabled` | `false` | `true`, `false` | Creates scheduled fulfillment job/service beans when true. |
| `lego.fulfillment.sync.scheduled.apply` | `false` | `true`, `false` | Requests ShipStation and BrickLink writes. Effective only when `lego.environment.production=true` (PROD); suppressed to dry-run elsewhere. |
| `lego.fulfillment.sync.scheduled.batch-size` | `25` | Integer; effective value at least `1` | Candidate marketplace order limit per run. |
| `lego.fulfillment.sync.scheduled.fixed-delay-ms` | `3600000` in code and base YAML; profile cadence above overrides it | Long milliseconds, >= 0 | Delay between runs. |
| `lego.fulfillment.sync.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | First-run startup delay. |
| `lego.fulfillment.sync.scheduled.lock-at-most-for` | `10m` | ShedLock duration string | Maximum distributed lock time. |
| `lego.fulfillment.sync.scheduled.lock-at-least-for` | `0s` | ShedLock duration string | Minimum distributed lock time. |
| `lego.fulfillment.shipstation.order-number-prefix` | `BL-` | Non-blank string | Prefix used for ShipStation `orderNumber` and `orderKey`. |
| `lego.fulfillment.shipstation.domestic-country-code` | `US` | ISO country code string | Used to choose domestic vs international shipping/tracking behavior. |
| `lego.fulfillment.shipstation.carrier-code` | `stamps_com` | ShipStation carrier code | Carrier assigned to mapped ShipStation orders. |
| `lego.fulfillment.shipstation.domestic-service-code` | `usps_priority_mail` | ShipStation service code | Service code for domestic orders. |
| `lego.fulfillment.shipstation.international-service-code` | `usps_priority_mail_international` | ShipStation service code | Service code for international orders. |
| `lego.fulfillment.shipstation.package-code` | `package` | ShipStation package code | Package type assigned to mapped orders. |
| `lego.fulfillment.shipstation.insurance-provider` | `shipsurance` | ShipStation insurance provider | Provider used when insurance options are created. |
| `lego.fulfillment.shipstation.international-contents` | `merchandise` | ShipStation customs contents value | Customs content type for international options. |
| `lego.fulfillment.shipstation.international-non-delivery` | `return_to_sender` | ShipStation customs non-delivery value | Customs non-delivery action for international options. |
| `lego.fulfillment.shipstation.customs-country-of-origin` | `US` | ISO country code string | Customs origin country for international items. |
| `lego.fulfillment.shipstation.order-item-image-external-service-id` | `10` | Integer external service id | External image provider id used when resolving order item image URLs. |

Recommended safe defaults:

```yaml
lego:
  fulfillment:
    marketplace-code: BRICKLINK
    metrics-tag: fulfillment
    statuses: [PENDING, UPDATED, READY, PROCESSING, PAID, PACKED]
    sync:
      scheduled:
        enabled: true
        apply: false
        batch-size: 25
```

### `shipstation.rest.*`

Backed by the `shipstation-rest` dependency `ShipStationRestProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `shipstation.rest.uri` | `https://ssapi.shipstation.com` | Absolute URI | ShipStation API base URI. |
| `shipstation.rest.api-key` | None | ShipStation API key | Basic-auth username. Secret, externalized. |
| `shipstation.rest.api-secret` | None | ShipStation API secret | Basic-auth password. Secret, externalized. |
| `shipstation.rest.http-logging.enabled` | `false` | `true`, `false` | Adds request/response logging interceptor when true. |
| `shipstation.rest.http-logging.include-headers` | `false` | `true`, `false` | Includes headers in debug logs. Authorization is redacted by the interceptor. |
| `shipstation.rest.http-logging.include-body` | `true` | `true`, `false` | Includes request/response body in debug logs. |
| `shipstation.rest.http-logging.max-body-length` | `-1` | `-1` for unlimited, or non-negative max characters | Truncates logged bodies when non-negative. |

### `lego.image-hosting.*`

Backed by `ImageHostingSyncProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.image-hosting.default-provider` | `flickr` | Key under `lego.image-hosting.providers` | Provider used when request/job omits `provider`. |
| `lego.image-hosting.providers.<provider>.enabled` | `false` in code, `true` for Flickr in base YAML | `true`, `false` | Enables provider configuration. Readiness expects Flickr enabled when sync runtime is required. |
| `lego.image-hosting.providers.<provider>.external-service-id` | None | Integer external service id | DB external service id for provider rows. Flickr is currently `10`. |
| `lego.image-hosting.providers.<provider>.display-name` | Provider key | String | Human-readable provider name. |
| `lego.image-hosting.providers.<provider>.metrics-tag` | Provider key | Low-cardinality string | Metrics provider tag. |
| `lego.image-hosting.sync.external-service-id` | `10` | Integer | Fallback external service id when provider config does not supply one. |
| `lego.image-hosting.sync.temp-directory` | `${java.io.tmpdir}/lego-data-ingress-image-hosting` | Filesystem path | Temporary directory used when adapting S3 objects to file-path based provider APIs. |

#### Image-Hosting Retry

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.image-hosting.sync.retry.enabled` | `true` | `true`, `false` | Enables provider retry wrapper. If false, effective max attempts is `1`. |
| `lego.image-hosting.sync.retry.max-attempts` | `3` | Integer; effective value at least `1` | Maximum attempts. |
| `lego.image-hosting.sync.retry.initial-backoff-ms` | `500` | Long milliseconds; effective value at least `0` | Initial retry delay. |
| `lego.image-hosting.sync.retry.backoff-multiplier` | `2.0` | Double; effective value at least `1.0` | Exponential backoff multiplier. |
| `lego.image-hosting.sync.retry.max-backoff-ms` | `5000` | Long milliseconds; effective value at least initial backoff | Maximum retry delay. |

#### Image-Hosting Scheduled Sync

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.image-hosting.sync.scheduled.enabled` | `false` | `true`, `false` | Enables scheduled image-hosting sync job. |
| `lego.image-hosting.sync.scheduled.batch-size` | `25` | Integer; effective value at least `1` | Candidate item inventory limit per run. |
| `lego.image-hosting.sync.scheduled.concurrency` | `2` in code, `5` in base YAML, `2` in Kubernetes | Integer; effective value at least `1` | Worker thread count. |
| `lego.image-hosting.sync.scheduled.retry-failed` | `true` | `true`, `false` | Includes failed sync rows in candidate query. |
| `lego.image-hosting.sync.scheduled.apply` | `false` | `true`, `false` | If false, plans only. If true, executes plan actions. |
| `lego.image-hosting.sync.scheduled.fixed-delay-ms` | `300000` | Long milliseconds, >= 0 | Delay between runs. |
| `lego.image-hosting.sync.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | First-run startup delay. |
| `lego.image-hosting.sync.scheduled.lock-at-most-for` | `10m` | ShedLock duration string | Maximum distributed lock time. |
| `lego.image-hosting.sync.scheduled.lock-at-least-for` | `0s` | ShedLock duration string | Minimum distributed lock time. |

#### Image-Hosting Publishing

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.image-hosting.publishing.photo.title-template` | `{captionOrFilename}` | Template string | Photo title template used for provider publishing. |
| `lego.image-hosting.publishing.photo.description-template` | `{captionOrTitle}` | Template string | Photo description template. |
| `lego.image-hosting.publishing.photo.tags` | `[]` | List of strings | Provider tags added to photos. |
| `lego.image-hosting.publishing.photo.public-flag` | `true` | `true`, `false` | Flickr public visibility flag. |
| `lego.image-hosting.publishing.photo.friend-flag` | `false` | `true`, `false` | Flickr friend visibility flag. |
| `lego.image-hosting.publishing.photo.family-flag` | `false` | `true`, `false` | Flickr family visibility flag. |
| `lego.image-hosting.publishing.photo.hidden` | `false` | `true`, `false` | Provider hidden flag. |
| `lego.image-hosting.publishing.photo.safety-level` | `safe` | Provider-supported safety value, currently `safe` by default | Flickr safety level. |

#### Image-Hosting Readiness

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.image-hosting.readiness.enabled` | `true` | `true`, `false` | Enables `imageHostingReadiness` health indicator and startup readiness logs. |
| `lego.image-hosting.readiness.require-scheduled-sync-enabled` | `false` in base YAML, `true` in Kubernetes | `true`, `false` | If true, readiness is DOWN when scheduled sync is disabled. |
| `lego.image-hosting.readiness.require-bitly-when-apply-enabled` | `true` | `true`, `false` | If true, Bitly config is required when scheduled sync and apply are both enabled. |

### `flickr.*`

Backed by `lego-imaging` `FlickrProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `flickr.application-name` | None | String | Application name passed to Flickr integration where used. |
| `flickr.debug-request` | `false` when omitted | `true`, `false` | Enables Flickr request debug mode in `lego-imaging`. |
| `flickr.debug-stream` | `false` when omitted | `true`, `false` | Enables Flickr stream debug mode in `lego-imaging`. |
| `flickr.user-id` | None | Flickr user id | Required by image-hosting readiness when sync runtime is required. |
| `flickr.secrets.key` | None | Secret string | Flickr API key. |
| `flickr.secrets.secret` | None | Secret string | Flickr API secret. |
| `flickr.secrets.token` | None | Secret string | Flickr OAuth token. |
| `flickr.secrets.token-secret` | None | Secret string | Flickr OAuth token secret. |

### `bitly.*`

Backed by `lego-imaging` `BitlyProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `bitly.base-url` | `https://api-ssl.bitly.com/v4` | Absolute URI | Bitly API base URL. |
| `bitly.access-token` | None | Secret string | Bitly access token. Required for short URL operations. |
| `bitly.group-guid` | None | Bitly group GUID | Required for short URL operations. |
| `bitly.oauth2.client-id` | None | Secret string | OAuth2 client id, currently not used by the readiness check. |
| `bitly.oauth2.client-secret` | None | Secret string | OAuth2 client secret, currently not used by the readiness check. |

### `lego.minio.*`

Backed by `S3ClientProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.minio.url` | Local/Kubernetes profile sets environment-specific URL | Absolute URI | MinIO/S3 endpoint. |
| `lego.minio.access-key` | None | Secret string | MinIO access key. |
| `lego.minio.secret-key` | None | Secret string | MinIO secret key. |

`lego.minio.url`, `access-key`, and `secret-key` are required by image-hosting readiness when image-hosting sync runtime is required.

### `kafka.*`

Backed by `KafkaCustomProperties` and Spring Kafka properties.

| Property | Default/Example | Valid values | Description |
| --- | --- | --- | --- |
| `kafka.bootstrap-servers` | Redpanda dev brokers | List of host:port strings | Kafka bootstrap servers. |
| `kafka.client-id` | None | String | Optional Kafka client id. |
| `kafka.properties.*` | Security and SASL settings | Kafka client properties | Common properties applied to all producers/consumers. |
| `kafka.consumer-defaults.*` | Sandbox defines deserializers/auth | Spring Kafka consumer properties | Defaults merged into every topic consumer. |
| `kafka.producer-defaults.*` | Sandbox defines serializers/retries/auth | Spring Kafka producer properties | Defaults merged into every topic producer. |
| `kafka.topic-configuration.<id>.topic` | Topic-specific | Single topic name | Used by most listeners/templates. |
| `kafka.topic-configuration.<id>.topics` | Topic-specific | Comma-separated or YAML list of topic names | Used by listeners that subscribe to multiple topics, such as `minio-s3`. |
| `kafka.topic-configuration.<id>.consumer.group-id` | Topic-specific | String | Consumer group id. |
| `kafka.topic-configuration.<id>.consumer.concurrency` | Topic-specific | Integer | Used only where the listener annotation references it. Currently `upload-photo`. |
| `kafka.topic-configuration.<id>.consumer.properties.*` | Topic-specific | Kafka/Spring Kafka properties | Per-topic consumer overrides. Hyphens are converted to dots. |
| `kafka.topic-configuration.<id>.producer.*` | Topic-specific | Kafka producer properties | Per-topic producer overrides. Hyphens are converted to dots. |

Sandbox topic ids currently configured:

| Topic id | Role |
| --- | --- |
| `minio-s3` | Raw MinIO S3 event input. |
| `upload-minio-s3` | Producer for routed upload/delete events. |
| `upload-photo` | Routed photo upload events. |
| `delete-photo` | Routed photo delete events. |
| `upload-bricklink-catalog` | Routed BrickLink catalog file events. |
| `upload-bricklink-category` | Routed BrickLink category file events. |
| `upload-rebrickable-catalog` | Routed Rebrickable catalog file events. |
| `upload-rebrickable-theme` | Routed Rebrickable theme/category file events. |
| `photo-upload-entry` | Configured but no current main-code listener found. |
| `bricklink-catalog-entry` | Parsed BrickLink catalog entries. |
| `bricklink-category-entry` | Parsed BrickLink category entries. |
| `rebrickable-catalog-entry` | Parsed Rebrickable catalog entries. |
| `rebrickable-theme-entry` | Parsed Rebrickable theme entries. |

## Readiness And Actuator

### Actuator URLs

| Endpoint | Local profile | Kubernetes profile | Purpose |
| --- | --- | --- | --- |
| `/actuator/health` | Exposed | Exposed | Overall health. |
| `/actuator/health/liveness` | Exposed with all local endpoints | Exposed | Kubernetes liveness. |
| `/actuator/health/readiness` | Exposed with all local endpoints | Exposed | Kubernetes readiness including DB and image-hosting readiness. |
| `/actuator/prometheus` | Exposed with all local endpoints | Exposed | Prometheus scrape endpoint. |
| `/actuator/metrics` | Exposed with all local endpoints | Exposed | Metrics endpoint. |
| `/swagger-ui/index.html` | Exposed by application | Exposed unless restricted externally | OpenAPI UI. |
| `/v3/api-docs` | Exposed by application | Exposed unless restricted externally | OpenAPI JSON. |

### `imageHostingReadiness`

Enabled when:

```yaml
lego.image-hosting.readiness.enabled: true
management.health.imageHostingReadiness.enabled: true
```

Checks:

| Component | Required when | Validates |
| --- | --- | --- |
| `scheduled-sync` | Required if `require-scheduled-sync-enabled=true`; otherwise warning when disabled | Scheduled sync enabled, batch size > 0, concurrency > 0. |
| `database` | Required if scheduled sync is enabled or required | `spring.datasource.database-key-name` and `DataSource` bean. |
| `s3` | Required if scheduled sync is enabled or required | `lego.minio.url`, `lego.minio.access-key`, `lego.minio.secret-key`. |
| `flickr` | Required if scheduled sync is enabled or required | Flickr provider config, external service id, user id, and secrets. |
| `bitly` | Required if scheduled sync enabled, apply enabled, and `require-bitly-when-apply-enabled=true` | Bitly base URL, access token, and group GUID. |

Startup logs:

| Event | Meaning |
| --- | --- |
| `image_hosting.readiness.startup` | Overall readiness result at startup. |
| `image_hosting.readiness.check` | One component check with status and requirement flag. |

## Metrics

Prometheus uses Micrometer naming conventions. For example, counter `image_hosting_sync` appears as `image_hosting_sync_total` in Prometheus.

### Image Hosting Metrics

| Meter | Type | Tags | Emitted by |
| --- | --- | --- | --- |
| `image_hosting_sync` | Counter | `provider`, `outcome`, `dry_run` | Manual direct sync endpoint. |
| `image_hosting_sync_duration` | Timer | `provider`, `outcome`, `dry_run` | Manual direct sync endpoint. |
| `image_hosting_photo_upload` | Counter | `provider`, `result` | Manual direct sync photo operations. |
| `image_hosting_album_operation` | Counter | `provider`, `operation`, `result` | Album create/membership/metadata operations. |
| `image_hosting_album_creation` | Counter | `provider`, `result` | Sync-plan album creation. |
| `image_hosting_album_adoption` | Counter | `provider`, `result` | Album adoption/repair planning. |
| `image_hosting_short_url` | Counter | `provider`, `operation`, `result` | Bitly short URL operations. |
| `image_hosting_scheduled_sync` | Counter | `provider`, `outcome` | Scheduled image-hosting sync runs. |
| `image_hosting_scheduled_sync_duration` | Timer | `provider`, `outcome` | Scheduled image-hosting sync runs. |
| `image_hosting_scheduled_sync_inventory` | Counter | `provider`, `result` | Per-inventory scheduled sync results. |

### BrickLink Order Sync Metrics

| Meter | Type | Tags | Description |
| --- | --- | --- | --- |
| `bricklink_order_sync` | Counter | `provider`, `outcome` | One count per BrickLink order sync run. |
| `bricklink_order_sync_duration` | Timer | `provider`, `outcome` | Run duration. |
| `bricklink_order_sync_order` | Counter | `provider`, `result` | Order counts by `discovered`, `fetched`, `failed`, `written`. |

### BrickLink Pricing Crawl Metrics

| Meter | Type | Tags | Description |
| --- | --- | --- | --- |
| `bricklink_pricing_crawl_job` | Counter | `outcome` | One count per crawl job run. Outcomes are lowercased result values such as `success`, `scheduled`, `partial_success`, or `no_work`. |
| `bricklink_pricing_crawl_job_duration` | Timer | `outcome` | Crawl job run duration. Prometheus exposes this as `_seconds_count`, `_seconds_sum`, and `_seconds_max`. |
| `bricklink_pricing_crawl_listing` | Counter | `result` | Listing counts by `selected`, `skipped`, and `failed`. |
| `bricklink_pricing_crawl_work_item` | Counter | `result` | Work item counts by `scheduled`, `claimed`, and `stale_requeued`. |
| `bricklink_pricing_crawl_snapshot` | Counter | `result` | Snapshot write counts by `snapshot`, `zero_comparable_snapshot`, and `snapshot_listing`. |
| `bricklink_pricing_crawl_catalog_item` | Counter | `result` | Catalog hydration counts by `hydrated`, `no_match`, `ambiguous_match`, and `failed_request`. |
| `bricklink_pricing_crawl_work_item_current` | Gauge | `state` | Current work item counts based on the latest `pricing_crawl_work_item` per marketplace listing. Historical failed/skipped rows that were superseded by a newer successful crawl do not count as current failures. States are `pending`, `due`, `retryable`, `claimed`, `stale_claimed`, `succeeded`, `failed`, and `skipped`. |

Prometheus examples:

```promql
sum by (outcome) (increase(bricklink_pricing_crawl_job_total{namespace="$namespace",service="$service"}[$__range]))
sum by (result) (increase(bricklink_pricing_crawl_snapshot_total{namespace="$namespace",service="$service"}[$__range]))
sum by (state) (bricklink_pricing_crawl_work_item_current{namespace="$namespace",service="$service"})
sum by (outcome) (rate(bricklink_pricing_crawl_job_duration_seconds_sum{namespace="$namespace",service="$service"}[5m]))
/
sum by (outcome) (rate(bricklink_pricing_crawl_job_duration_seconds_count{namespace="$namespace",service="$service"}[5m]))
```

Healthy sandbox behavior:

| Signal | Expected behavior |
| --- | --- |
| Crawl job runs | Counter increases on the configured scheduler cadence. In Kubernetes sandbox this may be every 3 minutes when the faster sandbox cadence is deployed. |
| Claimed work items | Usually increments by the worker batch size, currently expected to be small for BrickLink safety. |
| Snapshot listings | Increases only when BrickLink returns comparable listings and snapshot persistence succeeds. |
| `due` gauge | Can be nonzero when pending work is immediately eligible. A constantly growing `due` count means the worker is not keeping up or is failing before claim. |
| `retryable` gauge | Nonzero is acceptable for transient BrickLink/network failures. It should not grow without later successful retries or terminal failures. |
| `stale_claimed` gauge | Should normally be zero. Nonzero means prior claimed work was abandoned long enough to pass `claim-stale-after`. |

### BrickLink Pricing Decision Metrics

| Meter | Type | Tags | Description |
| --- | --- | --- | --- |
| `bricklink_pricing_decision_job` | Counter | `outcome` | One count per pricing decision job run. |
| `bricklink_pricing_decision_job_duration` | Timer | `outcome` | Decision job run duration. |
| `bricklink_pricing_decision_listing` | Counter | `result` | Listing counts by `selected`. |
| `bricklink_pricing_decision` | Counter | `result` | Decision counts by `written`, `proposed`, `skipped`, and `failed`. |
| `bricklink_pricing_decision_current` | Gauge | `status`, `unapplied_only` | Current latest decision counts by status. Status values include `proposed`, `failed`, and `skipped`. `unapplied_only=true` is currently registered for latest proposed decisions. |

Prometheus examples:

```promql
sum by (outcome) (increase(bricklink_pricing_decision_job_total{namespace="$namespace",service="$service"}[$__range]))
sum by (result) (increase(bricklink_pricing_decision_total{namespace="$namespace",service="$service"}[$__range]))
sum by (status,unapplied_only) (bricklink_pricing_decision_current{namespace="$namespace",service="$service"})
```

Healthy sandbox behavior:

| Signal | Expected behavior |
| --- | --- |
| Decision job runs | Counter increases on the configured scheduler cadence. |
| Decisions written | Should increase when new priceable candidates exist. After de-dupe hardening, it should not repeatedly write identical decisions for the same listing and same latest snapshot. |
| Proposed decisions | Should rise as matching crawl snapshots become available. |
| Failed decisions | Useful during early crawl rollout. High `failed` counts usually mean no matching snapshot yet, no exact comparables, or missing inventory condition/completeness. |
| Current gauges | Use these for current review state instead of counting all historical `pricing_decision` rows. |

### BrickLink Pricing Apply Readiness Metrics

| Meter | Type | Tags | Description |
| --- | --- | --- | --- |
| `bricklink_pricing_apply_readiness_job` | Counter | `outcome` | One count per apply-readiness dry-run job. |
| `bricklink_pricing_apply_readiness_job_duration` | Timer | `outcome` | Apply-readiness dry-run duration. |
| `bricklink_pricing_apply_readiness_decision` | Counter | `result` | Decision review counts by `selected`, `ready_to_apply`, `skipped_fixed_price`, `skipped_missing_current_price`, `skipped_missing_final_price`, `skipped_currency_mismatch`, `skipped_unsupported_decision_status`, `skipped_blocked_reason_code`, `skipped_ineligible_reason`, `skipped_below_minimum_delta`, `skipped_below_minimum_delta_percent`, `skipped_below_minimum_confidence`, `skipped_below_minimum_comparable_count`, `skipped_above_maximum_absolute_delta`, `skipped_above_maximum_percent_delta`, and `skipped_stale_decision`. |
| `bricklink_pricing_apply_readiness_current` | Gauge | `status` | Current apply-readiness count by persisted status, limited to readiness rows attached to each listing's latest pricing decision. Registered status tags are `ready_to_apply`, `ready_to_apply_initial_price`, `blocked_fixed_price`, `blocked_missing_current_price`, `blocked_missing_final_price`, `blocked_currency_mismatch`, `blocked_unsupported_decision_status`, `blocked_reason_code`, `blocked_ineligible_reason`, `blocked_below_minimum_delta`, `blocked_below_minimum_delta_percent`, `blocked_below_minimum_confidence`, `blocked_below_minimum_comparable_count`, `blocked_above_maximum_absolute_delta`, `blocked_above_maximum_percent_delta`, and `blocked_stale_decision`. |
| `bricklink_pricing_apply_readiness_block_reason_current` | Gauge | `reason` | Current apply-readiness count by persisted block reason, limited to readiness rows attached to each listing's latest pricing decision. Registered reason tags are `fixed_price`, `missing_current_price`, `missing_final_price`, `currency_mismatch`, `unsupported_decision_status`, `blocked_reason_code`, `ineligible_reason`, `below_minimum_delta`, `below_minimum_delta_percent`, `below_minimum_confidence`, `below_minimum_comparable_count`, `above_maximum_absolute_delta`, `above_maximum_percent_delta`, and `stale_decision`. |

Prometheus examples:

```promql
sum by (outcome) (increase(bricklink_pricing_apply_readiness_job_total{namespace="$namespace",service="$service"}[$__range]))
sum by (result) (increase(bricklink_pricing_apply_readiness_decision_total{namespace="$namespace",service="$service"}[$__range]))
sum by (status) (bricklink_pricing_apply_readiness_current{namespace="$namespace",service="$service"})
sum by (reason) (bricklink_pricing_apply_readiness_block_reason_current{namespace="$namespace",service="$service"})
```

Healthy sandbox behavior:

| Signal | Expected behavior |
| --- | --- |
| Apply-readiness runs | Counter increases when the dry-run job is enabled. |
| Ready to apply | Indicates latest unapplied proposed decisions that would be eligible for a future apply phase. This still does not mutate listing prices. |
| Skipped fixed price | Expected for fixed-price listings. These are intentionally protected. |
| Skipped below minimum delta | Expected when proposed and current prices are effectively the same. |
| Skipped ineligible reason | Review if unexpectedly high. It means the proposed decision reason code is not configured as apply-eligible. |

### BrickLink Pricing Apply And Marketplace Sync Metrics

| Meter | Type | Tags | Description |
| --- | --- | --- | --- |
| `bricklink_pricing_apply_job` | Counter | `outcome` | One count per pricing apply job run. |
| `bricklink_pricing_apply_job_duration` | Timer | `outcome` | Pricing apply job duration. |
| `bricklink_pricing_apply_decision` | Counter | `result` | Apply counts by `selected`, `local_price_updated`, `sync_request_enqueued`, `dry_run_selected`, `skipped`, and `failed`. |
| `bricklink_marketplace_sync_job` | Counter | `outcome` | One count per BrickLink marketplace sync job run. |
| `bricklink_marketplace_sync_job_duration` | Timer | `outcome` | BrickLink marketplace sync job duration. |
| `bricklink_marketplace_sync_request` | Counter | `result` | Sync worker counts by `selected`, `claimed`, `remote_verified`, `remote_updated`, `dry_run_verified`, `blocked`, and `failed`. |
| `bricklink_marketplace_sync_request_current` | Gauge | `state` | Current sync request counts by persisted state. Registered states are `pending`, `due`, `claimed`, `succeeded`, `blocked`, and `failed`. |

Prometheus examples:

```promql
sum by (outcome) (increase(bricklink_pricing_apply_job_total{namespace="$namespace",service="$service"}[$__range]))
sum by (result) (increase(bricklink_pricing_apply_decision_total{namespace="$namespace",service="$service"}[$__range]))
sum by (outcome) (increase(bricklink_marketplace_sync_job_total{namespace="$namespace",service="$service"}[$__range]))
sum by (result) (increase(bricklink_marketplace_sync_request_total{namespace="$namespace",service="$service"}[$__range]))
sum by (state) (bricklink_marketplace_sync_request_current{namespace="$namespace",service="$service"})
```

Healthy sandbox behavior:

| Signal | Expected behavior |
| --- | --- |
| Apply dry-run selections | In sandbox default dry-run mode, `dry_run_selected` can increase while `local_price_updated` remains zero. |
| Sync dry-run verified | In sandbox default dry-run mode, `dry_run_verified` can increase if pending sync requests exist, while `remote_updated` remains zero. |
| Sync blocked | Nonzero means the safety contract prevented a remote write. Review `last_error_message` and `bricklink_marketplace_listing.last_remote_safety_*`. |
| Sync failed | Nonzero means a transient or unexpected failure happened. Pending retry rows should have future `next_attempt_at`; terminal rows use status `FAILED`. |

### Fulfillment Sync Metrics

| Meter | Type | Tags | Description |
| --- | --- | --- | --- |
| `fulfillment_sync` | Counter | `provider`, `outcome`, `apply` | One count per fulfillment sync run. |
| `fulfillment_sync_duration` | Timer | `provider`, `outcome`, `apply` | Run duration. |
| `fulfillment_sync_order` | Counter | `provider`, `result` | Order counts by `discovered`, `loaded`, `payload_missing`, `mapped`, `created`, `updated`, `shipped_reconciled`, `skipped`, `failed`. |

### Photo Processing Metrics

| Meter | Type | Tags | Description |
| --- | --- | --- | --- |
| `photo.processed` | Counter | `mode` | Successful photo processing count. `mode` is usually `event` or `batch`. |
| `photo.failed` | Counter | `mode` | Failed photo processing count. |
| `photo.duplicate` | Counter | `mode` | Duplicate photo count. |
| `photo.skipped` | Counter | `mode`, `reason` | Skipped photo count. |
| `photo.processing.time` | Timer | `mode` | Photo processing duration. |

## Structured Logs

### BrickLink REST HTTP Logs

When `bricklink.rest.http-logging.enabled=true` and logger `com.bricklink.api` is DEBUG, the REST client logs:

| Event | Fields |
| --- | --- |
| `bricklink.rest.request` | HTTP method, URI, headers when enabled, body when enabled. |
| `bricklink.rest.response` | HTTP method, URI, status code/text, headers when enabled, body when enabled. |

Authorization and cookies are redacted by the logging interceptor.

### BrickLink Order Sync Logs

Primary events:

| Event | Purpose |
| --- | --- |
| `bricklink.order_sync.probe.started` | Run start with direction/statuses/apply. |
| `bricklink.order_sync.probe.no_work` | No orders discovered. |
| `bricklink.order_sync.probe.order_fetched` | One order fetched with important summary fields. |
| `bricklink.order_sync.probe.coverage` | Order-level field coverage. |
| `bricklink.order_sync.probe.item_coverage` | Item-level field coverage counts. |
| `bricklink.order_sync.probe.completed` | Final outcome and counters. |
| `bricklink.order_sync.probe.failed` | Run-level failure. |

### BrickLink Pricing Crawl Logs

Primary events:

| Event | Purpose |
| --- | --- |
| `bricklink.pricing.crawl.schedule.skipped_missing_catalog` | One scheduling candidate had no linked external catalog item; the scheduler skips creating a work item. |
| `bricklink.pricing.crawl.skipped_missing_catalog` | One claimed work item referenced a listing without a linked external catalog item; the worker marks the item skipped. |
| `bricklink.pricing.crawl.job.completed` | Scheduled run completed and logs `BricklinkPricingCrawlResult`. |

`BricklinkPricingCrawlResult` fields:

| Field | Meaning |
| --- | --- |
| `outcome` | `NO_WORK`, `SCHEDULED`, `SUCCESS`, or `PARTIAL_SUCCESS`. |
| `listingsSelected` | Number of active BrickLink marketplace listings selected as scheduling candidates after allowlist filtering. |
| `workItemsScheduled` | Number of new `PENDING` `pricing_crawl_work_item` rows inserted. |
| `workItemsClaimed` | Number of due work items transitioned from `PENDING` to `CLAIMED` and processed. |
| `staleWorkItemsRequeued` | Number of abandoned `CLAIMED` work items returned to `PENDING`. |
| `snapshotsWritten` | Number of `pricing_snapshot` rows inserted. |
| `zeroComparableSnapshotsWritten` | Number of `pricing_snapshot` rows inserted after BrickLink returned no comparable listing rows. |
| `snapshotListingsWritten` | Number of `pricing_snapshot_listing` rows inserted. |
| `hydratedCatalogItems` | Number of missing BrickLink internal `idItem` values populated into `external_catalog_item.external_unique_key`. |
| `catalogItemLookupNoMatches` | Number of BrickLink internal id lookups with no exact match. |
| `catalogItemLookupAmbiguousMatches` | Number of BrickLink internal id lookups with multiple matches. |
| `catalogItemLookupFailures` | Number of BrickLink internal id lookup client/runtime failures. |
| `skippedListings` | Number of listings skipped for missing/unusable local data. |
| `failedListings` | Number of listings that failed due to lookup, HTTP/client, or parsing errors. |
| `elapsedMillis` | Run duration in milliseconds. |

### BrickLink Pricing Decision Logs

Primary events:

| Event | Purpose |
| --- | --- |
| `bricklink.pricing.decision.job.completed` | Scheduled run completed and logs `BricklinkPricingDecisionResult`. |

`BricklinkPricingDecisionResult` fields:

| Field | Meaning |
| --- | --- |
| `outcome` | `NO_WORK`, `SUCCESS`, or `PARTIAL_SUCCESS`. |
| `listingsSelected` | Number of eligible BrickLink pricing decision candidates selected for the run. |
| `decisionsWritten` | Number of `pricing_decision` rows inserted. |
| `proposedDecisions` | Number of computed non-applied `PROPOSED` decisions. |
| `skippedDecisions` | Number of intentional skips, currently fixed-price overrides. |
| `failedDecisions` | Number of listings where no price could be safely computed. |
| `elapsedMillis` | Run duration in milliseconds. |

### BrickLink Pricing Apply Readiness Logs

Primary events:

| Event | Purpose |
| --- | --- |
| `bricklink.pricing.apply_readiness.ready` | DEBUG-level detail for one latest proposed decision that would update a current marketplace listing price in a later apply phase. |
| `bricklink.pricing.apply_readiness.job.completed` | Scheduled run completed and logs `BricklinkPricingApplyReadinessResult`. |

`BricklinkPricingApplyReadinessResult` fields:

| Field | Meaning |
| --- | --- |
| `outcome` | `NO_WORK`, `SUCCESS`, or `NO_READY_DECISIONS`. |
| `decisionsSelected` | Number of latest proposed, unapplied decisions selected for dry-run readiness review. |
| `readyToApply` | Number of selected decisions that would be eligible for a later apply phase. |
| `skippedFixedPrice` | Number skipped because the current marketplace listing is fixed price. |
| `skippedMissingCurrentPrice` | Number skipped because the current marketplace listing price is missing. |
| `skippedMissingFinalPrice` | Number skipped because the proposed final price is missing. |
| `skippedCurrencyMismatch` | Number skipped because current listing currency and decision currency differ. |
| `skippedUnsupportedDecisionStatus` | Number skipped because the selected decision status no longer matches the proposed status guard. |
| `skippedBlockedReasonCode` | Number skipped because the proposed decision reason code is configured as blocked. |
| `skippedIneligibleReason` | Number skipped because the proposed decision reason code is not configured as apply-eligible. |
| `skippedBelowMinimumDelta` | Number skipped because current and proposed prices differ by less than `minimum-price-delta`. |
| `skippedBelowMinimumConfidence` | Number skipped because decision confidence is below `minimum-confidence`. |
| `skippedBelowMinimumComparableCount` | Number skipped because comparable count is below `minimum-comparable-count`. |
| `skippedAboveMaximumAbsoluteDelta` | Number skipped because absolute price movement is greater than `maximum-absolute-delta`. |
| `skippedAboveMaximumPercentDelta` | Number skipped because relative price movement is greater than `maximum-percent-delta`. |
| `elapsedMillis` | Run duration in milliseconds. |

### Fulfillment Sync Logs

Primary events:

| Event | Purpose |
| --- | --- |
| `fulfillment.sync_job.started` | Run start with provider, marketplace code, statuses, batch size, and apply mode. |
| `fulfillment.sync_job.no_work` | No marketplace order candidates selected. |
| `fulfillment.sync_job.payload_missing` | A candidate lacks the staged order or order-items payload needed for mapping. |
| `fulfillment.sync_job.order_mapped` | One candidate mapped and reports the fulfillment action. |
| `fulfillment.sync_job.order_failed` | One candidate failed; the job continues with later candidates. |
| `fulfillment.sync_job.completed` | Final outcome and counters. |

When `apply=true`, ShipStation and BrickLink client HTTP logging can also be enabled for targeted diagnosis. Keep body logging off or bounded in long-running deployments because order payloads can contain customer addresses.

### Image Hosting Logs

Primary events:

| Event | Purpose |
| --- | --- |
| `image_hosting.sync.started` | Manual direct sync started. |
| `image_hosting.sync.completed` | Manual direct sync completed. |
| `image_hosting.sync.failed` | Manual direct sync failed. |
| `image_hosting.photo_upload.started` | Photo upload started. |
| `image_hosting.photo_upload.completed` | Photo upload completed. |
| `image_hosting.photo_upload.skipped` | Photo upload skipped. |
| `image_hosting.photo_upload.failed` | Photo upload failed. |
| `image_hosting.photo_metadata_update.started` | Photo metadata update started. |
| `image_hosting.photo_metadata_update.completed` | Photo metadata update completed. |
| `image_hosting.photo_metadata_update.failed` | Photo metadata update failed. |
| `image_hosting.album.create.started` | Album create started. |
| `image_hosting.album.create.completed` | Album create completed. |
| `image_hosting.album.create.failed` | Album create failed. |
| `image_hosting.album_membership.update.started` | Album membership update started. |
| `image_hosting.album_membership.update.completed` | Album membership update completed. |
| `image_hosting.album_membership.update.failed` | Album membership update failed. |
| `image_hosting.sync_job.*` | Scheduled job lifecycle and per-inventory result events. |
| `image_hosting.readiness.*` | Startup readiness evaluation. |

### Photo Logs

Primary events:

| Event | Purpose |
| --- | --- |
| `photo.kafka.consume.start` | Photo upload event consumption started. |
| `photo.kafka.consume.success` | Photo upload event processed successfully. |
| `photo.kafka.consume.complete` | Photo upload event consumption completed with duration. |
| `photo.process.start` | Photo processing started. |
| `photo.process.success` | Photo processing succeeded. |
| `photo.process.source_missing` | Source object missing for stale S3 event. |
| `photo.process.rejected` | Photo rejected by validation/business rules. |
| `photo.process.failed` | Photo processing failed. |
| `photo.process.complete` | Photo processing completed with duration. |
| `photo.delete.kafka.consume.start` | Photo delete event consumption started. |
| `photo.delete.kafka.consume.success` | Photo delete event processed successfully. |
| `photo.delete.kafka.consume.complete` | Photo delete event consumption completed with duration. |
| `photo.delete.process.start` | Delete service started processing an object delete. |

## Rollout Procedures

### Safe BrickLink Pricing Crawl Rollout

1. Confirm the Pricing Plane tables exist in the target database: `pricing_crawl_work_item`, `pricing_snapshot`, `pricing_snapshot_listing`, `pricing_decision`, and `pricing_apply_readiness`.
2. Confirm active BrickLink marketplace listings exist with `marketplace_listing.listing_external_service_id=2`, `listing_status_code='ACTIVE'`, and a populated `external_catalog_item_id`.
3. Confirm `external_catalog_item.external_item_key` contains the public BrickLink item number, for example `6390-1`.
4. Set `bricklink.ajax.uri=https://www.bricklink.com`.
5. Keep `bricklink.ajax.rate-limit.enabled=true`.
6. Keep `bricklink.ajax.rate-limit.minimum-delay-ms` at `2000` or higher for live BrickLink calls.
7. Start with `lego.bricklink.pricing.crawl.enabled=true` and `lego.bricklink.pricing.crawl.scheduled.enabled=false`.
8. For the first scheduled validation, use `lego.bricklink.pricing.crawl.marketplace-listing-allowlist` or a small `batch-size` to limit scheduled work.
9. Keep `lego.bricklink.pricing.crawl.worker-batch-size=1` for first live AJAX validation.
10. Enable `lego.bricklink.pricing.crawl.scheduled.enabled=true` only during a controlled local/sandbox run.
11. Watch `bricklink.pricing.crawl.job.completed` for selected, scheduled, claimed, stale requeue, hydrated, snapshot, listing, skipped, and failed counters.
12. Confirm new work items are spread across `schedule-spread-window` and not placed inside blackout periods when blackout is enabled.
13. Confirm due work is processed one claimed row at a time according to `worker-batch-size`.
14. Run the pricing SQL checks below to confirm work items, snapshots, snapshot listings, and `external_unique_key` hydration.
15. Disable scheduling again after validation unless the configured schedule window, fixed delay, and worker batch size are intentionally safe for the current listing count.

Rollback:

```yaml
lego:
  bricklink:
    pricing:
      crawl:
        scheduled:
          enabled: false
```

Set `lego.bricklink.pricing.crawl.enabled=false` if the service bean should be disabled entirely. If BrickLink starts failing or throttling AJAX requests, stop the job first; do not repeatedly retry at a high cadence.

### Safe BrickLink Pricing Decision Rollout

1. Confirm the Pricing Plane tables exist in the target database: `pricing_crawl_work_item`, `pricing_snapshot`, `pricing_snapshot_listing`, `pricing_decision`, and `pricing_apply_readiness`.
2. Run the BrickLink pricing crawl first and verify recent `pricing_snapshot` and `pricing_snapshot_listing` rows exist.
3. Confirm active BrickLink marketplace listings have `marketplace_listing.unit_price`, `currency_code`, `fixed_price`, `external_listing_id`, and a linked `item_inventory`.
4. Confirm `item_inventory.new_or_used` and `item_inventory.completeness` are populated. Supported values normalize to `N`/`U` and `S`/`C`/`X`.
5. Start with `lego.bricklink.pricing.decision.enabled=true` and `lego.bricklink.pricing.decision.scheduled.enabled=false`.
6. Use a small `lego.bricklink.pricing.decision.batch-size` for first validation, for example `1` to `5`.
7. Leave `minimum-price` and `maximum-price` unset unless you explicitly want global clamping during validation.
8. Enable `lego.bricklink.pricing.decision.scheduled.enabled=true` only during a controlled local/sandbox run.
9. Watch `bricklink.pricing.decision.job.completed` for proposed, skipped, and failed counters.
10. Review `pricing_decision` rows before any later apply/sync phase.
11. Disable scheduling again after validation unless the run cadence is intentionally safe.

Rollback:

```yaml
lego:
  bricklink:
    pricing:
      decision:
        scheduled:
          enabled: false
```

Set `lego.bricklink.pricing.decision.enabled=false` if the service bean should be disabled entirely. Phase 3 does not update marketplace listing prices, so rollback is stopping future decision rows rather than undoing applied price changes.

### Safe BrickLink Pricing Apply Readiness Rollout

1. Confirm the BrickLink pricing crawl has produced recent `pricing_snapshot` and `pricing_snapshot_listing` rows.
2. Confirm the BrickLink pricing decision job has produced recent latest `PROPOSED` decisions.
3. Start with `lego.bricklink.pricing.apply-readiness.enabled=true` and `lego.bricklink.pricing.apply-readiness.scheduled.enabled=false`.
4. Use a small `lego.bricklink.pricing.apply-readiness.batch-size` for first validation, for example `1` to `5`.
5. Keep `lego.bricklink.pricing.apply-readiness.minimum-price-delta=0.01` unless you want to suppress very small price changes.
6. Enable `lego.bricklink.pricing.apply-readiness.scheduled.enabled=true` only during a controlled local/sandbox run.
7. Watch `bricklink.pricing.apply_readiness.job.completed` for selected, ready, and skipped counters.
8. Watch `bricklink.pricing.apply_readiness.ready` rows for the specific marketplace listings and price deltas that a later apply phase would change.
9. Run the ready-for-apply SQL check below and confirm it agrees with the job counters.
10. Disable scheduling again after validation unless the run cadence is intentionally safe.

Rollback:

```yaml
lego:
  bricklink:
    pricing:
      apply-readiness:
        scheduled:
          enabled: false
```

Set `lego.bricklink.pricing.apply-readiness.enabled=false` if the service bean should be disabled entirely. Phase 6 does not update `marketplace_listing`, mark decisions applied, or trigger marketplace sync, so rollback is stopping future dry-run scans.

### Safe BrickLink Order Sync Rollout

1. Confirm BrickLink API credentials are present in `${import-path}/bricklink-client-api-keys.yml`.
2. Set `bricklink.rest.uri=https://api.bricklink.com/api/store/v1`.
3. Keep `lego.bricklink.orders.sync.scheduled.enabled=true`.
4. Keep `lego.bricklink.orders.sync.scheduled.apply=false` for initial validation.
5. Enable DEBUG logs for `com.bricklink.api` only while validating HTTP behavior.
6. Watch `bricklink.rest.request`, `bricklink.rest.response`, and `bricklink.order_sync.probe.*` logs.
7. Confirm order count and coverage logs look correct.
8. Confirm marketplace sync tables exist in the target database before apply mode.
9. Set `lego.bricklink.orders.sync.scheduled.apply=true` only after DB schema and probe logs are verified.
10. After enabling apply, verify `marketplace_order_sync_run`, `marketplace_order`, `marketplace_order_item`, and `marketplace_order_payload` rows.

Rollback:

```yaml
lego:
  bricklink:
    orders:
      sync:
        scheduled:
          apply: false
```

Set `enabled=false` if the job should stop entirely.

### Safe Fulfillment Sync Rollout

1. Confirm BrickLink order sync has already run with `apply=true` so `marketplace_order`, `marketplace_order_item`, and `marketplace_order_payload` contain current staged orders.
2. Confirm BrickLink credentials and ShipStation credentials are present in runtime config. For sandbox/local profile runs, ShipStation credentials normally come from `${import-path}/shipstation-client-api-keys.yml`.
3. Set `lego.fulfillment.sync.scheduled.enabled=true`.
4. Keep `lego.fulfillment.sync.scheduled.apply=false` for initial validation.
5. Confirm the selected order has an `INVOICED` canonical order link and active canonical transaction-item links, then watch `fulfillment.sync_job.order_mapped` and confirm `orderNumber=BL-{bricklinkOrderId}`, item counts, image URLs, insurance options, international options, and shipping service choices are correct.
6. Confirm `/actuator/prometheus` exposes `fulfillment_sync`, `fulfillment_sync_duration`, and `fulfillment_sync_order`.
7. Set a small `lego.fulfillment.sync.scheduled.batch-size` before first live apply validation.
8. Set `lego.fulfillment.sync.scheduled.apply=true` only in PROD (`lego.environment.production=true`), and only after dry-run mapping and credentials are verified. In local, sandbox, and dev, apply stays `false`, and the service suppresses it anyway.
9. Watch `fulfillment.sync_job.completed` for `ordersCreated`, `ordersUpdated`, `ordersShippedReconciled`, `ordersSkipped`, and `ordersFailed`.
10. Confirm ShipStation contains one order per BrickLink order number, the local canonical shipment has the ShipStation shipment ID/carrier/service/tracking and transaction-item links, and BrickLink is updated only after ShipStation reports the order shipped with tracking.

Rollback:

```yaml
lego:
  fulfillment:
    sync:
      scheduled:
        apply: false
```

Set `enabled=false` if the job should stop entirely. Existing ShipStation orders created before rollback remain in ShipStation and will be found again by `BL-{orderId}` if apply mode is re-enabled.

### Safe Image-Hosting Scheduled Sync Rollout

1. Start with `lego.image-hosting.sync.scheduled.enabled=true` and `apply=false`.
2. Confirm `/actuator/health/readiness` is `UP` or only has expected non-required warnings.
3. Watch `image_hosting.sync_job.candidates_selected` for candidate counts.
4. Review `image_hosting.sync_job.inventory_planned` to confirm planned action volume.
5. Confirm Flickr credentials and MinIO credentials are present.
6. Confirm Bitly credentials are present before enabling apply because album short URLs may be created or recovered.
7. Set `lego.image-hosting.sync.scheduled.apply=true` only after dry-run plans look correct.
8. Watch `image_hosting.sync_job.inventory_completed`, `image_hosting.album_*`, and `image_hosting.short_url` logs.
9. Use `/actuator/prometheus` to confirm scheduled sync counters and duration timers.

Rollback:

```yaml
lego:
  image-hosting:
    sync:
      scheduled:
        apply: false
```

Set `enabled=false` if the job should stop entirely. In Kubernetes, also consider `lego.image-hosting.readiness.require-scheduled-sync-enabled=false` if disabling the job intentionally.

### Manual Image-Hosting Validation

Read-only validation:

```http
GET /internal/image-hosting/item-inventories/{itemInventoryId}/generated-description
GET /internal/image-hosting/item-inventories/{itemInventoryId}/sync-plan
GET /internal/image-hosting/remote/albums/{albumId}/snapshot
GET /internal/image-hosting/item-inventories/{itemInventoryId}/repair-plan
```

Apply after review:

```http
POST /internal/image-hosting/item-inventories/{itemInventoryId}/sync-plan/apply
```

Use legacy direct sync only when you specifically want the old path:

```http
POST /internal/image-hosting/item-inventories/{itemInventoryId}/sync?dryRun=false
```

## Operational Checks

### Startup

1. Confirm expected profiles in startup logs.
2. Confirm imported credential files are present under `import-path`.
3. Confirm Kafka dynamic bean registration logs appear and sensitive values are masked.
4. Confirm `image_hosting.readiness.startup` if readiness is enabled.
5. Confirm scheduled jobs are either enabled intentionally or absent because the corresponding `enabled` property is false.

### BrickLink Pricing Crawl SQL Checks

Confirm active BrickLink listings eligible for crawl:

```sql
select ml.marketplace_listing_id,
       ml.item_inventory_id,
       ml.listing_external_service_id,
       ml.external_catalog_item_id,
       ml.external_listing_id,
       ml.listing_status_code,
       ii.uuid,
       ii.new_or_used,
       ii.completeness,
       eci.external_item_key,
       eci.external_unique_key
from marketplace_listing ml
join item_inventory ii
  on ii.item_inventory_id = ml.item_inventory_id
join external_catalog_item eci
  on eci.external_catalog_item_id = ml.external_catalog_item_id
where ml.listing_external_service_id = 2
  and ml.listing_status_code = 'ACTIVE'
order by ml.marketplace_listing_id
limit 50;
```

Check recent work item outcomes:

```sql
select work_status_code,
       count(*) as row_count,
       min(created_at) as first_created,
       max(updated_at) as last_updated
from pricing_crawl_work_item
group by work_status_code
order by row_count desc;
```

Check current work item outcomes using only the latest work item per marketplace listing. This mirrors `bricklink_pricing_crawl_work_item_current` and avoids counting historical failures that were later superseded:

```sql
select pcwi.work_status_code,
       count(*) as latest_listing_count,
       min(pcwi.updated_at) as oldest_latest_update,
       max(pcwi.updated_at) as newest_latest_update
from pricing_crawl_work_item pcwi
join (
    select marketplace_listing_id,
           max(pricing_crawl_work_item_id) as pricing_crawl_work_item_id
    from pricing_crawl_work_item
    group by marketplace_listing_id
) latest
  on latest.pricing_crawl_work_item_id = pcwi.pricing_crawl_work_item_id
group by pcwi.work_status_code
order by latest_listing_count desc,
         pcwi.work_status_code;
```

Check due pending work in the crawl queue:

```sql
select pricing_crawl_work_item_id,
       marketplace_listing_id,
       attempt_count,
       max_attempts,
       next_attempt_at,
       created_at,
       updated_at
from pricing_crawl_work_item
where work_status_code = 'PENDING'
  and next_attempt_at <= current_timestamp
  and coalesce(attempt_count, 0) < coalesce(max_attempts, 3)
order by next_attempt_at, pricing_crawl_work_item_id
limit 50;
```

Check stale claimed work that should be recovered by a later run:

```sql
select pricing_crawl_work_item_id,
       marketplace_listing_id,
       attempt_count,
       max_attempts,
       claimed_at,
       last_error_message
from pricing_crawl_work_item
where work_status_code = 'CLAIMED'
order by claimed_at, pricing_crawl_work_item_id
limit 50;
```

Review recent work items with request parameters and errors:

```sql
select pcwi.pricing_crawl_work_item_id,
       pcwi.marketplace_listing_id,
       pcwi.external_catalog_item_id,
       eci.external_item_key,
       eci.external_unique_key,
       pcwi.work_status_code,
       pcwi.attempt_count,
       pcwi.max_attempts,
       pcwi.next_attempt_at,
       pcwi.claimed_at,
       pcwi.completed_at,
       pcwi.source_request_url,
       pcwi.source_request_parameters,
       pcwi.last_error_message,
       pcwi.created_at
from pricing_crawl_work_item pcwi
left join external_catalog_item eci
  on eci.external_catalog_item_id = pcwi.external_catalog_item_id
order by pcwi.pricing_crawl_work_item_id desc
limit 50;
```

Check recent snapshots and persisted comparable counts:

```sql
select ps.pricing_snapshot_id,
       ps.marketplace_listing_id,
       ps.source_item_key,
       ps.source_unique_key,
       ps.item_condition_code,
       ps.completeness_code,
       ps.comparable_count,
       count(psl.pricing_snapshot_listing_id) as persisted_listing_count,
       min(psl.unit_price) as min_unit_price,
       avg(psl.unit_price) as avg_unit_price,
       max(psl.unit_price) as max_unit_price,
       ps.captured_at
from pricing_snapshot ps
left join pricing_snapshot_listing psl
  on psl.pricing_snapshot_id = ps.pricing_snapshot_id
group by ps.pricing_snapshot_id,
         ps.marketplace_listing_id,
         ps.source_item_key,
         ps.source_unique_key,
         ps.item_condition_code,
         ps.completeness_code,
         ps.comparable_count,
         ps.captured_at
order by ps.pricing_snapshot_id desc
limit 25;
```

Review latest zero-comparable snapshots. These indicate BrickLink returned no current comparable listings for the crawl target; they are successful observations and can produce `NO_CURRENT_COMPARABLES` decisions:

```sql
select ps.pricing_snapshot_id,
       ps.marketplace_listing_id,
       ml.external_listing_id,
       eci.external_item_key,
       ps.source_unique_key,
       ps.item_condition_code,
       ps.completeness_code,
       ps.comparable_count,
       ps.source_request_url,
       ps.source_request_parameters,
       ps.captured_at
from pricing_snapshot ps
join marketplace_listing ml
  on ml.marketplace_listing_id = ps.marketplace_listing_id
left join external_catalog_item eci
  on eci.external_catalog_item_id = ps.external_catalog_item_id
where ps.comparable_count = 0
order by ps.captured_at desc,
         ps.pricing_snapshot_id desc
limit 50;
```

Query latest exact comparables for one marketplace listing. This is the shape Phase 3 pricing should use by default:

```sql
select psl.external_listing_id,
       psl.seller_name,
       psl.seller_country_code,
       psl.item_condition_code,
       psl.completeness_code,
       psl.quantity_available,
       psl.unit_price,
       psl.currency_code,
       psl.description
from pricing_snapshot ps
join pricing_snapshot_listing psl
  on psl.pricing_snapshot_id = ps.pricing_snapshot_id
where ps.pricing_snapshot_id = (
    select ps2.pricing_snapshot_id
    from pricing_snapshot ps2
    where ps2.marketplace_listing_id = 24
      and ps2.item_condition_code = 'N'
      and ps2.completeness_code = 'S'
    order by ps2.captured_at desc, ps2.pricing_snapshot_id desc
    limit 1
)
  and psl.item_condition_code = ps.item_condition_code
  and (
        psl.completeness_code = ps.completeness_code
        or (psl.completeness_code is null and ps.completeness_code is null)
      )
order by psl.unit_price, psl.pricing_snapshot_listing_id;
```

Check whether missing BrickLink internal ids were hydrated:

```sql
select eci.external_catalog_item_id,
       eci.external_item_key,
       eci.external_unique_key,
       eci.item_name
from external_catalog_item eci
where eci.external_service_id = 2
  and eci.external_catalog_item_id in (
      select distinct external_catalog_item_id
      from pricing_snapshot
  )
order by eci.external_item_key;
```

Review active BrickLink listings that still need BrickLink internal id hydration:

```sql
select ml.marketplace_listing_id,
       ml.external_listing_id,
       eci.external_catalog_item_id,
       eci.external_item_key,
       eci.external_unique_key,
       ii.item_inventory_id,
       ii.uuid,
       ii.new_or_used,
       ii.completeness
from marketplace_listing ml
join item_inventory ii
  on ii.item_inventory_id = ml.item_inventory_id
join external_catalog_item eci
  on eci.external_catalog_item_id = ml.external_catalog_item_id
where ml.listing_external_service_id = 2
  and ml.listing_status_code = 'ACTIVE'
  and (eci.external_unique_key is null or trim(eci.external_unique_key) = '')
order by ml.marketplace_listing_id
limit 100;
```

Review duplicate crawl work items per marketplace listing. This should normally be empty or explainable by historical runs:

```sql
select marketplace_listing_id,
       count(*) as work_item_count,
       sum(case when work_status_code = 'PENDING' then 1 else 0 end) as pending_count,
       group_concat(distinct work_status_code order by work_status_code separator ',') as work_status_codes,
       group_concat(pricing_crawl_work_item_id order by pricing_crawl_work_item_id separator ',') as pricing_crawl_work_item_ids
from pricing_crawl_work_item
group by marketplace_listing_id
having count(*) > 1
order by pending_count desc,
         work_item_count desc,
         marketplace_listing_id
limit 100;
```

### BrickLink Pricing Decision SQL Checks

Review recent pricing decision outcomes:

```sql
select decision_status_code,
       reason_code,
       count(*) as row_count,
       min(created_at) as first_created,
       max(created_at) as last_created
from pricing_decision
group by decision_status_code, reason_code
order by row_count desc, decision_status_code, reason_code;
```

Inspect latest decisions:

```sql
select pd.pricing_decision_id,
       pd.marketplace_listing_id,
       ml.external_listing_id,
       ii.uuid,
       eci.external_item_key,
       ii.new_or_used,
       ii.completeness,
       pd.algorithm_version,
       pd.decision_status_code,
       pd.reason_code,
       pd.strategy_code,
       pd.previous_price,
       pd.computed_price,
       pd.final_price,
       pd.currency_code,
       pd.comparable_count,
       pd.confidence,
       pd.created_at
from pricing_decision pd
join marketplace_listing ml
  on ml.marketplace_listing_id = pd.marketplace_listing_id
join item_inventory ii
  on ii.item_inventory_id = ml.item_inventory_id
left join external_catalog_item eci
  on eci.external_catalog_item_id = ml.external_catalog_item_id
order by pd.pricing_decision_id desc
limit 50;
```

Review latest decision state per active BrickLink listing. This is the preferred Phase 5 review query because `pricing_decision` is historical and can contain older failed rows for the same listing:

```sql
select ml.marketplace_listing_id,
       ml.external_listing_id,
       eci.external_item_key,
       eci.external_unique_key,
       ii.uuid,
       ii.new_or_used,
       ii.completeness,
       ml.unit_price as current_unit_price,
       ml.fixed_price,
       pd.pricing_decision_id,
       pd.decision_status_code,
       pd.reason_code,
       pd.computed_price,
       pd.final_price,
       pd.final_price - ml.unit_price as current_to_final_delta,
       pd.comparable_count,
       pd.confidence,
       pd.created_at as decision_created_at,
       ps.captured_at as snapshot_captured_at,
       ps.comparable_count as snapshot_comparable_count
from marketplace_listing ml
join item_inventory ii
  on ii.item_inventory_id = ml.item_inventory_id
left join external_catalog_item eci
  on eci.external_catalog_item_id = ml.external_catalog_item_id
left join (
    select pd.*
    from pricing_decision pd
    join (
        select marketplace_listing_id,
               max(pricing_decision_id) as pricing_decision_id
        from pricing_decision
        group by marketplace_listing_id
    ) latest
      on latest.pricing_decision_id = pd.pricing_decision_id
) pd
  on pd.marketplace_listing_id = ml.marketplace_listing_id
left join pricing_snapshot ps
  on ps.pricing_snapshot_id = pd.pricing_snapshot_id
where ml.listing_external_service_id = 2
  and ml.listing_status_code = 'ACTIVE'
order by ml.marketplace_listing_id
limit 100;
```

Summarize latest active-listing review state:

```sql
select coalesce(latest.decision_status_code, 'NO_DECISION') as decision_status_code,
       coalesce(latest.reason_code, 'NO_DECISION') as reason_code,
       count(*) as listing_count
from marketplace_listing ml
left join (
    select pd.*
    from pricing_decision pd
    join (
        select marketplace_listing_id,
               max(pricing_decision_id) as pricing_decision_id
        from pricing_decision
        group by marketplace_listing_id
    ) latest
      on latest.pricing_decision_id = pd.pricing_decision_id
) latest
  on latest.marketplace_listing_id = ml.marketplace_listing_id
where ml.listing_external_service_id = 2
  and ml.listing_status_code = 'ACTIVE'
group by coalesce(latest.decision_status_code, 'NO_DECISION'),
         coalesce(latest.reason_code, 'NO_DECISION')
order by listing_count desc;
```

Find active BrickLink pricing decision candidates with no pricing decision yet:

```sql
select ml.marketplace_listing_id,
       ml.external_listing_id,
       ii.uuid,
       eci.external_item_key,
       ii.new_or_used,
       ii.completeness,
       ml.unit_price,
       ml.fixed_price
from marketplace_listing ml
join item_inventory ii
  on ii.item_inventory_id = ml.item_inventory_id
left join external_catalog_item eci
  on eci.external_catalog_item_id = ml.external_catalog_item_id
where ml.listing_external_service_id = 2
  and ml.listing_status_code = 'ACTIVE'
  and ml.external_catalog_item_id is not null
  and (
      coalesce(ml.fixed_price, 0) = 1
      or (
          ii.new_or_used is not null
          and trim(ii.new_or_used) <> ''
          and ii.completeness is not null
          and trim(ii.completeness) <> ''
      )
  )
  and not exists (
      select 1
      from pricing_decision pd
      where pd.marketplace_listing_id = ml.marketplace_listing_id
  )
order by ml.marketplace_listing_id;
```

Find active BrickLink listings currently excluded from pricing decision candidate selection:

```sql
select ml.marketplace_listing_id,
       ml.external_listing_id,
       ii.uuid,
       eci.external_item_key,
       ii.new_or_used,
       ii.completeness,
       ml.unit_price,
       ml.fixed_price
from marketplace_listing ml
join item_inventory ii
  on ii.item_inventory_id = ml.item_inventory_id
left join external_catalog_item eci
  on eci.external_catalog_item_id = ml.external_catalog_item_id
where ml.listing_external_service_id = 2
  and ml.listing_status_code = 'ACTIVE'
  and coalesce(ml.fixed_price, 0) <> 1
  and (
      ml.external_catalog_item_id is null
      or ii.new_or_used is null
      or trim(ii.new_or_used) = ''
      or ii.completeness is null
      or trim(ii.completeness) = ''
  )
order by ml.marketplace_listing_id;
```

Review proposed decisions before any future apply phase:

```sql
select pd.pricing_decision_id,
       ml.marketplace_listing_id,
       ml.external_listing_id,
       eci.external_item_key,
       pd.previous_price,
       pd.computed_price,
       pd.final_price,
       pd.final_price - pd.previous_price as price_delta,
       pd.reason_code,
       pd.comparable_count,
       pd.confidence,
       pd.source_summary_json,
       pd.created_at
from pricing_decision pd
join marketplace_listing ml
  on ml.marketplace_listing_id = pd.marketplace_listing_id
left join external_catalog_item eci
  on eci.external_catalog_item_id = ml.external_catalog_item_id
where pd.decision_status_code = 'PROPOSED'
order by abs(pd.final_price - pd.previous_price) desc,
         pd.pricing_decision_id desc
limit 50;
```

Review the current persisted apply-readiness state per listing. This excludes readiness rows for superseded pricing decisions:

```sql
select par.readiness_status_code,
       par.block_reason_code,
       count(*) as listing_count
from pricing_apply_readiness par
join (
    select marketplace_listing_id,
           max(pricing_decision_id) as pricing_decision_id
    from pricing_decision
    group by marketplace_listing_id
) latest_decision
  on latest_decision.marketplace_listing_id = par.marketplace_listing_id
 and latest_decision.pricing_decision_id = par.pricing_decision_id
group by par.readiness_status_code,
         par.block_reason_code
order by par.readiness_status_code,
         par.block_reason_code;
```

Review latest persisted decisions that the dry-run apply selection skeleton would select. This still does not apply any prices and excludes readiness rows for superseded pricing decisions:

```sql
select par.pricing_apply_readiness_id,
       par.pricing_decision_id,
       ml.marketplace_listing_id,
       ml.external_listing_id,
       eci.external_item_key,
       par.current_price,
       par.proposed_price,
       par.delta_amount,
       par.delta_percent,
       par.minimum_required_delta,
       par.currency_code,
       par.confidence,
       par.comparable_count,
       par.evaluated_at
from pricing_apply_readiness par
join (
    select marketplace_listing_id,
           max(pricing_decision_id) as pricing_decision_id
    from pricing_decision
    group by marketplace_listing_id
) latest_decision
  on latest_decision.marketplace_listing_id = par.marketplace_listing_id
 and latest_decision.pricing_decision_id = par.pricing_decision_id
join marketplace_listing ml
  on ml.marketplace_listing_id = par.marketplace_listing_id
join pricing_decision pd
  on pd.pricing_decision_id = par.pricing_decision_id
left join external_catalog_item eci
  on eci.external_catalog_item_id = ml.external_catalog_item_id
where par.readiness_status_code = 'READY_TO_APPLY'
  and pd.applied_at is null
order by par.delta_amount desc,
         par.pricing_apply_readiness_id desc
limit 50;
```

Review latest decisions that the dry-run apply-readiness job would count as ready from source decision data. This excludes stale proposals superseded by newer decisions and still does not apply any prices:

```sql
select pd.pricing_decision_id,
       ml.marketplace_listing_id,
       ml.external_listing_id,
       eci.external_item_key,
       ml.unit_price as current_unit_price,
       ml.currency_code as current_currency_code,
       pd.previous_price,
       pd.computed_price,
       pd.final_price,
       pd.currency_code as decision_currency_code,
       pd.final_price - ml.unit_price as current_to_final_delta,
       pd.reason_code,
       pd.comparable_count,
       pd.confidence,
       pd.created_at
from pricing_decision pd
join (
    select marketplace_listing_id,
           max(pricing_decision_id) as pricing_decision_id
    from pricing_decision
    group by marketplace_listing_id
) latest
  on latest.pricing_decision_id = pd.pricing_decision_id
join marketplace_listing ml
  on ml.marketplace_listing_id = pd.marketplace_listing_id
left join external_catalog_item eci
  on eci.external_catalog_item_id = ml.external_catalog_item_id
where ml.listing_external_service_id = 2
  and ml.listing_status_code = 'ACTIVE'
  and pd.decision_status_code = 'PROPOSED'
  and pd.applied_at is null
  and ml.unit_price is not null
  and pd.final_price is not null
  and coalesce(ml.fixed_price, 0) <> 1
  and coalesce(upper(trim(ml.currency_code)), 'USD') = coalesce(upper(trim(pd.currency_code)), 'USD')
  and pd.reason_code in (
      'SINGLE_COMPARABLE_DISCOUNTED',
      'TWO_COMPARABLES_WEIGHTED',
      'MEAN_PLUS_STDDEV',
      'MATCHED_LOWEST_COMPETITOR',
      'BELOW_MIN_PRICE_CLAMPED',
      'ABOVE_MAX_PRICE_CLAMPED'
  )
  and pd.reason_code not in (
      'NO_CURRENT_COMPARABLES',
      'NO_EXACT_COMPARABLES',
      'NO_CURRENT_SNAPSHOT'
  )
  and abs(pd.final_price - ml.unit_price) >= 0.01
  and coalesce(pd.confidence, 0) >= 0.00
  and coalesce(pd.comparable_count, 0) >= 1
order by abs(pd.final_price - ml.unit_price) desc,
         pd.pricing_decision_id desc
limit 100;
```

Review failed or skipped decisions:

```sql
select pd.pricing_decision_id,
       pd.marketplace_listing_id,
       ml.external_listing_id,
       eci.external_item_key,
       pd.decision_status_code,
       pd.reason_code,
       pd.notes,
       pd.created_at
from pricing_decision pd
join marketplace_listing ml
  on ml.marketplace_listing_id = pd.marketplace_listing_id
left join external_catalog_item eci
  on eci.external_catalog_item_id = ml.external_catalog_item_id
where pd.decision_status_code in ('FAILED', 'SKIPPED')
order by pd.pricing_decision_id desc
limit 50;
```

Review stale historical decisions that have been superseded by a newer decision for the same listing:

```sql
select pd.pricing_decision_id,
       pd.marketplace_listing_id,
       ml.external_listing_id,
       eci.external_item_key,
       pd.decision_status_code,
       pd.reason_code,
       pd.created_at
from pricing_decision pd
join marketplace_listing ml
  on ml.marketplace_listing_id = pd.marketplace_listing_id
left join external_catalog_item eci
  on eci.external_catalog_item_id = ml.external_catalog_item_id
where exists (
    select 1
    from pricing_decision newer
    where newer.marketplace_listing_id = pd.marketplace_listing_id
      and newer.pricing_decision_id > pd.pricing_decision_id
)
order by pd.marketplace_listing_id,
         pd.pricing_decision_id;
```

Do not delete historical `pricing_decision` rows during Phase 5. They are an audit trail and are useful for explaining why earlier runs failed, for example before inventory condition/completeness was backfilled. Use latest-decision queries for review. If storage cleanup is ever needed later, add an archival policy rather than ad hoc deletes.

### BrickLink Order Sync SQL Checks

After `apply=true`, useful checks:

```sql
select *
from marketplace_order_sync_run
order by marketplace_order_sync_run_id desc
limit 10;

select marketplace_code, external_order_id, order_status, order_direction, ordered_at, grand_total_amount, last_seen_at
from marketplace_order
where marketplace_code = 'BRICKLINK'
order by last_seen_at desc
limit 25;

select oi.*
from marketplace_order_item oi
join marketplace_order o on o.marketplace_order_id = oi.marketplace_order_id
where o.marketplace_code = 'BRICKLINK'
order by oi.marketplace_order_id desc, oi.marketplace_order_item_id;

select o.marketplace_code, o.external_order_id, p.payload_type_code, p.payload_hash, p.captured_at
from marketplace_order_payload p
join marketplace_order o
  on o.marketplace_order_id = p.marketplace_order_id
where o.marketplace_code = 'BRICKLINK'
order by p.marketplace_order_payload_id desc
limit 25;
```

### Fulfillment Sync SQL Checks

Fulfillment does not currently write new local fulfillment tables. It reads staged marketplace order data and writes to ShipStation and, for shipped reconciliation, BrickLink. These checks validate whether local staged data is ready for fulfillment:

```sql
select marketplace_order_id, marketplace_code, external_order_id, external_status_code, last_seen_at
from marketplace_order
where marketplace_code = 'BRICKLINK'
  and external_status_code in ('PENDING', 'UPDATED', 'READY', 'PROCESSING', 'PAID', 'PACKED')
order by last_seen_at desc
limit 25;

select o.external_order_id,
       count(*) as item_count,
       sum(case when oi.marketplace_listing_id is null then 1 else 0 end) as missing_listing_link_count,
       sum(case when oi.item_inventory_id is null then 1 else 0 end) as missing_inventory_link_count
from marketplace_order o
join marketplace_order_item oi
  on oi.marketplace_order_id = o.marketplace_order_id
where o.marketplace_code = 'BRICKLINK'
group by o.external_order_id
order by o.external_order_id desc;

select o.external_order_id,
       p.payload_type_code,
       p.payload_hash,
       p.captured_at
from marketplace_order o
join marketplace_order_payload p
  on p.marketplace_order_id = o.marketplace_order_id
where o.marketplace_code = 'BRICKLINK'
  and p.payload_type_code in ('ORDER_RESPONSE', 'ORDER_ITEMS_RESPONSE')
order by p.marketplace_order_payload_id desc
limit 50;

select oi.external_order_item_id,
       oi.item_inventory_id,
       iip.item_inventory_photo_id,
       iip.primary,
       ei.image_url
from marketplace_order_item oi
left join item_inventory_photo iip
  on iip.item_inventory_id = oi.item_inventory_id
left join external_image ei
  on ei.item_inventory_photo_id = iip.item_inventory_photo_id
 and ei.external_service_id = 10
where oi.marketplace_order_id = 100
order by oi.marketplace_order_item_id, iip.primary desc, iip.item_inventory_photo_id;
```

### Fulfillment SQL Checks

Before enabling fulfillment apply mode, confirm candidates have staged raw payloads:

```sql
select o.marketplace_order_id,
       o.external_order_id,
       o.external_status_code,
       count(p.marketplace_order_payload_id) as payload_count
from marketplace_order o
left join marketplace_order_payload p
  on p.marketplace_order_id = o.marketplace_order_id
 and p.payload_type in ('ORDER_RESPONSE', 'ORDER_ITEMS_RESPONSE')
where o.marketplace_code = 'BRICKLINK'
  and o.external_status_code in ('PENDING', 'UPDATED', 'READY', 'PROCESSING', 'PAID', 'PACKED')
group by o.marketplace_order_id, o.external_order_id, o.external_status_code
order by o.last_seen_at desc;
```

After shipped reconciliation, local staged status is updated so the same order is not repeatedly reconciled before the next BrickLink probe:

```sql
select marketplace_order_id,
       external_order_id,
       external_status_code,
       tracking_present,
       status_changed_at,
       last_seen_at
from marketplace_order
where marketplace_code = 'BRICKLINK'
order by last_seen_at desc
limit 25;
```

### Image-Hosting SQL Checks

```sql
select *
from external_image_album
where item_inventory_id = 100
  and external_service_id = 10;

select *
from external_image
where external_service_id = 10
  and item_inventory_photo_id in (
      select item_inventory_photo_id
      from item_inventory_photo
      where item_inventory_id = 100
  );

select ai.*
from external_image_album_image ai
join external_image_album a
  on ai.external_image_album_id = a.external_image_album_id
where a.item_inventory_id = 100
  and a.external_service_id = 10
order by ai.sort_order;
```

### Common Failure Modes

| Symptom | Likely cause | Check/fix |
| --- | --- | --- |
| Application fails because config import is missing | Required external file absent under `import-path` | Create/project the expected YAML file or adjust active profiles. |
| BrickLink responses are `302` to error page | Wrong BrickLink base URI | Use `https://api.bricklink.com/api/store/v1`. |
| BrickLink sync discovers orders but writes nothing | `lego.bricklink.orders.sync.scheduled.apply=false` | This is probe mode. Set apply true only after DB tables exist and probe output is reviewed. |
| Pricing crawl job does not start | `lego.bricklink.pricing.crawl.enabled=false` or `lego.bricklink.pricing.crawl.scheduled.enabled=false` | Set both properties true for scheduled runs. |
| Pricing crawl schedules work but makes no AJAX calls | Newly created work has `next_attempt_at` in the future or no pending work is currently due | Check `pricing_crawl_work_item.next_attempt_at`, `schedule-spread-window`, blackout settings, and `worker-batch-size`. |
| Pricing crawl repeatedly re-crawls the same listings | `crawl-cadence` is too short or terminal work rows have past `next_attempt_at` values | Increase `crawl-cadence` and check recent `pricing_crawl_work_item` rows for the listing. |
| Pricing crawl leaves rows in `CLAIMED` | JVM stopped or failed after claim and before completion | Rows older than `claim-stale-after` are requeued on a later run if attempts remain. |
| Pricing crawl writes many `SKIPPED_MISSING_CONDITION` work items | `item_inventory.new_or_used` is null or not `N`/`NEW`/`U`/`USED` | Fix inventory condition data before crawling that listing. |
| Pricing crawl writes `FAILED_ITEM_ID_LOOKUP_NO_MATCH` | BrickLink `searchproduct.ajax` could not match `external_catalog_item.external_item_key` for the selected item type | Verify `external_catalog_item.external_item_key`, `external_catalog_item.item_type_code`, and the configured fallback `catalog-item-type` for rows missing an item type. |
| Pricing crawl writes snapshots but no listings | BrickLink returned zero comparable listings for that item/condition | Check `pricing_snapshot.comparable_count`, request parameters, and BrickLink site manually if needed. This can be valid sparse-market behavior. |
| Pricing crawl sees `catalogifs.ajax ... returned []` | BrickLink returned an empty AJAX array for the pricing request | The crawler treats this as a successful zero-comparable snapshot instead of an HTTP failure. Review `zeroComparableSnapshotsWritten` and latest `pricing_snapshot.comparable_count=0` rows. |
| Pricing crawl returns New/Complete rows for a New/Sealed inventory item | BrickLink pricing AJAX filters by condition only, not completeness | This is expected. Phase 3 pricing should query exact comparables by matching snapshot/listing condition and completeness. |
| Pricing decision job does not start | `lego.bricklink.pricing.decision.enabled=false` or `lego.bricklink.pricing.decision.scheduled.enabled=false` | Set both properties true for scheduled runs. |
| Pricing decision job reports `NO_WORK` even though active BrickLink listings exist | No active listings currently meet decision-candidate eligibility | Check for missing `external_catalog_item_id`, missing inventory condition/completeness, or non-fixed legacy listings excluded by the candidate query. |
| Pricing decisions are `FAILED` with `NO_CURRENT_SNAPSHOT` | No crawl snapshot exists for that listing and exact condition/completeness | Run the pricing crawl first and confirm normalized `pricing_snapshot.item_condition_code` and `completeness_code`. |
| Pricing decisions are `FAILED` with `NO_CURRENT_COMPARABLES` | Latest matching crawl snapshot exists and has `comparable_count=0` | This is not a crawl timing issue. Review manually, wait for future comparables, or add a later fallback strategy. |
| Pricing decisions are `FAILED` with `NO_EXACT_COMPARABLES` | Snapshot exists, but no returned rows match condition/completeness after excluding your own listing | Check latest exact comparable SQL; this can be valid sparse-market behavior. |
| Pricing decisions are `SKIPPED` with `FIXED_PRICE_OVERRIDE` | `marketplace_listing.fixed_price=true` | Expected when the listing price is intentionally fixed. |
| Pricing decisions are clamped | `minimum-price` or `maximum-price` is configured | Review global clamp properties and `source_summary_json` for the original algorithm branch. |
| Pricing decision final price differs greatly from current price | Competitive algorithm found a large market delta or stale listing price | Review exact comparable rows, comparable count, confidence, and reason code before any future apply phase. |
| Pricing apply-readiness job reports `NO_WORK` | No latest, unapplied `PROPOSED` decisions match the configured marketplace service/status | Run the decision job first and inspect latest decision state per active listing. |
| Pricing apply-readiness job reports `NO_READY_DECISIONS` | Selected proposed decisions were skipped by fixed-price, missing-price, currency, reason-code, confidence, comparable-count, stale-snapshot, absolute movement, percent movement, or max movement guards | Review `BricklinkPricingApplyReadinessResult` counters and the persisted `pricing_apply_readiness` latest-state query. |
| BrickLink AJAX calls start failing after a fast test run | BrickLink may be throttling or temporarily banning the external IP | Stop the scheduled job, keep `bricklink.ajax.rate-limit.enabled=true`, keep `minimum-delay-ms >= 2000`, and wait before retrying. |
| Fulfillment sync maps orders but creates no ShipStation orders | `lego.fulfillment.sync.scheduled.apply=false`, or a non-production runtime (`lego.environment.production=false`) | This is dry-run mapping mode, and it is expected in local/sandbox/dev. In PROD, set apply true only after staged payloads and credentials are verified. |
| `fulfillment.sync_job.apply_suppressed` warning every run | Fulfillment apply requested in a non-production runtime | Expected safety behavior. Set `lego.fulfillment.sync.scheduled.apply=false` in that profile. Never set `lego.environment.production=true` outside PROD. |
| Fulfillment sync reports `PAYLOADS_MISSING` | BrickLink order sync has not stored latest `ORDER_RESPONSE` and `ORDER_ITEMS_RESPONSE` payloads for candidates | Run BrickLink order sync with `apply=true` first and confirm `marketplace_order_payload` rows exist. |
| Fulfillment candidate fails with duplicate ShipStation orders | More than one ShipStation order exists for the same `BL-{orderId}` order number | Resolve the duplicate in ShipStation before re-running live fulfillment apply. |
| Shipped ShipStation order does not update BrickLink | ShipStation shipment has no non-voided tracking number | Add/verify tracking in ShipStation, then let the next fulfillment run reconcile it. |
| ShipStation client fails authentication | Missing or wrong `shipstation.rest.api-key` or `shipstation.rest.api-secret` | Check the projected secret or environment variables supplying ShipStation REST config. |
| Image-hosting scheduled job logs planned actions but no writes | `lego.image-hosting.sync.scheduled.apply=false` | This is dry-run scheduled mode. |
| Kubernetes readiness DOWN for image hosting | Missing required DB/S3/Flickr/Bitly config or invalid scheduled settings | Inspect `/actuator/health/readiness` details and startup readiness logs. |
| Kafka listener not consuming | Topic id missing consumer config or wrong group/topic | Check `kafka.topic-configuration.<id>.*` and dynamic bean registration logs. |
| Kafka JSON deserialization failure | Missing/wrong `spring-json-value-default-type` or trusted packages | Check per-topic consumer properties and `kafka.consumer-defaults.properties.spring.json.trusted.packages`. |
| Photo delete throughput setting ignored in YAML | Listener concurrency is hard-coded to `6` | Code change required to make it configurable. |

## Safety Rules

- Keep scheduled `apply=false` during first deployments or after large refactors.
- Do not enable BrickLink HTTP body logging in long-running production-like deployments unless needed for diagnosis.
- Do not add high-cardinality metric tags such as order ids, photo ids, album ids, UUIDs, file names, or exception messages.
- Treat all `/internal/*` endpoints as administrative operations. They should remain behind trusted network/access controls.
- Prefer sync-plan and repair-plan endpoints over the legacy direct sync endpoint for manual image-hosting operations.
- Before enabling write-side marketplace sync, confirm the marketplace sync tables and constraints exist in the target database.
- Only PROD may write to real orders (ShipStation, BrickLink order status, Drive Thru). Never set `lego.environment.production=true` outside `application-prod.yml`.
- Before enabling fulfillment apply mode, confirm BrickLink order sync has written current staged payloads and start with a small fulfillment batch size.
- Do not use fulfillment sync as the source of local accounting truth yet; transaction finalization, payment/cost/shipping rows, and marketplace order transaction links remain deferred.

## Context consolidation: inventory intake and initial-price operations

The following checkpoints capture the end-to-end behavior verified while exercising the
inventory intake and Pricing Plane workflow. They are deliberately kept here with the
ingress operational runbook because the service creates local state while ingress owns
the scheduled and remote side effects.

### Service-to-ingress contract

`lego-data-service` is responsible for:

```text
POST /api/v1/inventory
    -> item_inventory and primary BrickLink catalog link
PATCH .../sale-intent
    -> explicit SELLABLE inventory intent
POST /api/v1/marketplace-listings
    -> local DRAFT and BrickLink draft details
GET .../marketplace-readiness
    -> local blockers and warnings
POST .../sync-requests
    -> one durable LISTING_CREATE request for one listing
```

Ingress is responsible for:

```text
pricing_crawl_work_item
    -> BrickLink AJAX lookup/hydration
    -> pricing_snapshot + pricing_snapshot_listing
    -> pricing_decision
    -> pricing_apply_readiness
    -> local unit_price apply
    -> LISTING_CREATE or PRICE_UPDATE queue consumption
    -> BrickLink REST call and remote read-back safety
```

The service endpoint is intentionally one listing at a time. It does not accept an
array of inventory IDs. Batch operators must enumerate IDs, run readiness/preview per
listing, and create only the requests whose individual blockers are empty.

### Initial-price state names and handoff

The current service blocker is `INITIAL_PRICE_PENDING`. This is the implemented name;
`INITIAL_FIXED_PRICE_PENDING` was discussed but is not a current status. It means a
non-fixed local `DRAFT` has `unit_price=NULL` and is waiting for the Pricing Plane. It
does not mean that the crawl failed.

The current ingress readiness/apply chain is:

```text
pricing_decision.status_code = PROPOSED
        |
        +--> pricing_apply_readiness.readiness_status_code
                = READY_TO_APPLY_INITIAL_PRICE
        |
        +--> apply rechecks unpriced DRAFT + no remote inventory id
        |
        +--> marketplace_listing.unit_price becomes positive
        +--> LISTING_CREATE request is queued
        +--> marketplace sync creates and verifies BrickLink inventory
```

`pricing_crawl_work_item.work_status_code=SUCCEEDED` proves only that source market
data was captured. It does not prove that a pricing decision exists, that the decision
passed readiness, that local price apply ran, or that BrickLink was called. Always
inspect the latest row in each stage when a listing still shows an old manual price.

### Catalog internal-ID hydration

The catalog mapping contains both the user-facing BrickLink item number
(`external_item_key`) and BrickLink's internal `idItem` (`external_unique_key`). When
the internal key is null, the pricing crawler can call BrickLink
`searchproduct.ajax`, using the catalog item number and the catalog row's item type,
then persist the resolved key. The catalog row's type is preferred; the configured
`catalog-item-type` is only a fallback when the row type is blank.

Use the following query before diagnosing a crawl:

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

A null internal key is a recoverable hydration state, not permission to invent an ID.
If hydration fails, use the work-item status/error (`NO_MATCH`, `AMBIGUOUS`, HTTP
error, or parse error) and the item type to determine the next action.

### Database preflight for unpriced drafts

The algorithmic onboarding path requires the database column
`marketplace_listing.unit_price` to allow null. Verify the live database rather than
assuming that a checked-in migration has been deployed:

```sql
select table_name, column_name, is_nullable, column_type
from information_schema.columns
where table_schema = database()
  and table_name = 'marketplace_listing'
  and column_name = 'unit_price';
```

If this returns `NO`, the draft insert can fail with `Column 'unit_price' cannot be
null`. Apply the database deployment/migration and verify again. Do not seed a fake
price; doing so defeats the initial-price workflow.

### Current sandbox execution checkpoint

The recent sandbox redeploy branch was intentionally used to run the complete path:

| Stage | Effective sandbox intent |
| --- | --- |
| Crawl | enabled; eligible statuses include `ACTIVE` and `DRAFT`; initial delay 30 seconds; fixed delay 10 minutes |
| Decision | enabled; current snapshot required; initial delay 120 seconds; fixed delay 10 minutes |
| Apply-readiness | enabled; initial delay 180 seconds; fixed delay 10 minutes |
| Apply | `APPLY_LOCAL_AND_ENQUEUE_SYNC`; initial delay 240 seconds; fixed delay 10 minutes |
| Marketplace sync | `APPLY`; `production=false`; stockroom-only; initial delay 300 seconds; fixed delay 10 minutes |

The redeploy branch used stockroom `C` for sandbox. The checked-in `develop` profile
and the running Kubernetes ConfigMap can differ from a force-redeploy branch, so verify
the bound profile and pod environment before relying on this table. Local remains
effectively `DRY_RUN`; sandbox/dev are real BrickLink account operations constrained to
non-public stockroom inventory and managed system remarks.

### Remote metadata behavior

Before the first create, a local PATCH to the listing description or
`bricklink.remarks` is included in the `LISTING_CREATE` payload. After the listing is
`ACTIVE`, the same service PATCH changes local state only. The current pricing
`PRICE_UPDATE` path can update price and preserve/repair the managed system remarks
block, but it does not propagate a local description, title, private note, or new human
remarks value. There is no current description-only or remarks-only sync request type.

Never hand-author the `[SYSTEM_BEGIN] ... [SYSTEM_END]` remarks block, and never change
an active listing back to `DRAFT` to force a create; both actions bypass remote ownership
safety.

### Safe retry after a sync-request HTTP 500

The body-less service call is supported:

```http
POST /api/v1/marketplace-listings/{marketplaceListingId}/sync-requests
Content-Type: application/json

{}
```

An older null-safe logging defect could return HTTP 500 after the local transaction had
already inserted the request. Before retrying, query the listing's pending/claimed
requests:

```sql
select marketplace_listing_sync_request_id,
       sync_request_type_code,
       sync_request_status_code,
       attempt_count,
       next_attempt_at,
       last_error_message
from marketplace_listing_sync_request
where marketplace_listing_id = <marketplace_listing_id>
order by marketplace_listing_sync_request_id desc;
```

If a request exists, do not create a duplicate. Inspect ingress logs and let the
existing request proceed or cancel it only when the service rules allow cancellation.

### Kubernetes and GitHub Actions monitoring

For a sandbox rollout triggered by a feature-branch push, verify the GitHub Action first,
then inspect the application deployment:

```text
GitHub Action build/deploy completed
    -> lego-data-ingress pod rolled out
    -> scheduled-job startup logs appear
    -> pricing/sync logs show the listing ID
    -> database rows advance through each stage
    -> BrickLink account shows stockroom inventory after remote read-back
```

If an action does not start, inspect ARC runner pod status, labels, image-pull events,
and runner logs before debugging application code. If the action completes but no job
logs appear, inspect the ingress deployment, active profile/configuration, scheduled
flags, and pod restart/readiness events. Use the stage-specific metrics and the SQL
queries in this runbook rather than treating a single successful crawl log as proof of
end-to-end completion.
