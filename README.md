# Lego Data Ingress

## API Documentation

Operational runbook coverage for controllers, scheduled jobs, event-driven
ingestion flows, configuration switches, metrics, logs, and rollout procedures
is maintained in [runbook.md](runbook.md).

Detailed internal image-hosting API documentation for the Flickr Integration
Phase 3 endpoints is maintained in
[docs/image-hosting-api.md](docs/image-hosting-api.md).

When the service is running, generated OpenAPI documentation is also available at `/swagger-ui/index.html` and `/v3/api-docs`.

## Flickr Integration Phase 1

Phase 1 exposes a temporary internal endpoint for manually syncing one item inventory record at a time to the configured image hosting provider.

The endpoint defaults to dry-run mode and does not make provider calls or write `external_image` tables unless `dryRun=false` is supplied.
Responses include an `outcome` field intended for future low-cardinality sync metrics: `SUCCESS`, `PARTIAL_FAILURE`, `FAILED`, or `DRY_RUN`.

```http
POST /internal/image-hosting/item-inventories/{itemInventoryId}/sync
```

Dry run:

```http
POST /internal/image-hosting/item-inventories/100/sync
```

Real sync:

```http
POST /internal/image-hosting/item-inventories/100/sync?dryRun=false
```

Optional provider override:

```http
POST /internal/image-hosting/item-inventories/100/sync?dryRun=false&externalServiceId=10
```

Flickr is disabled by default. Enable it only in a local or environment-specific configuration that has access to the Flickr client configuration consumed by `lego-imaging`.

```yaml
lego:
  image-hosting:
    default-provider: flickr
    providers:
      flickr:
        enabled: true
        external-service-id: 10
        display-name: Flickr
        metrics-tag: flickr
    sync:
      external-service-id: 10

flickr:
  application-name: lego-data-ingress
  client-config-dir: /path/to/secure/config
  client-config-file: flickr-client.json
```

Phase 1 deliberately stayed single-item only. Batch scheduling, delete sync, replacement sync, retries, and real Flickr smoke tests were deferred until the manual path was stable.

The current upload path writes S3 objects to a temporary local file before calling `lego-imaging` because the provider facade accepts `PhotoMetaDataV1` file paths today. Keep this adapter isolated in the sync service until `lego-imaging` supports stream or byte-array uploads.

## Flickr Integration Phase 2

Phase 2 keeps the manual single-item endpoint and hardens it for real local Flickr validation. The image-hosting configuration is provider-oriented so future providers can be added by configuration without changing the sync request shape.

Provider-selected dry run:

```http
POST /internal/image-hosting/item-inventories/100/sync?provider=flickr
```

Provider-selected real sync:

```http
POST /internal/image-hosting/item-inventories/100/sync?provider=flickr&dryRun=false
```

Retry a previously failed image sync row:

```http
POST /internal/image-hosting/item-inventories/100/sync?provider=flickr&dryRun=false&retryFailed=true
```

The legacy `externalServiceId` override is still accepted:

```http
POST /internal/image-hosting/item-inventories/100/sync?provider=flickr&dryRun=false&externalServiceId=10
```

The response includes the low-cardinality `outcome` plus manual-operation details such as uploaded, metadata-updated, skipped, and failed photo ids, album id/url, and failure messages.

If an already-hosted photo is reprocessed with the same normalized filename but changed Lightroom metadata, the ingest pipeline stores a new `metadataHash` and updates the local photo row as a replacement. The image-hosting sync then updates the hosted photo metadata when `metadataHash` differs from `metadataHashAtSync`, without re-uploading the image bytes.

### Local Flickr Configuration

`application-sandbox.yml` imports optional `${import-path}/flickr-configuration.yml` and `${import-path}/bitly-configuration.yml` files. Locally, `application-local.yml` sets `import-path: /dev/config`; Kubernetes sets `import-path: /etc/.credentials`.

Example local `/dev/config/flickr-configuration.yml`:

```yaml
lego:
  image-hosting:
    providers:
      flickr:
        enabled: true

flickr:
  application-name: lego-data-ingress
  client-config-dir: C:\Users\tvatt\.credentials\flickr.api
  client-config-file: flickr-client-api-keys.json
  debug-request: false
  debug-stream: false
```

For Kubernetes, keep the same logical shape and let Infisical project the file into `/etc/.credentials/flickr-configuration.yml`.

