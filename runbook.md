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
| Photo ingestion | Kafka/S3 event driven | Process uploaded S3 photo objects into durable DB photo metadata and S3 state. |
| Photo delete reconciliation | Kafka/S3 event driven | React to final photo object removals. |
| BrickLink catalog ingestion | Kafka/S3 event driven | Parse BrickLink XML catalog/category files and upsert external catalog/category tables. |
| Rebrickable catalog ingestion | Kafka/S3 event driven | Parse Rebrickable gzipped CSV catalog/theme files and upsert external catalog/category tables. |
| Image-hosting sync | REST and scheduled job | Reconcile item inventory photos and albums to Flickr through `lego-imaging`. |
| Image-hosting repair | REST | Repair DB links from current remote image-hosting state. |
| BrickLink want list | Scheduled job (every environment) | Keep this environment's rows in the shared `marketplace_pricing.want_list_item`: what it is pricing, by catalog item, condition and completeness. |
| BrickLink pricing crawl | Scheduled job (one deployment: SANDBOX) | The shared crawler: crawl the combined want list once for every environment and store market snapshots and lots in `marketplace_pricing`. |
| BrickLink pricing decision | Scheduled job | Read the latest shared BrickLink market snapshots, compute competitive prices for active listings and unpriced drafts, and persist auditable non-applied pricing decisions. |
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
| `lego.s3.url` | `https://api.minio.dev.internal.legohunter.io` | S3 URL for local runs (minio-dev until silo-dev replaces it). |

### Sandbox Profile

`application-sandbox.yml` imports these external files from `${import-path}`:

| File                            | Required for | Typical contents |
|---------------------------------| --- | --- |
| `database-configuration.yml`    | DB access | `lego.data`, `lego.databases`, datasource-related settings. |
| `kafka-configuration.yml`       | Kafka auth | `lego.kafka.consumer.username/password`, `lego.kafka.producer.username/password`. |
| `s3-configuration.yml`          | S3 access | `lego.s3.access-key`, `lego.s3.secret-key`. |
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

The S3 URL is not in the shared `kubernetes` profile. Each environment profile sets its own
`lego.s3.url`: SANDBOX and DEV use `http://minio.minio-dev.svc.cluster.local` until silo-dev replaces
minio-dev, and PROD uses `http://silo-prod.silo-prod.svc.cluster.local:9000`.

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

Class: `io.legohunter.ingress.orders.BricklinkOpenOrderProbeJob`

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

### `BricklinkWantListSyncJob`

Class: `io.legohunter.ingress.pricing.BricklinkWantListSyncJob` (HOMELAB-32)

Runs in **every** environment that prices listings (SANDBOX and DEV today). It tells the shared crawler what this environment is selling by keeping its own rows in `marketplace_pricing.want_list_item`.

```yaml
lego.bricklink.pricing.want-list.enabled: true
lego.bricklink.pricing.want-list.scheduled.enabled: true
```

| Property | Default | Meaning |
| --- | --- | --- |
| `lego.bricklink.pricing.want-list.environment-code` | `${lego.environment.code}` | The environment code its rows carry. It only ever writes and prunes rows with this code. |
| `lego.bricklink.pricing.want-list.priceable-listing-status-codes` | `ACTIVE,DRAFT` | Listings in these statuses are wanted. Same selection as the decision. |
| `lego.bricklink.pricing.want-list.prune-after` | `2d` | Rows this environment has not confirmed for this long are deleted: it no longer sells the item. |
| `lego.bricklink.pricing.want-list.scheduled.fixed-delay-ms` | `3600000` | Hourly. |

Each run selects the environment's priceable BrickLink listings (recognised New/Used condition, a completeness, a catalog item with type and number), groups them by catalog item + canonical condition (`N`/`U`) + canonical completeness (`S`/`C`/`B`/`X`), creates the shared `canonical_item` (BrickLink type + number) if this environment is the first to want it, upserts the `want_list_item` row, and prunes stale rows. Logs `bricklink.pricing.want_list.job.completed` with confirmed / added / skipped / pruned counts; metric `bricklink_pricing_want_list_item_total{result}`.

### `BricklinkPricingCrawlJob`: the shared BrickLink crawler

Class: `io.legohunter.ingress.pricing.BricklinkPricingCrawlJob` (HOMELAB-32)

**Exactly one deployment runs it.** It crawls BrickLink once for every environment and writes to the shared `marketplace_pricing` schema; each environment reads that schema for its own decisions. Until PROD exists the SANDBOX deployment is the crawler. Every other profile sets `crawl.enabled: false` (DEV and PROD explicitly; `local` too).

```yaml
lego.bricklink.pricing.crawl.enabled: true          # SANDBOX only
lego.bricklink.pricing.crawl.scheduled.enabled: true
```

To test crawler changes from a feature branch, never point them at the real schema: set `lego.marketplace-pricing.schema=marketplace_pricing_scratch`, enable the crawl, and use a long `scheduled.fixed-delay-ms` so only a handful of items are fetched.

| Property | Default | Meaning |
| --- | --- | --- |
| `lego.bricklink.pricing.crawl.worker-batch-size` | `1` | Due targets claimed and fetched per run. Keep small for live BrickLink calls. |
| `lego.bricklink.pricing.crawl.results-per-page` | `500` | `catalogifs.ajax` `rpp`. |
| `lego.bricklink.pricing.crawl.crawl-cadence` | `7d` | After a success the target is due again this much later. Also the wait after `max-attempts` failures in a row, and the minimum gap when a disabled target is wanted again. |
| `lego.bricklink.pricing.crawl.schedule-spread-window` | `72h` | New targets are spread across this window. |
| `lego.bricklink.pricing.crawl.schedule-jitter` | `0s` | Optional random jitter on new targets. |
| `lego.bricklink.pricing.crawl.retry-backoff` | `6h` | Wait after a failed fetch. |
| `lego.bricklink.pricing.crawl.max-attempts` | `3` | After this many failures in a row, wait a full cadence instead of the backoff. |
| `lego.bricklink.pricing.crawl.claim-stale-after` | `2h` | A claim expires after this long, so a crashed crawler's target is taken over. |
| `lego.bricklink.pricing.crawl.claimed-by` | pod host name | Stored in `crawl_target.claimed_by`. |
| `lego.bricklink.pricing.crawl.blackout.*` | weekdays 21:30–08:30 America/New_York (SANDBOX) | No fetches inside the window; due times are moved to its end. |

Each run:

| Step | Behavior |
| --- | --- |
| New wants | Every item + `N`/`U` pair in any environment's want list without a `BRICKLINK` target gets one `crawl_target` (IDLE), due times spread over the window. One target serves every sub-condition: one fetch returns Sealed, Complete and Incomplete lots. |
| Wanted again | DISABLED targets someone wants again become IDLE, due at max(now, last success + cadence). |
| No longer wanted | IDLE targets no environment wants become DISABLED. Their history stays. |
| Blackout | Inside the blackout window the run stops here (outcome `BLACKOUT`). |
| Claim | Due targets (IDLE and due, or CLAIMED with an expired claim) are claimed with a conditional update, so two crawlers never fetch the same target. |
| Resolve `idItem` | Read from `marketplace_item.marketplace_unique_key`; looked up once with `searchproduct.ajax` and stored when missing. |
| Fetch | `catalogifs.ajax` for the item and condition. An exception (maintenance redirect, block, timeout) is a failure; an empty list is a successful, empty market. |
| Store | One `market_snapshot` per success (the price history) and one `market_listing` per lot: raw and canonical condition/completeness, display price + currency, the seller's native price, seller key/store/country/feedback, raw payload. |
| Log | Every attempt is a `crawl_attempt` row (`SUCCEEDED`, `FAILED_HTTP`, `FAILED_PARSE`, `FAILED_RESOLVE_NO_MATCH`, `FAILED_RESOLVE_AMBIGUOUS`, `FAILED_MISSING_ITEM`). |
| Release | Success: IDLE, failures reset, due after the cadence. Failure: IDLE, `consecutive_failure_count` + 1, due after the backoff (or the cadence after `max-attempts`). |

Outcomes: `NO_WORK`, `SCHEDULED` (queue changed, nothing due), `BLACKOUT`, `SUCCESS`, `PARTIAL_SUCCESS` (at least one fetch failed).

Monitoring (a ban or block shows as failures rising while successes stay flat):

| Metric | Meaning |
| --- | --- |
| `bricklink_pricing_crawl_job_total{outcome}` | Runs by outcome. |
| `bricklink_pricing_crawl_fetch_total{result}` | `succeeded`, `empty`, `failed` fetches. |
| `bricklink_pricing_crawl_target_total{result}` | `created`, `reenabled`, `disabled`, `claimed` targets. |
| `bricklink_pricing_crawl_listing_total{result="written"}` | Lots stored. |
| `bricklink_pricing_crawl_target_current{state}` | Queue now: `idle`, `claimed`, `disabled`, `due`, `failing`. Shared, so every deployment reports the same numbers. |

Logs: `bricklink.pricing.crawl.job.completed` (the run result), `bricklink.pricing.crawl.failed` (one per failed fetch, with the target, outcome, failure count and message). The maintenance report (`GET /internal/bricklink/pricing/maintenance-report`) shows the queue by state and the latest failed attempts.

Boundaries:

| Boundary | Detail |
| --- | --- |
| Crawl only captures source data | Prices are computed by `BricklinkPricingDecisionJob` in each environment; the crawler never changes a listing. |
| Shared, append-only history | `market_snapshot` and `market_listing` are never updated. SANDBOX's earlier `pricing_snapshot*` history was copied in once (HOMELAB-31). The old `pricing_crawl_work_item` / `pricing_snapshot*` tables are no longer written; dropping them is a later cleanup. |
| AJAX rate limit protection | Outbound AJAX calls are protected by `bricklink-ajax` rate limiting. Keep it enabled for live runs. |

### Pricing Plane Table And Timing Mental Model

The Pricing Plane has two separate responsibilities:

1. Crawling captures source data from BrickLink and stores immutable pricing history.
2. Decisioning reads stored crawl data and writes read-only pricing recommendations.

The decision job does not call BrickLink. It only reads data already persisted by the crawl job.

Table responsibilities:

| Table | Mental model | Primary question it answers |
| --- | --- | --- |
| `marketplace_pricing.crawl_target` | Shared crawl queue (HOMELAB-32). One stable row per marketplace, item and New/Used condition, updated in place. | What needs to be crawled, when can it run, and what happened last time? |
| `marketplace_pricing.market_snapshot` | Immutable crawl header. One row means "at this time, BrickLink lots were captured for this item and condition", for every environment. | What was the latest successful capture for this item and condition? |
| `marketplace_pricing.market_listing` | Immutable lots inside a snapshot, every sub-condition. The decision keeps those matching the listing's condition and completeness. | Which competitor lots and prices were available in that capture? |
| `pricing_decision` | Historical pricing calculation output. One row means "at this time, the algorithm evaluated this listing and wrote a proposed, skipped, or failed decision." | What did the pricing algorithm decide, and why? |

The normal flow (HOMELAB-32):

```text
each environment: priceable marketplace_listing rows
        |
        | BricklinkWantListSyncJob (every environment)
        v
marketplace_pricing.want_list_item   (environment + canonical item + N/U + completeness)
        |
        | BricklinkPricingCrawlJob (the one shared crawler, SANDBOX)
        v
marketplace_pricing.crawl_target     (one per item + N/U, due by next_due_at)
        |
        | BrickLink AJAX crawl, logged in crawl_attempt
        v
marketplace_pricing.market_snapshot  -> market_listing rows (every sub-condition)
        |
        | BricklinkPricingDecisionJob (each environment, its own listings)
        v
pricing_decision                     (market_snapshot_id + market_snapshot_captured_at)
```

`crawl_target` controls pacing. The crawler only fetches targets that are due (`IDLE` and `next_due_at <= now`, or `CLAIMED` with an expired claim), at most `worker-batch-size` per run, never inside the blackout window. New targets are spread across `schedule-spread-window`. The `bricklink-ajax` client still enforces the process-local minimum delay between AJAX request starts.

Current snapshot:

| Term | Meaning |
| --- | --- |
| Current snapshot | The newest `market_snapshot` (`BRICKLINK`) for the listing's canonical item (catalog item type + number) and normalized New/Used condition. The decision then keeps only the lots whose canonical condition and completeness match the listing's. |
| Not available | No snapshot exists yet for that item and condition: nobody has wanted it long enough for the crawler to reach it. |
| Zero-comparable snapshot | A snapshot exists with `listing_count = 0`: BrickLink had no lots for sale in that condition. Different from no snapshot. |
| Historical snapshot | Any older `market_snapshot` for the same item and condition. Kept: it is the price history. |

There is no mutable "current snapshot" table. "Current" is a query-time concept: the latest snapshot by `captured_at` and `market_snapshot_id`.

Example:

| Owned listing state | Snapshot and lots the decision uses |
| --- | --- |
| `new_or_used = U`, `completeness = C`, catalog item `S 6390-1` | latest `BRICKLINK` snapshot for canonical item `S 6390-1` and `U`; lots with `condition_code = 'U'` and `completeness_code = 'C'` |
| `new_or_used = N`, `completeness = S`, catalog item `S 6390-1` | latest snapshot for `S 6390-1` and `N`; lots with `N` / `S`. The same snapshot also serves an `N` / `C` listing. |

Why a snapshot may not be available:

| Cause | What to check |
| --- | --- |
| Not on a want list yet | `marketplace_pricing.want_list_item` for this environment and canonical item. The want list job runs hourly. |
| Crawl target is waiting | `crawl_target.target_status_code = 'IDLE'` and `next_due_at > now`, or the blackout window. |
| The crawler has not run long enough | It fetches only due targets, `worker-batch-size` per run, behind the AJAX rate limiter. |
| BrickLink `idItem` not resolved | `marketplace_item.marketplace_unique_key` is null; the crawler looks it up before the first fetch. |
| Crawl failed or is waiting for retry | `crawl_target.consecutive_failure_count`, `last_error_message`, `next_due_at`, and the `crawl_attempt` rows for the target. |

Decision outcomes that commonly indicate timing or source-data state:

| Decision reason | Meaning | Next action |
| --- | --- | --- |
| `NO_CURRENT_SNAPSHOT` | No market snapshot exists for the listing's item and condition. | Check the want list and the crawl target as above. |
| `NO_CURRENT_COMPARABLES` | The latest snapshot has no lots at all. | No crawl timing problem exists; review manually or wait for market listings. |
| `NO_EXACT_COMPARABLES` | The snapshot has lots, but none in the listing's sub-condition after own-listing and own-store exclusion. | Inspect the snapshot's `market_listing` rows and their `condition_code` / `completeness_code`. |
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

Class: `io.legohunter.ingress.pricing.BricklinkPricingDecisionJob`

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
| Formula levers | see below | | `aggression`, `target-percentiles`, `outlier-max-ratio-to-median`, `two-comparables-fraction`, `maximum-change-per-run`, `maximum-absolute-change-per-run`, `box-instructions-adjustment-enabled`, `step-down.*`. See "Pricing formula and its levers". |
| `lego.bricklink.pricing.decision.minimum-price` | null | Decimal money amount or null | Optional lower bound. If computed price is below this value, final price is clamped and reason is `BELOW_MIN_PRICE_CLAMPED`. |
| `lego.bricklink.pricing.decision.maximum-price` | null | Decimal money amount or null | Optional upper bound. If computed price is above this value, final price is clamped and reason is `ABOVE_MAX_PRICE_CLAMPED`. |

Candidate selection:

| Requirement | Detail |
| --- | --- |
| Marketplace listing service | `marketplace_listing.listing_external_service_id` must equal `lego.bricklink.pricing.decision.bricklink-external-service-id`. |
| Marketplace listing status | `marketplace_listing.listing_status_code` must be in `lego.bricklink.pricing.decision.priceable-listing-status-codes`, defaulting to `ACTIVE` and `DRAFT`. |
| Catalog mapping | `marketplace_listing.external_catalog_item_id` must be populated. |
| Fixed-price eligibility | `marketplace_listing.fixed_price=true` listings are candidates even when condition/completeness are missing because fixed price is authoritative. |
| Non-fixed eligibility | Non-fixed listings must have `item_inventory.new_or_used` of `N`, `NEW`, `U` or `USED` (any case, trimmed) and non-blank `item_inventory.completeness`. Crawl scheduling uses the same predicate. |
| Optional snapshot gate | When `require-current-snapshot=true`, non-fixed listings must also have a shared `market_snapshot` for their catalog item and normalized condition. Use this after crawl data is flowing to avoid filling review batches with `NO_CURRENT_SNAPSHOT` decisions. |
| Batch limit | The selection query filters to eligible candidates, orders by `marketplace_listing_id`, and applies `lego.bricklink.pricing.decision.batch-size`. |

Decision behavior:

| Step | Description |
| --- | --- |
| Fixed price override | If `marketplace_listing.fixed_price=true`, writes a `SKIPPED` decision with reason `FIXED_PRICE_OVERRIDE`, preserves current listing price as `final_price`, and does not read snapshots. |
| Load inventory | Loads `item_inventory` for condition, completeness, box condition, and instructions condition. |
| Normalize codes | Maps `N`/`NEW` to `N`, `U`/`USED` to `U`, `SEALED`/`S` to `S`, `COMPLETE`/`C` to `C`, and `INCOMPLETE`/`I`/`X` to `X`. |
| Select latest snapshot | Reads the latest shared `market_snapshot` (`BRICKLINK`) for the listing's canonical item (catalog item type + number) and normalized New/Used condition. |
| Select exact comparables | Reads that snapshot's `market_listing` rows whose canonical `condition_code` and `completeness_code` match the listing's (one snapshot holds every sub-condition). |
| Exclude own listing | Removes the owned marketplace listing by matching `market_listing.external_listing_id` to `marketplace_listing.external_listing_id`, and every lot whose `seller_key` is in `own-seller-names`. |
| Compute price | Runs the market-position formula ("Pricing formula and its levers" below) and writes a `PROPOSED` decision. |
| Persist metadata | Stores algorithm version, strategy, reason code, computed price, final price, previous price, currency, exact comparable count, confidence, source summary JSON, and notes. |
| No apply | Phase 3 writes decisions only. It does not update `marketplace_listing.unit_price` and does not trigger marketplace sync. |

#### Pricing formula and its levers

Algorithm `bricklink-market-position-v2`. Every decision row stores it in `pricing_decision.algorithm_version`, and stores the aggression preset in `strategy_code`.

**Why this formula.** The design comes from the HOMELAB-13 analysis (`00-workspace/context/pricing-plane/pricing-algorithm-analysis.md`), which compared about ten weeks of crawl history with BrickLink sold records:

- Buyers buy the cheaper lots. Across 187 sales, the middle sale sat at the 12th percentile of the prices on offer.
- The earlier formula priced used sets around the 86th percentile. That was above every used sale observed.
- It priced sealed sets just under the cheapest US seller, below almost every sealed sale.
- Used lots whose descriptions say they include a box and instructions ask about 1.26× the market median (around the 73rd percentile), and still leave the market as fast as the rest. Lots with neither ask about 0.89× (around the 31st). BrickLink has no structured box or instructions field, so the formula does **not** read competitors' descriptions at pricing time. Instead it uses **your own** item's box and instructions, from `item_inventory`, to choose a higher or lower target percentile in the whole market. The descriptions were used only once, offline, to calibrate those targets.

**How a price is calculated.** Comparables are the lots in the latest snapshot with the same condition **and** sub-condition as the listing, sorted by price. Excluded: the lot being priced, any lot whose seller is in `own-seller-names` (your own store), and any lot without a positive price in the listing's currency.

| Step | What happens |
| --- | --- |
| 1. Drop outliers | With 3 or more comparables, lots priced above `outlier-max-ratio-to-median` × the median are ignored. The number dropped is recorded as `outliersDropped` in `source_summary_json`. |
| 2a. 3 or more comparables | Price = the **target percentile** of the comparable prices, with linear interpolation between neighbouring lots. For used items, the target depends on the item's own packaging: box and instructions (`BI`), one of the two (`ONE`), or neither (`NONE`). Box or instructions graded `MS` (missing) or `NA` (not applicable) count as not having it. Reason `MARKET_POSITION`. |
| 2b. 2 comparables | Price = lower + (higher − lower) × `two-comparables-fraction`. Reason `TWO_COMPARABLES_WEIGHTED`. |
| 2c. 1 comparable | Price = `min(price - min(price * 0.03, 10), max(price - 1, 1))`, just under the only competitor. Reason `SINGLE_COMPARABLE_DISCOUNTED`. |
| 2d. 0 comparables | `FAILED` with `NO_CURRENT_COMPARABLES` or `NO_EXACT_COMPARABLES`; set a price by hand. |
| 3. Box and instructions grade | Used items only, when `box-instructions-adjustment-enabled`: the price is multiplied by the average of the box and instructions grade factors (table below). The factors are small (−15% to +10%), because having the box or instructions is already rewarded by the higher target. Missing or unknown items count as `1.0`. This gives the **market price**, recorded as `marketPrice`. |
| 3b. Weekly step-down | When `step-down.enabled`, for an `ACTIVE` listing published more than `step-down.start-after-weeks` ago: market price × (1 − `weekly-percent`) ^ (weeks unsold − `start-after-weeks`). It is never more than `maximum-total-percent` below the market price, and never below the `floor-percentile` of the competing lots (with 3 or more comparables). Weeks unsold count from `marketplace_listing.published_at`, so repeated runs never compound the discount. Recorded as `weeksUnsold` and `stepDownPercent`. Drafts and unpublished listings are never stepped down. |
| 4. Change limit | When the listing already has a price, the new price stays within ± `maximum-change-per-run` (and `maximum-absolute-change-per-run`, when set) of it. A large correction therefore happens over several runs. `changeLimited` in `source_summary_json` says whether the limit applied. `computed_price` keeps the formula price after any step-down, but before the change limit. |
| 5. Clamps | `minimum-price` and `maximum-price` apply last, with reasons `BELOW_MIN_PRICE_CLAMPED` and `ABOVE_MAX_PRICE_CLAMPED`. |
| 6. Rounding | `computed_price` and `final_price` are rounded to two decimals. |

**Target percentile.** The share of competing lots priced below yours: 0.20 means 20% of competitors are cheaper. Lower sells faster and higher earns more per sale. A market is `condition-completeness`: `U-C` is Used/Complete, `N-S` Sealed, `N-C` New/Complete, `U-X` Used/Incomplete. Used markets can add your item's packaging: `U-C-BI`, `U-C-ONE`, `U-C-NONE`.

**Levers** (`lego.bricklink.pricing.decision.*`; set them per environment in `application-<profile>.yml`):

| Lever | Default | What it changes |
| --- | --- | --- |
| `aggression` | `BALANCED` | The preset that sets each market's target percentile (next table). `QUICK`, `BALANCED` or `PATIENT`, case-insensitive. |
| `target-percentiles` | none | Overrides of the preset, from `0` (cheapest) to `1` (most expensive). Values outside that range are capped. Quote and bracket the keys. The most specific key wins: `"[U-C-BI]": 0.70` applies only to Used/Complete sets with box and instructions, and `"[U-C]": 0.40` applies to every Used/Complete set not covered by a more specific key. |
| `own-seller-names` | none | Your BrickLink store username(s), matched case-insensitively. Their lots are never counted as competitors, so a listing is never priced against your own other lots. Set it in every environment that prices listings. |
| `outlier-max-ratio-to-median` | `3.0` | Ignore lots priced above this multiple of the median. `0` or empty keeps every lot. |
| `two-comparables-fraction` | `0.25` | Where to price between two competitors: `0` matches the cheaper one, `1` the dearer one. |
| `maximum-change-per-run` | `0.25` | Largest move from the current price in one decision run (0.25 = 25%). `0` or empty moves straight to the formula price. Keep it at or below apply-readiness `maximum-percent-delta`, otherwise readiness blocks the move instead of letting it happen in steps. |
| `maximum-absolute-change-per-run` | none (local and SANDBOX: `50.00`) | The same limit as an amount in listing currency; the smaller of the two applies. Keep it at or below apply-readiness `maximum-absolute-delta`. |
| `box-instructions-adjustment-enabled` | `true` | Turns the box and instructions grade factors on or off. Packaging still chooses the target percentile. |
| `step-down.enabled` | `false` | Lower an unsold active listing's price each week. |
| `step-down.start-after-weeks` | `8` | Weeks at full market price after publishing. |
| `step-down.weekly-percent` | `0.01` | Discount per further week unsold, compounding. Apply readiness ignores changes under its `minimum-delta.percent` (2% in local and SANDBOX), so 1% steps take effect about every other week. Use `0.02` or more if every step should apply. |
| `step-down.maximum-total-percent` | `0.20` | Never more than this far below the market price. |
| `step-down.floor-percentile` | `0.10` | Never below this percentile of current competing lots. Empty disables the floor. |
| `minimum-price` / `maximum-price` | none | Absolute floor and ceiling in listing currency. |

**Presets.** The estimated share sold within 52 weeks comes from the HOMELAB-13 crawl and sold-record analysis; treat it as a guide, not a promise.

| `aggression` | Intent | Used, box and instructions (`BI`) | Used, one of the two (`ONE`) | Used, neither (`NONE`) | Sealed (`N-S`) | Other new (`N-C`, `N-X`) |
| --- | --- | --- | --- | --- | --- | --- |
| `QUICK` | Sell within about 6 months | 0.55 | 0.20 | 0.15 | 0.10 | 0.10 |
| `BALANCED` | Sell within 12 months at the best price per sale | 0.65 | 0.35 | 0.25 | 0.20 | 0.20 |
| `PATIENT` | Hold out for price; 18–24 months acceptable | 0.75 | 0.45 | 0.35 | 0.30 | 0.30 |

Each used value is the whole-market percentile where the preset's position among *similar* sellers lands. For example, under BALANCED the 35th percentile among box-and-instructions lots is about the 65th–70th percentile of the whole market. Estimated share sold within 52 weeks for the whole market: QUICK about 87% used / 97% sealed, BALANCED about 76% / 89%, PATIENT about 64% / 73%. These are a guide, not a promise. The box-and-instructions estimate is less certain, because the sold records do not say which sales had a box. New/Complete markets had too few sales to calibrate, so they use the sealed value until more data exists.

Example: sell faster overall, but price Used/Complete a little higher than the preset, and move prices in smaller steps.

```yaml
lego:
  bricklink:
    pricing:
      decision:
        aggression: QUICK
        target-percentiles:
          "[U-C-BI]": 0.60
        maximum-change-per-run: 0.15
```

Example: try a higher price first, then lower it 2% a week after 6 weeks, to at most 25% off.

```yaml
lego:
  bricklink:
    pricing:
      decision:
        aggression: PATIENT
        step-down:
          enabled: true
          start-after-weeks: 6
          weekly-percent: 0.02
          maximum-total-percent: 0.25
```

**Where to see why a price was chosen.** `pricing_decision.source_summary_json` records:
- `aggression`, `packaging` (`BI`, `ONE`, `NONE`, or empty for new items), and `targetPercentile`;
- `exactComparableCount` (after outliers are dropped) and `outliersDropped`;
- `lowestComparablePrice`, `highestComparablePrice` and `marketPrice`;
- `weeksUnsold` and `stepDownPercent`;
- `changeLimited`, `baseReasonCode` and `finalReasonCode`.

Box and instructions grade factors (used items; the price is multiplied by the average of the box factor and the instructions factor):

```text
SL 1.10, M 1.05, E 1.00, VG 0.98, G 0.95, F 0.90, P 0.85, CC 0.90 (colour copy), BW 0.85 (black-and-white copy)
MS (missing), NA (not applicable), none recorded: 1.00, and the item counts as not having it
```

Confidence by exact comparable count: 5 or more `0.90`, 3–4 `0.75`, 2 `0.60`, 1 `0.40`, 0 `0.00`.

Decision statuses:

| Status | Meaning |
| --- | --- |
| `PROPOSED` | A price was computed and persisted, but not applied. |
| `SKIPPED` | Decision intentionally did not compute a replacement price, currently only fixed-price override. |
| `FAILED` | Required data was missing or the algorithm could not safely compute a price. |