Example local `/dev/config/bitly-configuration.yml`:

```yaml
bitly:
  access-token: your-bitly-token
  group-guid: your-bitly-group-guid
```

For Kubernetes, project the same logical shape into `/etc/.credentials/bitly-configuration.yml`. Bitly is optional while the scheduled Flickr sync runs in dry-run mode, but it is required before enabling apply mode because new Flickr albums need generated short URLs and adopted albums should recover existing short URLs.

### Kubernetes Flickr Sync Rollout

The Kubernetes profile enables scheduled Flickr sync with conservative dry-run defaults:

```yaml
lego:
  image-hosting:
    sync:
      scheduled:
        enabled: true
        apply: false
```

With `apply: false`, the job selects candidates, builds sync plans, logs actions, and emits metrics without creating Flickr albums, uploading photos, updating Flickr metadata, or writing DB repair state. This keeps the pod readiness check meaningful while allowing the first deployment to validate DB, S3, Flickr, and candidate selection safely.

Recommended rollout:

1. Deploy with `sandbox,kubernetes` profiles and confirm `/actuator/health/readiness` is `UP`.
2. Watch `image_hosting.sync_job.*`, `image_hosting.album_adoption`, and `image_hosting.short_url` logs.
3. Review `/actuator/prometheus` for scheduled sync, adoption, and Bitly counters.
4. Confirm candidate counts look correct for missing album links, missing photo links, failed sync rows, and metadata changes.
5. Project Bitly configuration before switching to apply mode.
6. Override `lego.image-hosting.sync.scheduled.apply=true` only after the dry-run output looks correct.

### Logging

The sync path emits structured event-style logs with stable names:

- `image_hosting.sync.started`
- `image_hosting.sync.completed`
- `image_hosting.sync.failed`
- `image_hosting.photo_upload.started`
- `image_hosting.photo_upload.completed`
- `image_hosting.photo_upload.skipped`
- `image_hosting.photo_upload.failed`
- `image_hosting.photo_metadata_update.started`
- `image_hosting.photo_metadata_update.completed`
- `image_hosting.photo_metadata_update.failed`
- `image_hosting.album.create.started`
- `image_hosting.album.create.completed`
- `image_hosting.album.create.failed`
- `image_hosting.album_membership.update.started`
- `image_hosting.album_membership.update.completed`
- `image_hosting.album_membership.update.failed`

Logs include provider, external service id, item inventory id, dry-run/retry flags, counts, provider ids, and elapsed time. They intentionally avoid credential values.

### Prometheus Metrics

Metrics use low-cardinality tags only. Do not add inventory ids, photo ids, album ids, UUIDs, or exception messages as tags.

Prometheus exposes the counters and timer from these Micrometer meters:

- `image_hosting_sync_total{provider,outcome,dry_run}`
- `image_hosting_photo_upload_total{provider,result}`
- `image_hosting_album_operation_total{provider,operation,result}`
- `image_hosting_sync_duration_seconds_*{provider,outcome,dry_run}`

Kubernetes exposes Prometheus at `/actuator/prometheus`. Local profile currently exposes all actuator endpoints for validation.

### Smoke Validation

1. Pick an `item_inventory_id` with processed `item_inventory_photo` rows and valid S3 objects.
2. Start the service with Flickr enabled through `/dev/config/flickr-configuration.yml`.
3. Run a dry run and confirm `outcome=DRY_RUN` and `photosDiscovered > 0`.
4. Run a real sync with `dryRun=false`.
5. Confirm Flickr has the uploaded photos and album/photoset membership.
6. Confirm the database has rows in `external_image`, `external_image_album`, and `external_image_album_image`.
7. Check logs for the event names above.
8. Check `/actuator/prometheus` for the image-hosting metrics.

Useful verification queries:

```sql
select * from external_image_album where item_inventory_id = 100 and external_service_id = 10;
select * from external_image where external_service_id = 10 and item_inventory_photo_id in (
    select item_inventory_photo_id from item_inventory_photo where item_inventory_id = 100
);
select ai.* from external_image_album_image ai
join external_image_album a on ai.external_image_album_id = a.external_image_album_id
where a.item_inventory_id = 100 and a.external_service_id = 10
order by ai.sort_order;
```

Batch scheduling, delete sync, replacement sync, and broad backfills remain deferred until the real manual path is stable.