Reason codes:

| Reason | Meaning |
| --- | --- |
| `FIXED_PRICE_OVERRIDE` | Listing has fixed price enabled; current price remains authoritative. |
| `SINGLE_COMPARABLE_DISCOUNTED` | One exact comparable was available after own-listing exclusion. |
| `TWO_COMPARABLES_WEIGHTED` | Two exact comparables were available. |
| `MARKET_POSITION` | Three or more exact comparables; priced at the market's target percentile. |
| `NO_CURRENT_SNAPSHOT` | No latest pricing snapshot exists for the listing condition/completeness. Run the crawl first. |
| `NO_CURRENT_COMPARABLES` | A latest matching snapshot exists, but BrickLink returned no comparable listings for that crawl. |
| `NO_EXACT_COMPARABLES` | Snapshot exists, but no exact comparable remains after filtering and own-listing exclusion. |
| `MISSING_INVENTORY` | Marketplace listing points to missing `item_inventory`. |
| `MISSING_CONDITION` | Inventory condition is missing or not recognized. |
| `MISSING_COMPLETENESS` | Inventory completeness is missing. |
| `BELOW_MIN_PRICE_CLAMPED` | Computed price was below configured `minimum-price`; final price was clamped. |
| `ABOVE_MAX_PRICE_CLAMPED` | Computed price was above configured `maximum-price`; final price was clamped. |

Important logs:

| Event | Meaning |
| --- | --- |
| `bricklink.pricing.decision.job.completed` | Scheduled run completed and logs `BricklinkPricingDecisionResult` with selected, written, proposed, skipped, failed, and elapsed counters. |

### `BricklinkPricingApplyReadinessJob`

Class: `io.legohunter.ingress.pricing.BricklinkPricingApplyReadinessJob`

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
| `skippedStaleDecision` | A newer shared `market_snapshot` exists for the listing's item and condition than the one the latest proposed decision used (compared by `captured_at`). Persisted rows use readiness status `BLOCKED_STALE_DECISION` and block reason `STALE_DECISION`. |

Important logs:

| Event | Meaning |
| --- | --- |
| `bricklink.pricing.apply_readiness.ready` | DEBUG-level detail for one latest proposed decision that would change a listing if a later apply phase existed. Logs listing id, decision id, readiness status, initial-price flag, current price, proposed price, delta, currency, reason, and algorithm version. Initial-price rows explicitly show a null current-price baseline. |
| `bricklink.pricing.apply_readiness.job.completed` | Scheduled run completed and logs `BricklinkPricingApplyReadinessResult` with selected, ready, skipped, and elapsed counters. |

### BrickLink Pricing Maintenance Report

Class: `io.legohunter.ingress.pricing.BricklinkPricingMaintenanceReportController`

Endpoint:

```text
GET /internal/bricklink/pricing/maintenance-report?limit=100
```

This is a dry-run diagnostic report. It does not delete, requeue, archive, or otherwise mutate Pricing Plane data.

### BrickLink Pricing Data-Quality Report

Class: `io.legohunter.ingress.pricing.BricklinkPricingMaintenanceReportController`

Endpoint:

```text
GET /internal/bricklink/pricing/data-quality-report?limit=100
```

Read-only. It lists BrickLink listings in `lego.bricklink.pricing.crawl.priceable-listing-status-codes` (default `ACTIVE`, `DRAFT`) whose inventory or catalog data is missing something the Pricing Plane needs, so the data can be fixed before the listing is crawled or priced. `limit` is capped at 500; `summary` always counts every listing.

| Field | Meaning |
| --- | --- |
| `summary.listingsChecked` | Listings in the priceable statuses. |
| `summary.listingsWithGaps` | Listings with at least one gap. Each gap below is also counted on its own. |
| `gaps[]` | Up to `limit` listings with a gap, lowest `marketplaceListingId` first. Each row carries the raw inventory and catalog values, the status of the listing's most recent crawl work item (`latestCrawlStatusCode`), and one flag per gap. |

| Gap flag | Condition | Effect |
| --- | --- | --- |
| `missingCondition` | `item_inventory.new_or_used` is blank or not `N`/`NEW`/`U`/`USED`. | Never crawled; non-fixed listings never priced. |
| `missingCompleteness` | `item_inventory.completeness` is blank. | Never crawled; non-fixed listings never priced. |
| `missingCatalogItem` | `marketplace_listing.external_catalog_item_id` is NULL. | Never crawled or priced. |
| `missingItemNumber` | The catalog item's `external_item_key` is blank. | Crawl work ends `SKIPPED_MISSING_ITEM_NUMBER`. |
| `missingItemType` | The catalog item's `item_type_code` is blank. | The crawl falls back to `catalog-item-type` (default `S`); wrong for non-set items. |
| `missingInternalItemId` | The catalog item's `external_unique_key` (BrickLink `idItem`) is blank. | The crawl looks it up first. Together with a `FAILED_ITEM_ID_LOOKUP_*` `latestCrawlStatusCode` it is a hydration failure that needs a catalog fix. |
| `missingBoxGrade` | A used item has no `item_inventory.box_condition_id`. | Priced as if it has no box (lower target percentile). |
| `missingInstructionsGrade` | A used item has no `item_inventory.instructions_condition_id`. | Priced as if it has no instructions. |

Box and instructions grades are checked for used items only; new items are priced without them. A grade of `MS` (missing) or `NA` (not applicable) is a recorded answer, not a gap.

The same counts are exported as Prometheus gauges, refreshed by `BricklinkPricingDataQualityMetrics` after every pricing crawl job run (one aggregate query per run, not per scrape). They are shown on the `lego-data-ingress` Grafana dashboard, Pricing Plane row, as **Listings with Data Gaps** and **Pricing Data Gaps by Type**.

| Meter | Type | Tags | Description |
| --- | --- | --- | --- |
| `bricklink_pricing_data_quality_gaps_current` | Gauge | `gap` | Listings with that gap: `condition`, `completeness`, `catalog_item`, `item_number`, `item_type`, `internal_item_id`, `box_grade`, `instructions_grade`. |
| `bricklink_pricing_data_quality_listings_current` | Gauge | `scope` | `checked` (listings in the priceable statuses) and `with_gaps` (listings with at least one gap). |
| `bricklink_pricing_data_quality_last_refresh_timestamp_seconds` | Gauge | none | Epoch seconds of the last refresh; 0 until the first crawl run after start-up. |

Every series is registered at start-up and reports 0 until the first refresh; a failed refresh logs `bricklink.pricing.data_quality.metrics_refresh_failed` and keeps the previous values. When the crawl job is disabled the gauges are never refreshed, so check the last-refresh timestamp before trusting a 0.

### BrickLink Pricing Apply Preview Reports

Class: `io.legohunter.ingress.pricing.BricklinkPricingMaintenanceReportController`

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

Class: `io.legohunter.ingress.pricing.BricklinkPricingApplyJob`

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
| `APPLY_LOCAL_ONLY` | Updates `marketplace_listing.unit_price` and moves the decision from `PROPOSED` to `APPLIED` with `applied_at`, in one transaction. Does not enqueue remote marketplace sync. |
| `APPLY_LOCAL_AND_ENQUEUE_SYNC` | **Remote first.** For listings with an existing BrickLink inventory id, it moves the decision from `PROPOSED` to `ACCEPTED` and inserts a `PRICE_UPDATE` sync request, in one transaction, and leaves the local price alone: the marketplace sync worker changes `marketplace_listing.unit_price`, and moves the decision to `APPLIED`, only after BrickLink confirms the new price. If the request instead ends `BLOCKED`, or `FAILED` with no attempts left, the worker moves the decision from `ACCEPTED` to `SYNC_FAILED` and the local price stays as it was; a retryable failure leaves it `ACCEPTED`. A listing that already has a `PENDING` or `CLAIMED` sync request is skipped until it finishes. An existing request for the same decision is never overwritten. For priced local `DRAFT` listings without a BrickLink inventory id, it sets the draft price, accepts the decision and inserts a `LISTING_CREATE` sync request, in one transaction. For priced local `DRAFT` listings without a BrickLink inventory id, it upserts a `LISTING_CREATE` sync request so the marketplace sync worker can create the stockroom/public listing according to environment guardrails. Initial-price requests use `previous_unit_price=NULL`, a positive `requested_unit_price`, and sync reason `INITIAL_PRICE_APPLIED`. |

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
| `bricklink.pricing.apply.local_updated` | `APPLY_LOCAL_ONLY`: local `marketplace_listing.unit_price` was updated and the pricing decision was marked `APPLIED`. |
| `bricklink.pricing.apply.draft_price_set` | The price of a local `DRAFT` (no BrickLink lot yet) was set before its `LISTING_CREATE` request was queued. |
| `bricklink.pricing.apply.sync_enqueued` | The decision was accepted and a durable `PRICE_UPDATE` `marketplace_listing_sync_request` row was inserted for an existing BrickLink inventory row. The local price is unchanged until the sync worker confirms it. |
| `bricklink.pricing.apply.listing_create_sync_enqueued` | A durable `LISTING_CREATE` `marketplace_listing_sync_request` row was inserted for a priced local `DRAFT` listing with no BrickLink inventory id. |
| `bricklink.pricing.apply.skipped` | A selected row was intentionally skipped. `reason` is one of `PRICE_ALREADY_CURRENT`, `PRICING_DECISION_ALREADY_APPLIED` (already applied, or another worker accepted it first), `SYNC_REQUEST_IN_FLIGHT` (the listing has a `PENDING` or `CLAIMED` sync request), or `MISSING_REMOTE_INVENTORY_ID_FOR_NON_DRAFT` (a non-draft listing with no BrickLink lot; its price is not changed). |
| `bricklink.pricing.apply.failed` | One selected readiness row failed defensive re-checks or sync-request preconditions. |
| `bricklink.pricing.apply.job.completed` | Scheduled run completed and logs `BricklinkPricingApplyResult`. |

### `BricklinkMarketplaceSyncJob`

Class: `io.legohunter.ingress.marketplace.bricklink.BricklinkMarketplaceSyncJob`

Condition:

```yaml
lego.bricklink.marketplace-sync.enabled: true
lego.bricklink.marketplace-sync.scheduled.enabled: true
```

The marketplace sync job consumes due `marketplace_listing_sync_request` rows for BrickLink `PRICE_UPDATE` and `LISTING_CREATE` work. This is the first LegoHunter path that can mutate the live BrickLink account from local system-of-record data, so every write path is guarded by final safety checks in the worker.

`PRICE_UPDATE` verifies the existing remote BrickLink inventory before mutating price or remarks. `LISTING_CREATE` verifies local create eligibility, builds a BrickLink `Inventory` create payload, calls BrickLink `createInventory`, fetches the created remote inventory back, verifies the remote safety contract, and only then marks the local `marketplace_listing` `ACTIVE`.

`LISTING_CREATE` creates each lot at most once (HOMELAB-15). BrickLink has no idempotency key, so a retried create would otherwise list the same unique item twice:

1. The new lot's id is saved on `marketplace_listing_sync_request.remote_inventory_id` (first) and `bricklink_marketplace_listing.bricklink_inventory_id` as soon as `createInventory` returns, before the read-back.
2. A request that already has a saved `remote_inventory_id` resumes at the read-back instead of creating again. Only the request's own id counts: a listing whose mapping already had a lot before the request is still blocked with `REMOTE_INVENTORY_ALREADY_EXISTS`.
3. Before creating, the worker lists the store's lots (`GET /inventories` with no `status` filter, which BrickLink documents as every status, stockrooms included) and looks for a system remarks block naming this environment, `MARKETPLACE_LISTING_ID` and `ITEM_INVENTORY_UUID`. One match is taken over (`listing_create_adopted`: an earlier create succeeded on BrickLink but its response was lost). More than one blocks the request with `DUPLICATE_REMOTE_INVENTORY`; resolve those lots by hand. If the search fails, nothing is created and the request retries.
4. The read-back of a lot the request just created or took over must pass remote safety and hold exactly the quantity the listing put up (1). BrickLink's create can consolidate a new lot into a matching one, so a different quantity blocks the request with `REMOTE_QUANTITY_MISMATCH`. On either failure the worker hides the lot: stockroom `A` in PROD (its stockroom for deliberately hidden lots), the environment's own stockroom elsewhere. The request's error message says whether hiding worked; if it says "hide it by hand", do that.
5. At the start of each `APPLY` run, requests left `CLAIMED` for longer than `stale-claim-timeout` (default `1h`) are put back to `PENDING` (or `FAILED` with no attempts left). That happens when a pod is killed mid-run. Steps 2 and 3 make the retry safe.

BrickLink calls time out (`bricklink.rest.timeouts.connect`, default `10s`; `.read`, default `60s`). A timed-out create may still have been applied by BrickLink; step 3 finds it on the retry.

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
| Expected stockroom | `stock_room_id` must match `lego.bricklink.marketplace-sync.non-prod-stock-room-id`: `C` for SANDBOX and local, `B` for DEV. There is no default, and `A` is rejected outside PROD. The rules are shared with lego-data-service (`BricklinkStockroomPolicy` and `LegoEnvironment` in lego-data). The environment code must be `local`, `sandbox`, `dev` or `prod`, and must agree with `production`. Pricing apply's environment code must equal marketplace sync's (HOMELAB-20). |
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

Local safety metadata on `bricklink_marketplace_listing` after a sync attempt:

| Column | Safety check passed | Safety check blocked |
| --- | --- | --- |
| `last_remote_verified_at`, `last_remote_safety_status_code`, `last_remote_safety_message` | Set (`VERIFIED`) | Set (the block reason) |
| `environment_code` | Set to the running environment | **Left unchanged.** A blocked lot was not verified as this environment's, so it is not marked as such. |
| `system_remarks_hash` | Set to the hash of the remarks written | Cleared |
| `remarks`, `is_stock_room`, `stock_room_id` (blocked create, before any BrickLink call) | Set from the create payload | Left unchanged, so the local draft keeps its own values |

So `environment_code` names the environment that created the draft or last verified the lot, never one that only tried and was blocked. A read-back mismatch after a price update records the mismatch status and the observed stockroom only.

Lower-environment data: listing and marketplace state (listings, sync requests, pricing tables) is never copied down from a higher environment. Seed SANDBOX or DEV through the API instead, so each lot is created in that environment's stockroom with its own system block. See `00-workspace/docs/architecture/system-overview.md`, "Data between environments".

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
| `lego.bricklink.marketplace-sync.non-prod-require-stock-room` | `true` | `true` only, outside PROD | Requires stockroom-only inventory in non-prod environments. `false` is rejected at startup outside PROD (HOMELAB-20). |
| `lego.bricklink.marketplace-sync.non-prod-stock-room-id` | none (must be set) | `C` (SANDBOX, local), `B` (DEV) | Expected non-prod stockroom. `A` is reserved for PROD and rejected at startup in lower environments (HOMELAB-20). |
| `lego.bricklink.marketplace-sync.remarks-max-length` | `1024` | Integer; effective value at least `1` | Local maximum generated BrickLink remarks length. |
| `lego.bricklink.marketplace-sync.scheduled.enabled` | `false` | `true`, `false` | Creates the scheduled sync worker only when `enabled=true`. |
| `lego.bricklink.marketplace-sync.scheduled.fixed-delay-ms` | `300000` | Long milliseconds, >= 0 | Delay between sync scans. |
| `lego.bricklink.marketplace-sync.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | Startup delay before first sync scan. |
| `lego.bricklink.marketplace-sync.scheduled.lock-at-most-for` | `10m` | ShedLock duration string | Maximum distributed lock time. |
| `lego.bricklink.marketplace-sync.scheduled.lock-at-least-for` | `0s` | ShedLock duration string | Minimum distributed lock time. |
| `lego.bricklink.marketplace-sync.stale-claim-timeout` | `1h` | Duration | How long a request may stay `CLAIMED` before the next `APPLY` run treats its worker as dead and releases it. Keep it longer than any one run. |
| `bricklink.rest.timeouts.connect` / `.read` | `10s` / `60s` | Duration | BrickLink REST client timeouts (bricklink-rest). |

Important logs:

| Event | Meaning |
| --- | --- |
| `bricklink.marketplace_sync.dry_run_verified` | A due `PRICE_UPDATE` request passed remote safety verification, but sync mode is `DRY_RUN`. |
| `bricklink.marketplace_sync.listing_create_dry_run` | A due `LISTING_CREATE` request passed local create safety and mapped to a BrickLink create payload, but sync mode is `DRY_RUN`. |
| `bricklink.marketplace_sync.listing_created` | BrickLink `createInventory` succeeded, remote safety verification passed, and the local listing was marked `ACTIVE`. |
| `bricklink.marketplace_sync.listing_create_blocked` | A `LISTING_CREATE` request failed final local create safety checks. |
| `bricklink.marketplace_sync.listing_create_remote_verification_blocked` | BrickLink inventory was created, but the read-back remote safety check failed. The local BrickLink inventory id is retained for investigation and the request is blocked. |
| `bricklink.marketplace_sync.listing_create_blocked_lot_hidden` | The lot that failed the read-back check was moved into a stockroom (A in PROD, the environment's own elsewhere) so it is not for sale. |
| `bricklink.marketplace_sync.listing_create_blocked_lot_hide_failed` | Hiding that lot failed. Hide it in BrickLink by hand. |
| `bricklink.marketplace_sync.listing_create_resumed` | A `LISTING_CREATE` request with a saved `remote_inventory_id` resumed at the read-back instead of creating another lot. |
| `bricklink.marketplace_sync.listing_create_adopted` | No id was saved, but the store already had exactly one lot owned by this listing (an earlier create's response was lost). The lot was taken over instead of creating another. |
| `bricklink.marketplace_sync.listing_create_duplicates_found` | The store has several lots owned by this listing. Nothing was created; the request is blocked with `DUPLICATE_REMOTE_INVENTORY`. |
| `bricklink.marketplace_sync.stale_claim_released` | A request stuck in `CLAIMED` past `stale-claim-timeout` was put back to `PENDING` (or `FAILED`). |
| `bricklink.marketplace_sync.remote_updated` | BrickLink `updateInventory` was called for a price update. |
| `bricklink.marketplace_sync.blocked` | A request failed safety checks and was marked blocked in apply mode. |
| `bricklink.marketplace_sync.failed` | A transient or unexpected failure occurred. In apply mode, the request retries with backoff until max attempts is reached. |
| `bricklink.marketplace_sync.decision_sync_failed` | A request with a pricing decision ended `BLOCKED`, or `FAILED` with no attempts left, so the decision moved from `ACCEPTED` to `SYNC_FAILED`. |
| `bricklink.marketplace_sync.decision_not_accepted` | The request finished (succeeded, blocked or failed) but its decision was no longer `ACCEPTED`, so its status was left alone. |
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

### `BricklinkInventoryReconciliationJob`

Read-only comparison of the whole BrickLink store with the local listings (HOMELAB-18). One
`getInventories` call per run; it never writes to BrickLink or the database.

For every ACTIVE local BrickLink listing with a lot id it reports:

| Drift type | Meaning |
| --- | --- |
| `MISSING_REMOTE_LOT` | The store no longer has the lot: usually sold, or deleted by hand. |
| `PRICE_DRIFT` | The lot's price differs from `marketplace_listing.unit_price`. Not reported while the listing has a `PENDING`/`CLAIMED` sync request (price change in flight). |
| `QUANTITY_DRIFT` | The lot's quantity differs from `last_remote_quantity` (1 when unset). |
| `VISIBILITY_DRIFT` | Outside PROD: the lot is not in this environment's stockroom (SANDBOX C, DEV B). In PROD: the stockroom flag or id differs from what the listing recorded. |
| `REMARKS_DRIFT` | The system remarks block is missing, or names another environment or listing. |
| `UNTRACKED_REMOTE_LOT` | A lot whose block names this environment, but no ACTIVE local listing points at it. |

Each drift is logged at WARN as `bricklink.inventory_reconciliation.drift`; each run logs
`bricklink.inventory_reconciliation.completed`. A failed store read logs
`bricklink.inventory_reconciliation.failed` at ERROR and leaves the metrics from the last good run.

Settings (`lego.bricklink.inventory-reconciliation.*`): `enabled` (default `false`),
`bricklink-external-service-id` (`2`), `scheduled.enabled` (`false`), `scheduled.fixed-delay-ms`
(`3600000`), `scheduled.initial-delay-ms` (`120000`), `scheduled.lock-at-most-for` (`10m`),
`scheduled.lock-at-least-for` (`0s`). SANDBOX runs it hourly.

### `BricklinkLotAuditJob`

Read-only audit that no lot owned by a lower environment can be seen by buyers or sits in the
wrong stockroom (HOMELAB-18). It shares no code with the marketplace-sync guards it double-checks:
it reads the whole store itself, finds `LEGOHUNTER_ENV=<code>` anywhere in the remarks (so a
damaged system block still counts), and keeps its own copy of the stockroom rule, which a unit test
checks against `LegoEnvironment`.

| Violation | Meaning |
| --- | --- |
| `LOWER_ENV_LOT_PUBLIC` | A lot tagged for a non-PROD environment is not in any stockroom: buyers can see it. |
| `LOWER_ENV_LOT_IN_PROD_STOCKROOM` | It is in stockroom A, which only PROD may use. |
| `LOWER_ENV_LOT_IN_WRONG_STOCKROOM` | It is in another environment's stockroom (SANDBOX must be C, DEV must be B), or names an unknown environment. |

Every violation is logged at ERROR as `bricklink.lot_audit.violation`. It never changes a lot:
move the lot back by hand, then find out how it got there.

Settings (`lego.bricklink.lot-audit.*`): `enabled` (default `false`), `scheduled.enabled`
(`false`), `scheduled.fixed-delay-ms` (`1800000`), `scheduled.initial-delay-ms` (`60000`),
`scheduled.lock-at-most-for` (`10m`), `scheduled.lock-at-least-for` (`0s`). It has its own switch,
separate from marketplace sync and the Pricing Plane. Until PROD runs lego-data-ingress it runs in
SANDBOX every 30 minutes; move it to PROD then, where a SANDBOX feature deploy cannot switch it off.

Run either check now (read-only; also updates the metrics), or get 404 when it is not enabled:

```shell
curl -s -X POST https://lego-data-ingress.sandbox.internal.legohunter.io/internal/bricklink/inventory/reconciliation
curl -s -X POST https://lego-data-ingress.sandbox.internal.legohunter.io/internal/bricklink/inventory/lot-audit
```

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

### S3 Event Router

Listener: `S3EventListener`

Input topic:

```yaml
kafka.topic-configuration.s3-events.topics
```

Group id:

```yaml
kafka.topic-configuration.s3-events.consumer.group-id
```

Container factory: `s3EventsContainerFactory`. Router: `S3EventRouter`, which publishes through
`s3EventRoutingKafkaTemplate` (topic id `s3-event-routing`).

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
| BrickLink catalog file split | `BricklinkCatalogS3EventListener` | `upload-bricklink-catalog.topic` | Reads XML from S3 and publishes one `CatalogEntry` per `ITEM` to `bricklink-catalog-entry.topic`. |
| BrickLink catalog DB upsert | `BricklinkCatalogEntryConsumer` | `bricklink-catalog-entry.topic` | Upserts `external_catalog_item` and category link when category exists. |
| BrickLink category file split | `BricklinkCategoryS3EventListener` | `upload-bricklink-category.topic` | Reads XML from S3 and publishes one `CategoryEntry` per `ITEM` to `bricklink-category-entry.topic`. |
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
| Rebrickable catalog category lookup | Looks the set's theme up among Rebrickable categories (service id `9`). Before this fix it used BrickLink's id `2`, so environments ingested earlier hold wrong links (Rebrickable items → BrickLink categories) until repaired. |

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
| `lego.bricklink.pricing.crawl.active-listing-status-code` | `ACTIVE` | Non-blank listing status string; code trims and uppercases | Listing status used by the maintenance and data-quality reports. The shared crawler itself crawls the want list, not listings. |
| `lego.bricklink.pricing.crawl.catalog-item-type` | `S` | BrickLink catalog item type | Unused by the shared crawler: `searchproduct.ajax` gets the canonical item's own type (`S`, `G`, `B`). |
| `lego.bricklink.pricing.crawl.batch-size` | `25` | Integer; effective value at least `1` | Unused by the shared crawler (every wanted pair gets a target). |
| `lego.bricklink.pricing.crawl.worker-batch-size` | `1` | Integer; effective value at least `1` | Maximum due crawl targets claimed and fetched per run. |
| `lego.bricklink.pricing.crawl.results-per-page` | `500` | Integer; effective value at least `1` | `rpp` sent to `catalogifs.ajax`. |
| `lego.bricklink.pricing.crawl.max-attempts` | `3` | Integer; effective value at least `1` | After this many failed fetches in a row (`crawl_target.consecutive_failure_count`), the target waits a full `crawl-cadence` instead of `retry-backoff`. |
| `lego.bricklink.pricing.crawl.crawl-cadence` | `7d` | Spring `Duration`, positive | After a success the target is due again this much later; also the wait after `max-attempts` failures, and the minimum gap when a target is wanted again. |
| `lego.bricklink.pricing.crawl.schedule-spread-window` | `72h` | Spring `Duration`, zero or positive | Window used to spread new crawl targets' due times. |
| `lego.bricklink.pricing.crawl.schedule-jitter` | `0s` | Spring `Duration`, zero or positive | Optional random positive jitter added to new targets' due times. |
| `lego.bricklink.pricing.crawl.retry-backoff` | `6h` | Spring `Duration`, positive | Delay before a target whose fetch failed is due again. |
| `lego.bricklink.pricing.crawl.claim-stale-after` | `2h` | Spring `Duration`, positive | A claim expires after this long, so a crashed crawler's target is taken over. |
| `lego.bricklink.pricing.crawl.claimed-by` | pod host name | String | Stored in `crawl_target.claimed_by`. |
| `lego.bricklink.pricing.crawl.blackout.enabled` | `false` | `true`, `false` | No fetches inside the window; new and retry due times are moved to its end. |
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
| `lego.bricklink.pricing.decision.require-current-snapshot` | `false` | `true`, `false` | When true, non-fixed candidates must already have a shared `market_snapshot` for their item and condition; this suppresses `NO_CURRENT_SNAPSHOT` review rows and keeps batches focused on calculable or fixed-price listings. |
| `lego.bricklink.pricing.decision.aggression` | `BALANCED` | `QUICK`, `BALANCED`, `PATIENT` | Preset that sets each market's target percentile. Stored on `pricing_decision.strategy_code`. |
| `lego.bricklink.pricing.decision.target-percentiles` | empty | Map of `condition-completeness[-packaging]` to `0`..`1`, keys bracketed (`"[U-C-BI]": 0.70`) | Overrides of the preset; the most specific key wins. |
| `lego.bricklink.pricing.decision.own-seller-names` | empty | Set of BrickLink usernames | Comparables sold by these sellers (your own store) are ignored. |
| `lego.bricklink.pricing.decision.outlier-max-ratio-to-median` | `3.0` | Decimal; `0` or empty disables | Ignore comparables priced above this multiple of the median. |
| `lego.bricklink.pricing.decision.two-comparables-fraction` | `0.25` | `0`..`1` | Price position between exactly two comparables. |
| `lego.bricklink.pricing.decision.maximum-change-per-run` | `0.25` | Fraction; `0` or empty disables | Largest move from the current price per decision run. |
| `lego.bricklink.pricing.decision.maximum-absolute-change-per-run` | null | Money amount; `0` or empty disables | Largest move from the current price per decision run, as an amount. |
| `lego.bricklink.pricing.decision.box-instructions-adjustment-enabled` | `true` | `true`, `false` | Apply the box and instructions grade factors to used items. |
| `lego.bricklink.pricing.decision.step-down.enabled` | `false` | `true`, `false` | Lower an unsold active listing's price each week. |
| `lego.bricklink.pricing.decision.step-down.start-after-weeks` | `8` | Integer >= 0 | Weeks at full market price after `published_at`. |
| `lego.bricklink.pricing.decision.step-down.weekly-percent` | `0.01` | Fraction | Compounding discount per further week unsold. |
| `lego.bricklink.pricing.decision.step-down.maximum-total-percent` | `0.20` | `0`..`1` | Largest total step-down below the market price. |
| `lego.bricklink.pricing.decision.step-down.floor-percentile` | `0.10` | `0`..`1`; empty disables | Never step below this percentile of current competing lots. |
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
        aggression: BALANCED
        scheduled:
          enabled: false
```

Run pricing decisions after the shared crawler has populated `marketplace_pricing.market_snapshot` and `market_listing` for the environment's wanted items. The decision job selects fixed-price overrides and non-fixed listings with populated condition/completeness data; legacy listings missing those fields do not consume the configured decision batch. Phase 3 decisions are non-applying; review `pricing_decision` rows before any future apply/sync phase.

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

### `lego.s3.*`

Backed by `S3ClientProperties` (connection) and `S3BucketProperties` (buckets). The store is Silo, a
MinIO fork; the client is the MinIO Java SDK (`io.minio:minio`).

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.s3.url` | Each environment profile sets its own | Absolute URI | S3 endpoint. |
| `lego.s3.access-key` | None | Secret string | S3 access key (from `s3-configuration.yml`). |
| `lego.s3.secret-key` | None | Secret string | S3 secret key (from `s3-configuration.yml`). |
| `lego.s3.buckets.final-photo` | None; startup fails if blank | Bucket name | Bucket that holds the final scaled photos, `lego-photos-<env>`. |

`lego.s3.url`, `access-key`, and `secret-key` are required by image-hosting readiness when image-hosting sync runtime is required.

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
| `kafka.topic-configuration.<id>.topics` | Topic-specific | Comma-separated or YAML list of topic names | Used by listeners that subscribe to multiple topics, such as `s3-events`. |
| `kafka.topic-configuration.<id>.consumer.group-id` | Topic-specific | String | Consumer group id. |
| `kafka.topic-configuration.<id>.consumer.concurrency` | Topic-specific | Integer | Used only where the listener annotation references it. Currently `upload-photo`. |
| `kafka.topic-configuration.<id>.consumer.properties.*` | Topic-specific | Kafka/Spring Kafka properties | Per-topic consumer overrides. Hyphens are converted to dots. |
| `kafka.topic-configuration.<id>.producer.*` | Topic-specific | Kafka producer properties | Per-topic producer overrides. Hyphens are converted to dots. |

Sandbox topic ids currently configured:

| Topic id | Role |
| --- | --- |
| `s3-events` | Raw S3 bucket-event input (`<env>-s3-events`). |
| `s3-event-routing` | Producer for routed upload/delete events. |
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
| `s3` | Required if scheduled sync is enabled or required | `lego.s3.url`, `lego.s3.access-key`, `lego.s3.secret-key`. |
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

Recorded by the one shared crawler (SANDBOX). The queue gauges read `marketplace_pricing`, so every deployment reports the same values.

| Meter | Type | Tags | Description |
| --- | --- | --- | --- |
| `bricklink_pricing_crawl_job` | Counter | `outcome` | One count per crawl run: `no_work`, `scheduled`, `blackout`, `success`, `partial_success`. |
| `bricklink_pricing_crawl_job_duration` | Timer | `outcome` | Crawl run duration. Prometheus exposes `_seconds_count`, `_seconds_sum`, `_seconds_max`. |
| `bricklink_pricing_crawl_fetch` | Counter | `result` | Fetches: `succeeded`, `empty` (success with no lots), `failed`. A ban or block shows as `failed` rising while `succeeded` stays flat. |
| `bricklink_pricing_crawl_target` | Counter | `result` | Queue changes: `created`, `reenabled`, `disabled`, `claimed`. |
| `bricklink_pricing_crawl_listing` | Counter | `result` | `written`: lots stored. |
| `bricklink_pricing_crawl_item` | Counter | `result` | `id_item_resolved`: BrickLink idItems looked up and stored. |
| `bricklink_pricing_crawl_target_current` | Gauge | `state` | Shared queue now: `idle`, `claimed`, `disabled`, `due`, `failing` (latest attempt failed). |
| `bricklink_pricing_want_list_item` | Counter | `result` | This environment's want list sync: `confirmed`, `canonical_item_added`, `skipped`, `pruned`. |

Prometheus examples:

```promql
sum by (outcome) (increase(bricklink_pricing_crawl_job_total{namespace="$namespace",service="$service"}[$__range]))
sum by (result) (increase(bricklink_pricing_crawl_fetch_total{namespace="$namespace",service="$service"}[$__range]))
sum by (state) (bricklink_pricing_crawl_target_current{namespace="$namespace",service="$service"})
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
| `bricklink_pricing_decision_current` | Gauge | `status`, `unapplied_only` | Current latest decision counts by status. Status values include `proposed`, `failed`, `skipped`, `accepted` and `sync_failed` (`accepted` is waiting on its sync request; `sync_failed` means the request ended `BLOCKED` or `FAILED`). `unapplied_only=true` is currently registered for latest proposed decisions. |

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

### BrickLink Inventory Reconciliation And Lot Audit Metrics

Gauges hold the result of the last run that read the store; a failed read only increments the run
counter, so a BrickLink outage never looks like "all clear". The Grafana dashboard
`lego-server-configuration/grafana/grafana/dashboards/lego-data-ingress.json` shows them in the
"Marketplace Reconciliation and Lot Audit" row.

| Metric | Labels | Meaning |
| --- | --- | --- |
| `bricklink_inventory_reconciliation_job_total` | `outcome` = `in_sync`, `drift_found`, `failed` | Reconciliation runs. |
| `bricklink_inventory_reconciliation_job_duration_seconds` | `outcome` | Reconciliation run time. |
| `bricklink_inventory_reconciliation_drift_current` | `type` = drift type, lower case | Drifts at the last run. |
| `bricklink_inventory_reconciliation_lots_current` | `scope` = `remote_lots`, `owned_remote_lots`, `local_listings`, `matched_listings`, `in_flight_listings` | What the last run compared. |
| `bricklink_inventory_reconciliation_last_success_timestamp_seconds` | | Epoch seconds of the last run that read the store. |
| `bricklink_lot_audit_job_total` | `outcome` = `clean`, `violations_found`, `failed` | Audit runs. |
| `bricklink_lot_audit_violations_current` | `type` = violation type, lower case | Violations at the last run. **Any value above 0 needs action.** |
| `bricklink_lot_audit_lots_current` | `scope` = `lots_checked`, `lower_env_lots` | What the last audit read. |
| `bricklink_lot_audit_last_success_timestamp_seconds` | | Epoch seconds of the last audit that read the store. |

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
| `bricklink.pricing.crawl.failed` | One fetch failed: target, item, condition, outcome, consecutive failures and message. |
| `bricklink.pricing.crawl.job.completed` | Scheduled run completed and logs `BricklinkPricingCrawlResult`. |

`BricklinkPricingCrawlResult` fields:

| Field | Meaning |
| --- | --- |
| `outcome` | `NO_WORK`, `SCHEDULED`, `BLACKOUT`, `SUCCESS`, or `PARTIAL_SUCCESS`. |
| `targetsCreated` | New `crawl_target` rows for item + condition pairs some environment started wanting. |
| `targetsReenabled` | DISABLED targets wanted again. |
| `targetsDisabled` | IDLE targets no environment wants any more. |
| `targetsClaimed` | Due targets this run claimed and fetched. |
| `snapshotsWritten` | `market_snapshot` rows inserted (successful fetches). |
| `emptySnapshots` | Snapshots with no lots for sale. |
| `listingsWritten` | `market_listing` rows inserted. |
| `idItemsResolved` | BrickLink idItems looked up and stored on `marketplace_item`. |
| `failedFetches` | Fetches that failed; each has a `crawl_attempt` row with its outcome and error. |
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

The crawler is shared (HOMELAB-32): exactly one deployment enables it. Validate changes against the scratch schema first.

1. Confirm `marketplace_pricing` exists and the deployment's database user can read it (and write it, for the crawler). See `00-workspace/ops/marketplace-pricing/`.
2. Confirm the want list is filling: `select environment_code, count(*) from marketplace_pricing.want_list_item group by 1`.
3. Set `bricklink.ajax.uri=https://www.bricklink.com`, keep `bricklink.ajax.rate-limit.enabled=true` and `minimum-delay-ms` at `2000` or higher.
4. For a trial, set `lego.marketplace-pricing.schema=marketplace_pricing_scratch`, `lego.bricklink.pricing.crawl.worker-batch-size=1` and a long `scheduled.fixed-delay-ms`.
5. Enable `lego.bricklink.pricing.crawl.enabled=true` and `scheduled.enabled=true` in **one** deployment only. Every other profile keeps `crawl.enabled: false`.
6. Watch `bricklink.pricing.crawl.job.completed` and `bricklink_pricing_crawl_fetch_total{result="failed"}`.
7. Run the SQL checks below: targets by state, recent attempts, latest snapshots.

Rollback:

```yaml
lego:
  bricklink:
    pricing:
      crawl:
        scheduled:
          enabled: false
```

Set `lego.bricklink.pricing.crawl.enabled=false` if the service bean should be disabled entirely. If BrickLink starts failing or throttling AJAX requests, stop the job first; the queue keeps its state, and each failing target already backs off (`retry-backoff`, then a full cadence after `max-attempts`).

### Safe BrickLink Pricing Decision Rollout

1. Confirm `pricing_decision` and `pricing_apply_readiness` exist in the environment database and `marketplace_pricing` is readable.
2. Confirm the shared crawler has produced recent `market_snapshot` rows for this environment's wanted items.
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

1. Confirm the shared crawler has produced recent `market_snapshot` and `market_listing` rows for this environment's wanted items.
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
5. Confirm Flickr credentials and S3 credentials are present.
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

The crawl data is in the shared `marketplace_pricing` schema (HOMELAB-32); the queries below work from any environment connection.

What each environment wants:

```sql
select environment_code,
       count(*) as wanted_rows,
       max(last_confirmed_at) as last_confirmed
from marketplace_pricing.want_list_item
group by environment_code;
```

The crawl queue by state:

```sql
select target_status_code,
       count(*) as targets,
       sum(next_due_at <= utc_timestamp()) as due_now,
       sum(consecutive_failure_count > 0) as failing,
       min(next_due_at) as next_due
from marketplace_pricing.crawl_target
where marketplace_code = 'BRICKLINK'
group by target_status_code;
```

Failing targets with their latest error:

```sql
select ct.crawl_target_id,
       ci.item_type_code,
       ci.item_number,
       ct.query_condition_code,
       ct.consecutive_failure_count,
       ct.last_attempt_at,
       ct.next_due_at,
       left(ct.last_error_message, 200) as last_error
from marketplace_pricing.crawl_target ct
join marketplace_pricing.canonical_item ci
  on ci.canonical_item_id = ct.canonical_item_id
where ct.marketplace_code = 'BRICKLINK'
  and ct.consecutive_failure_count > 0
order by ct.consecutive_failure_count desc, ct.last_attempt_at desc
limit 50;
```

Recent attempts (a ban or maintenance shows as a run of `FAILED_HTTP`):

```sql
select ca.started_at,
       ca.outcome_code,
       ci.item_number,
       ct.query_condition_code,
       ca.market_snapshot_id,
       left(ca.error_message, 120) as error
from marketplace_pricing.crawl_attempt ca
join marketplace_pricing.crawl_target ct
  on ct.crawl_target_id = ca.crawl_target_id
join marketplace_pricing.canonical_item ci
  on ci.canonical_item_id = ct.canonical_item_id
order by ca.started_at desc
limit 50;
```

Latest snapshots and their lots per sub-condition:

```sql
select ms.market_snapshot_id,
       ci.item_number,
       ms.query_condition_code,
       ms.captured_at,
       ms.listing_count,
       ml.condition_code,
       ml.completeness_code,
       count(ml.market_listing_id) as lots,
       min(ml.item_price) as lowest_price
from marketplace_pricing.market_snapshot ms
join marketplace_pricing.canonical_item ci
  on ci.canonical_item_id = ms.canonical_item_id
left join marketplace_pricing.market_listing ml
  on ml.market_snapshot_id = ms.market_snapshot_id
where ms.captured_at >= utc_timestamp() - interval 1 day
group by ms.market_snapshot_id, ci.item_number, ms.query_condition_code, ms.captured_at, ms.listing_count,
         ml.condition_code, ml.completeness_code
order by ms.captured_at desc
limit 100;
```

Price history of one item (every crawl is kept):

```sql
select ms.captured_at,
       min(ml.item_price) as lowest,
       count(*) as lots
from marketplace_pricing.market_snapshot ms
join marketplace_pricing.canonical_item ci
  on ci.canonical_item_id = ms.canonical_item_id
join marketplace_pricing.market_listing ml
  on ml.market_snapshot_id = ms.market_snapshot_id
where ci.item_type_code = 'S'
  and ci.item_number = '6390-1'
  and ms.query_condition_code = 'U'
  and ml.condition_code = 'U'
  and ml.completeness_code = 'C'
group by ms.market_snapshot_id, ms.captured_at
order by ms.captured_at;
```

Items whose BrickLink idItem is not resolved yet:

```sql
select ci.item_type_code, ci.item_number, mi.marketplace_unique_key, mi.resolved_at
from marketplace_pricing.canonical_item ci
left join marketplace_pricing.marketplace_item mi
  on mi.canonical_item_id = ci.canonical_item_id
 and mi.marketplace_code = 'BRICKLINK'
where mi.marketplace_unique_key is null
order by ci.item_number
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
       ps.listing_count as snapshot_listing_count
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
left join marketplace_pricing.market_snapshot ps
  on ps.market_snapshot_id = pd.market_snapshot_id
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
      'MARKET_POSITION',
      'SINGLE_COMPARABLE_DISCOUNTED',
      'TWO_COMPARABLES_WEIGHTED',
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
| Pricing crawl schedules work but makes no AJAX calls | New targets have `next_due_at` in the future, the run is inside the blackout window (`BLACKOUT`), or nothing is due | Check `marketplace_pricing.crawl_target.next_due_at`, `schedule-spread-window`, blackout settings, and `worker-batch-size`. |
| Pricing crawl repeatedly re-crawls the same item | `crawl-cadence` is too short, or the fetch keeps failing and retries after `retry-backoff` | Check `crawl_target.consecutive_failure_count` and the target's `crawl_attempt` rows. |
| Pricing crawl leaves rows in `CLAIMED` | JVM stopped or failed after claim and before completion | Rows older than `claim-stale-after` are requeued on a later run if attempts remain. |
| A listing never gets a pricing crawl | Its inventory has no recognised condition or no completeness, or it has no catalog item | Run `GET /internal/bricklink/pricing/data-quality-report` and fix the flagged fields. |
| Pricing crawl writes `FAILED_ITEM_ID_LOOKUP_NO_MATCH` | BrickLink `searchproduct.ajax` could not match `external_catalog_item.external_item_key` for the selected item type | Verify `external_catalog_item.external_item_key`, `external_catalog_item.item_type_code`, and the configured fallback `catalog-item-type` for rows missing an item type. |
| Pricing crawl writes snapshots but no listings | BrickLink returned zero comparable listings for that item/condition | Check `market_snapshot.listing_count`, request parameters, and BrickLink site manually if needed. This can be valid sparse-market behavior. |
| Pricing crawl sees `catalogifs.ajax ... returned []` as an HTTP failure | BrickLink answers "no lots for sale" with an HTTP 4xx whose body is empty or `[]` | `bricklink-ajax` (2.0.0-SNAPSHOT from HOMELAB-25 on) returns an empty result for that answer, so the crawler writes a successful empty snapshot. A `FAILED_HTTP` attempt with that text means an older `bricklink-ajax` is on the classpath, or the status was 429 or 5xx (never treated as "no lots"). Review `emptySnapshots` and the latest `market_snapshot.listing_count=0` rows. |
| Log `bricklink.pricing.crawl.unparseable_price` | A competitor lot's display price wasn't in a known BrickLink format (`US $1.00`, `AU $1.00`, `EUR 1.00`) or was not positive | The lot is skipped; the rest of the snapshot is written. Check the logged `displayPrice`. |
| Pricing crawl returns New/Complete rows for a New/Sealed inventory item | BrickLink pricing AJAX filters by condition only, not completeness | This is expected. Phase 3 pricing should query exact comparables by matching snapshot/listing condition and completeness. |
| Pricing decision job does not start | `lego.bricklink.pricing.decision.enabled=false` or `lego.bricklink.pricing.decision.scheduled.enabled=false` | Set both properties true for scheduled runs. |
| Pricing decision job reports `NO_WORK` even though active BrickLink listings exist | No active listings currently meet decision-candidate eligibility | Check for missing `external_catalog_item_id`, missing inventory condition/completeness, or non-fixed legacy listings excluded by the candidate query. |
| Pricing decisions are `FAILED` with `NO_CURRENT_SNAPSHOT` | No shared snapshot exists yet for the listing's item and New/Used condition | Check the environment's `want_list_item` row and the item's `crawl_target` (due time, failures). |
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
want_list_item (this environment)
    -> shared crawler (SANDBOX): crawl_target, BrickLink AJAX lookup/fetch
    -> marketplace_pricing.market_snapshot + market_listing
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

A `SUCCEEDED` `crawl_attempt` proves only that source market
data was captured. It does not prove that a pricing decision exists, that the decision
passed readiness, that local price apply ran, or that BrickLink was called. Always
inspect the latest row in each stage when a listing still shows an old manual price.

### Catalog internal-ID hydration

The catalog mapping contains both the user-facing BrickLink item number
(`external_item_key`) and BrickLink's internal `idItem` (`external_unique_key`). When
the shared crawler has no resolved key in `marketplace_pricing.marketplace_item`, it calls
BrickLink `searchproduct.ajax` with the canonical item number and type and stores the key
there once, for every environment (HOMELAB-32). It no longer writes
`external_catalog_item.external_unique_key`.

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
If hydration fails, use the target's latest `crawl_attempt` outcome
(`FAILED_RESOLVE_NO_MATCH`, `FAILED_RESOLVE_AMBIGUOUS`, `FAILED_HTTP`) and the item type to
determine the next action.

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

SANDBOX uses stockroom `C` (HOMELAB-20; the checked-in profile now sets it, and the app
refuses to start with `A`). Each profile declares its environment once, as `lego.environment.code`
(and `production`); marketplace sync and pricing apply read it from there. The lego-data starter refuses to start
the app unless that code matches the connected schema: `sandbox` on `lego_sandbox`, `dev` on `lego_dev`, `prod` on
`lego_prod`, `local` on anything but `lego_prod` (HOMELAB-19/34, `EnvironmentDatabaseVerifier`, which logs
"Environment '…' verified against database schema '…'" at startup); it also stays down while MySQL is unreachable.
Verify the bound profile and pod environment before relying
on the rest of this table. Local remains
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
