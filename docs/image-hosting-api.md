# Image Hosting API Guide

This document describes the internal image-hosting endpoints exposed by
`lego-data-ingress`, with an emphasis on the Flickr Integration Phase 3
workflows.

The runtime OpenAPI document is still available when the application is running:

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

This Markdown guide is intentionally more detailed than the generated OpenAPI
view. It explains when to use each endpoint, which parameters are required, what
the response means, and how the endpoints fit together.

## Scope

All endpoints documented here are internal operational endpoints under:

```http
/internal/image-hosting
```

They are meant for local validation, manual repair, operational inspection, and
controlled administrative use. They are not public customer-facing APIs.

There are currently no request-body parameters for these endpoints. All inputs
are path variables or query parameters.

## Provider Selection

Most endpoints accept the same provider-selection query parameters.

| Parameter | Location | Required | Default | Valid values | Description |
| --- | --- | --- | --- | --- | --- |
| `provider` | Query | No | `lego.image-hosting.default-provider`, currently `flickr` unless overridden by configuration | A configured key under `lego.image-hosting.providers`, for example `flickr` | Selects the image-hosting provider configuration. Use this when you want the endpoint to resolve provider-specific defaults such as external service id, display name, and metrics tag. |
| `externalServiceId` | Query | No | Provider `external-service-id`, otherwise `lego.image-hosting.sync.external-service-id`, currently `10` by default | Integer matching an `external_service` row | Overrides the external service id used for DB rows in `external_image`, `external_image_album`, and related tables. For Flickr this is normally `10`. |

When both values are omitted, the configured default provider is used. When
`provider` is supplied and `externalServiceId` is omitted, the provider's
configured external service id is used. When `externalServiceId` is supplied, it
overrides the configured id.

## Remote Snapshot Parameters

Endpoints that read Flickr state also accept these query parameters.

| Parameter | Location | Required | Default | Valid values | Description |
| --- | --- | --- | --- | --- | --- |
| `userId` | Query | No | Provider/client configuration where supported | Flickr user id, for example `144144385@N08` | Optional explicit user id for provider album listing. Use it when the configured provider cannot infer the user id or when validating against a specific Flickr account. |
| `albumPageSize` | Query | No | `500` | Positive integer | Page size used when listing remote albums. Larger values reduce paging calls. Keep at or below provider limits. |
| `photoPageSize` | Query | No | `500` | Positive integer | Page size used when listing photos inside a remote album. Larger values reduce paging calls. Keep at or below provider limits. |

## Recommended Workflow

For normal manual validation of one item inventory:

1. Preview the generated item description.
2. Build a sync plan.
3. Review every action in the plan.
4. Apply the plan only if no unexpected `BLOCKED` or `REQUIRES_REVIEW` actions are present.
5. Inspect the sync report.
6. Use repair-plan endpoints only when DB links to Flickr need to be repaired from remote state.

Typical safe read-only calls:

```http
GET /internal/image-hosting/item-inventories/335/generated-description
GET /internal/image-hosting/item-inventories/335/sync-plan
GET /internal/image-hosting/remote/albums/72157713151696202/snapshot
GET /internal/image-hosting/item-inventories/335/repair-plan
```

Typical apply call:

```http
POST /internal/image-hosting/item-inventories/335/sync-plan/apply
```

Use `allowReviewRequired=true` only after manually reviewing the plan and
accepting the risk described by the action.

## Endpoint Summary

| Method | Path | Purpose | Makes Flickr writes | Makes DB writes |
| --- | --- | --- | --- | --- |
| `GET` | `/item-inventories/{itemInventoryId}/generated-description` | Preview the durable DB-backed description that will be used for album/marketplace text. | No | No |
| `GET` | `/remote/albums/{albumId}/snapshot` | Read Flickr's current album/photos state. | No | No |
| `GET` | `/item-inventories/{itemInventoryId}/sync-plan` | Build a dry-run reconciliation plan comparing DB desired state to Flickr state. | No | No |
| `POST` | `/item-inventories/{itemInventoryId}/sync-plan/apply` | Build and apply the current sync plan. | Yes, for applicable actions | Yes |
| `GET` | `/item-inventories/{itemInventoryId}/repair-plan` | Build a dry-run DB repair plan from Flickr state. | No | No |
| `POST` | `/item-inventories/{itemInventoryId}/repair-plan/apply` | Build and apply a DB repair plan. | Usually no Flickr writes; may update membership for safe repair actions | Yes |
| `POST` | `/item-inventories/{itemInventoryId}/sync` | Legacy direct sync workflow. Prefer sync-plan endpoints for Phase 3. | Yes when `dryRun=false` | Yes when `dryRun=false` |

## Generated Description

### GET `/internal/image-hosting/item-inventories/{itemInventoryId}/generated-description`

Preview the generated item description from durable DB state. This is useful
before syncing because the generated description is used as the desired album
description in the DB-backed sync flow.

The description is composed from:

- BrickLink item number and name, when available.
- Inventory condition fields such as item, box, instructions, sealed, and built once.
- Durable photo captions stored on `item_inventory_photo`.
- Flickr album URL or short URL, when present in DB.

#### Parameters

| Parameter | Location | Required | Default | Valid values | Description |
| --- | --- | --- | --- | --- | --- |
| `itemInventoryId` | Path | Yes | None | Existing `item_inventory.item_inventory_id` | Selects the inventory item whose description should be generated. |
| `provider` | Query | No | Default provider | Configured provider key | Included in the response and used to resolve provider/external service context. |
| `externalServiceId` | Query | No | Resolved provider external service id | External service id integer | Selects the image-hosting service context for album URL lookup. |

#### Example

```http
GET /internal/image-hosting/item-inventories/335/generated-description
```

#### Response Fields

| Field | Description |
| --- | --- |
| `itemInventoryId` | Inventory item id used for the request. |
| `provider` | Resolved provider key. |
| `externalServiceId` | Resolved external service id. |
| `title` | Generated title, normally BrickLink number plus item name when available. |
| `description` | Full generated description text. |
| `photoUrl` | Short URL or hosted album URL, if already known. |
| `facts` | Structured facts that were included in the description. |
| `captions` | Durable photo captions included in the description. |

## Remote Album Snapshot

### GET `/internal/image-hosting/remote/albums/{albumId}/snapshot`

Read the provider's current remote state for one album. For Flickr, this reads
album metadata and the current photo membership for the requested Flickr
photoset/album id.

This endpoint is read-only. It does not change DB state and does not write to
Flickr.

#### Parameters

| Parameter | Location | Required | Default | Valid values | Description |
| --- | --- | --- | --- | --- | --- |
| `albumId` | Path | Yes | None | Flickr album/photoset id, for example `72157713151696202` | Remote album id to inspect. |
| `provider` | Query | No | Default provider | Configured provider key | Selects the image-hosting provider. |
| `externalServiceId` | Query | No | Resolved provider external service id | External service id integer | Included in response context. |
| `userId` | Query | No | Provider/client configuration where supported | Flickr user id | User id used when listing albums. |
| `albumPageSize` | Query | No | `500` | Positive integer | Page size used while searching/listing albums. |
| `photoPageSize` | Query | No | `500` | Positive integer | Page size used while listing photos in the album. |

#### Example

```http
GET /internal/image-hosting/remote/albums/72157713151696202/snapshot?provider=flickr
```

#### Response Fields

| Field | Description |
| --- | --- |
| `provider` | Resolved provider key. |
| `externalServiceId` | Resolved external service id. |
| `userId` | User id used for remote lookup, if supplied/resolved. |
| `requestedAlbumId` | Album id from the path. |
| `album` | Remote hosted album metadata, or `null` when not found. |
| `photos` | Remote hosted photos currently in the album. |
| `albumPagesRead` | Number of album listing pages read while resolving the album. |
| `photoPagesRead` | Number of photo listing pages read for the album. |
| `failureMessages` | Non-empty when remote lookup failed or was incomplete. |
| `albumFound` | Derived boolean indicating whether `album` is present. |
| `successful` | Derived boolean indicating whether `failureMessages` is empty. |

## Sync Plan

### GET `/internal/image-hosting/item-inventories/{itemInventoryId}/sync-plan`

Build a dry-run reconciliation plan. The plan compares DB desired state against
current Flickr remote state and lists actions that would bring Flickr into line
with the DB-backed desired state.

When the DB does not yet have a Flickr album id, normal sync first attempts
safe remote adoption before planning a new album. If exactly one existing
Flickr album matches the DB desired title, the plan contains DB repair actions
that link the album, photos, and membership rows back to the item inventory. If
multiple remote albums match, the plan is blocked as ambiguous. If no remote
album matches, the planner falls back to `CREATE_ALBUM`.

This endpoint is read-only. It does not upload photos, update Flickr metadata,
update album membership, or write repair information to the DB.

#### Parameters

| Parameter | Location | Required | Default | Valid values | Description |
| --- | --- | --- | --- | --- | --- |
| `itemInventoryId` | Path | Yes | None | Existing `item_inventory.item_inventory_id` | Inventory item to reconcile. |
| `provider` | Query | No | Default provider | Configured provider key | Selects the image-hosting provider. |
| `externalServiceId` | Query | No | Resolved provider external service id | External service id integer | Selects DB provider rows to use as desired state. |
| `userId` | Query | No | Provider/client configuration where supported | Flickr user id | Used for remote album listing. |
| `albumPageSize` | Query | No | `500` | Positive integer | Page size for remote album listing. |
| `photoPageSize` | Query | No | `500` | Positive integer | Page size for remote photo listing. |

#### Example

```http
GET /internal/image-hosting/item-inventories/335/sync-plan
```

#### Planned Actions

The plan may contain these action types:

| Action type | Meaning | Typical safety |
| --- | --- | --- |
| `UPLOAD_PHOTO` | DB has a processed photo without a Flickr photo id; applying uploads the S3 image to Flickr and stores the returned id. | `SAFE_AUTOMATIC` |
| `CREATE_ALBUM` | DB desired state has no hosted album id and no existing matching Flickr album was found; applying creates a Flickr album/photoset. | `SAFE_AUTOMATIC`, or `BLOCKED` when desired album state is missing |
| `UPDATE_PHOTO_METADATA` | Photo metadata hash changed since the last sync; applying updates Flickr title/description/tags. | `SAFE_AUTOMATIC` |
| `UPDATE_ALBUM_METADATA` | Desired album title or description differs from Flickr. | `SAFE_AUTOMATIC` |
| `UPDATE_ALBUM_MEMBERSHIP` | Flickr album membership/order differs from DB desired photo ids. | `SAFE_AUTOMATIC` when no unexpected remote-only photos would be removed; otherwise `REQUIRES_REVIEW` |
| `FIX_PRIMARY_PHOTO` | Flickr primary photo differs from DB desired primary photo. | `SAFE_AUTOMATIC` or `REQUIRES_REVIEW` depending on membership risk |
| `REPAIR_ALBUM_ID` | DB album id points to a Flickr album that was not found, or normal sync found one safe existing Flickr album to adopt for an item with missing DB linkage. | `SAFE_AUTOMATIC` for unique adoption, otherwise usually `REQUIRES_REVIEW` or `BLOCKED` |
| `REPAIR_PHOTO_ID` | DB photo id points to a Flickr photo that was not found in the album snapshot, or normal sync found one safe existing Flickr photo title match during album adoption. | `SAFE_AUTOMATIC` for unique adoption, otherwise usually `REQUIRES_REVIEW` |

Older sync model enum values such as `REBUILD_MANIFEST`, `WRITE_MANIFEST`, and
`SHORTEN_ALBUM_URL` may exist in shared models for legacy compatibility, but the
Phase 3 DB-backed sync planner does not use local AlbumManifest JSON.

#### Response Fields

| Field | Description |
| --- | --- |
| `planId` | Unique id for this generated plan. |
| `mode` | `DRY_RUN` for sync-plan responses. |
| `createdAt` | Local server timestamp when the plan was generated. |
| `actions` | Ordered list of proposed `SyncAction` records. Empty means no sync work is currently required. |
| `dryRun` | Derived boolean indicating whether `mode` is `DRY_RUN`. |
| `reviewRequiredActionCount` | Count of actions with `safety=REQUIRES_REVIEW`. |
| `blockedActionCount` | Count of actions with `safety=BLOCKED`. |

#### SyncAction Fields

| Field | Description |
| --- | --- |
| `actionId` | Stable display id within the plan, for example `001-update-album-metadata`. |
| `type` | Action type listed above. |
| `safety` | `SAFE_AUTOMATIC`, `REQUIRES_REVIEW`, or `BLOCKED`. |
| `albumId` | Remote hosted album id associated with the action, when known. |
| `photoId` | Remote hosted photo id associated with the action, when known. |
| `filename` | Local DB photo filename associated with the action, when applicable. |
| `manifestPath` | Legacy field retained in the shared model. Phase 3 DB-backed sync should not rely on local manifest paths. |
| `description` | Human-readable explanation of the action. |
| `attributes` | Action-specific details used for review and execution. |
| `blocked` | Derived boolean. `true` when `safety=BLOCKED`. Blocked actions are not applied. |

Common `attributes` include:

| Attribute | Meaning |
| --- | --- |
| `provider` | Resolved provider key. |
| `externalServiceId` | External service id used for DB state. |
| `itemInventoryId` | Inventory item id. |
| `externalImageAlbumId` | DB row id for `external_image_album`, when available. |
| `itemInventoryPhotoId` | DB row id for `item_inventory_photo`, when applicable. |
| `externalImageId` | DB row id for `external_image`, when applicable. |
| `externalAlbumId` | Remote hosted album id. |
| `externalServiceImageId` | Remote hosted photo id. |
| `desiredTitle` | Title desired from DB/generated policy. |
| `remoteTitle` | Current remote title. |
| `desiredDescription` | Description desired from DB/generated policy. |
| `remoteDescription` | Current remote description. |
| `metadataHash` | Current DB metadata hash for a photo. |
| `metadataHashAtSync` | Metadata hash last known to have been synced to Flickr. |
| `desiredPhotoIds` | Comma-separated desired Flickr photo ids in DB sort order. |
| `remotePhotoIds` | Comma-separated Flickr photo ids currently in remote membership order. |
| `desiredOnlyPhotoIds` | Photo ids present in DB desired state but missing remotely. |
| `remoteOnlyPhotoIds` | Photo ids present remotely but not in DB desired state. These require careful review before removal. |
| `desiredPrimaryPhotoId` | Flickr photo id that should be primary according to DB. |
| `remotePrimaryPhotoId` | Flickr photo id currently primary remotely. |
| `primary` | Whether the local DB photo is the desired primary photo. |
| `failureMessages` | Remote snapshot failure details when planning is blocked. |

## Apply Sync Plan

### POST `/internal/image-hosting/item-inventories/{itemInventoryId}/sync-plan/apply`

Build the current sync plan and immediately execute applicable actions. This is
the main Phase 3 apply endpoint.

The endpoint does not accept a request body. It builds a fresh plan at apply
time using the current DB and current Flickr state.

#### Parameters

| Parameter | Location | Required | Default | Valid values | Description |
| --- | --- | --- | --- | --- | --- |
| `itemInventoryId` | Path | Yes | None | Existing `item_inventory.item_inventory_id` | Inventory item to reconcile and apply. |
| `provider` | Query | No | Default provider | Configured provider key | Selects the image-hosting provider. |
| `externalServiceId` | Query | No | Resolved provider external service id | External service id integer | Selects DB provider rows to use and write. |
| `userId` | Query | No | Provider/client configuration where supported | Flickr user id | Used for remote album listing. |
| `albumPageSize` | Query | No | `500` | Positive integer | Page size for remote album listing. |
| `photoPageSize` | Query | No | `500` | Positive integer | Page size for remote photo listing. |
| `allowReviewRequired` | Query | No | `false` | `true` or `false` | When `false`, actions with `safety=REQUIRES_REVIEW` are reported as `BLOCKED` and are not executed. When `true`, review-required actions are allowed to execute. `BLOCKED` actions still do not execute. |

#### Example

```http
POST /internal/image-hosting/item-inventories/335/sync-plan/apply
```

Apply a plan that includes reviewed actions:

```http
POST /internal/image-hosting/item-inventories/335/sync-plan/apply?allowReviewRequired=true
```

#### Execution Behavior

The executor supports these action types:

- `UPLOAD_PHOTO`
- `CREATE_ALBUM`
- `UPDATE_PHOTO_METADATA`
- `UPDATE_ALBUM_METADATA`
- `UPDATE_ALBUM_MEMBERSHIP`
- `FIX_PRIMARY_PHOTO`
- `REPAIR_ALBUM_ID`
- `REPAIR_PHOTO_ID`

Unsupported or legacy action types are skipped.

For album lifecycle actions, the executor also maintains `external_image_album.short_url`
where possible:

- `CREATE_ALBUM` calls Bitly after Flickr returns the new album URL and stores
  the generated short URL.
- Existing-album adoption through `REPAIR_ALBUM_ID` looks up the Flickr album
  URL in your Bitly account and stores the recovered short URL when one is
  found.
- Bitly lookup/generation failures are reported in the action result message
  but do not fail the Flickr sync action.

Manual historical backfill for existing DB album rows remains a migration-tool
workflow in `lego-data-migration`. The ingress application only performs short
URL recovery/generation as part of normal sync-plan apply execution.

Transient provider errors are retried according to
`lego.image-hosting.sync.retry`. Retryable error types are:

- `SERVICE_UNAVAILABLE`
- `RATE_LIMITED`
- `NETWORK_ERROR`
- `WRITE_FAILED`

Validation, auth, not-found, duplicate, upload-limit, and file-size errors are
not retried.

#### Response Fields

The response is a `SyncReport`.

| Field | Description |
| --- | --- |
| `reportId` | Unique id for this execution report. |
| `planId` | Plan id that was built and applied. |
| `mode` | `APPLY` for apply responses. |
| `startedAt` | Local server timestamp when execution started. |
| `finishedAt` | Local server timestamp when execution finished. |
| `results` | Ordered list of action execution results. |
| `failures` | Derived list of failed or blocked action results. |
| `summary` | Counts by result status. |

#### SyncActionResult Fields

| Field | Description |
| --- | --- |
| `actionId` | Action id from the plan. |
| `type` | Action type executed or skipped. |
| `status` | `SUCCEEDED`, `FAILED`, `BLOCKED`, `SKIPPED`, or `PLANNED`. |
| `albumId` | Remote album id related to the result, when applicable. |
| `photoId` | Remote photo id related to the result, when applicable. |
| `responseCode` | Provider response/error code when one exists. |
| `message` | Human-readable execution result. |
| `errorType` | Provider error category for failed/blocked results. |
| `attempts` | Number of provider attempts made. |
| `retried` | Whether at least one retry happened. |
| `retryable` | Whether the final provider response was considered retryable. |
| `startedAt` | Local server timestamp for action start. |
| `finishedAt` | Local server timestamp for action finish. |
| `failure` | Derived boolean. `true` when status is `FAILED` or `BLOCKED`. |

## DB Repair Plan

### GET `/internal/image-hosting/item-inventories/{itemInventoryId}/repair-plan`

Build a DB repair plan from remote Flickr state. This is the replacement for
gen-1 manifest rebuild workflows.

Use this when Flickr already has the album/photos but local DB links are stale,
missing, or inconsistent. The repair plan tries to identify remote albums/photos
by current DB ids first and then by safer fallbacks such as title matching.

This endpoint is read-only.

#### Parameters

| Parameter | Location | Required | Default | Valid values | Description |
| --- | --- | --- | --- | --- | --- |
| `itemInventoryId` | Path | Yes | None | Existing `item_inventory.item_inventory_id` | Inventory item whose DB image-hosting links should be analyzed for repair. |
| `provider` | Query | No | Default provider | Configured provider key | Selects the image-hosting provider. |
| `externalServiceId` | Query | No | Resolved provider external service id | External service id integer | Selects DB provider rows to inspect. |
| `userId` | Query | No | Provider/client configuration where supported | Flickr user id | Used for remote album listing. |
| `albumPageSize` | Query | No | `500` | Positive integer | Page size for remote album listing. |
| `photoPageSize` | Query | No | `500` | Positive integer | Page size for remote photo listing. |

#### Example

```http
GET /internal/image-hosting/item-inventories/335/repair-plan
```

#### Repair Actions

| Action type | Meaning |
| --- | --- |
| `REPAIR_ALBUM_ID` | Clear or repair stale/missing DB album linkage based on remote state. |
| `REPAIR_PHOTO_ID` | Clear or repair stale/missing DB photo linkage based on remote state. |
| `UPDATE_ALBUM_MEMBERSHIP` | Update DB album membership rows to match safely identified remote state. |
| `FIX_PRIMARY_PHOTO` | Update DB primary membership to match safely identified remote state. |

Actions that depend on ambiguous title matches or incomplete remote data may be
`REQUIRES_REVIEW` or `BLOCKED`.

## Apply DB Repair Plan

### POST `/internal/image-hosting/item-inventories/{itemInventoryId}/repair-plan/apply`

Build and execute the current DB repair plan.

This endpoint can update DB rows such as `external_image`,
`external_image_album`, and `external_image_album_image`. It is intended for
repairing local DB state from Flickr, not for normal publishing.

#### Parameters

| Parameter | Location | Required | Default | Valid values | Description |
| --- | --- | --- | --- | --- | --- |
| `itemInventoryId` | Path | Yes | None | Existing `item_inventory.item_inventory_id` | Inventory item to repair. |
| `provider` | Query | No | Default provider | Configured provider key | Selects the image-hosting provider. |
| `externalServiceId` | Query | No | Resolved provider external service id | External service id integer | Selects DB provider rows to inspect and update. |
| `userId` | Query | No | Provider/client configuration where supported | Flickr user id | Used for remote album listing. |
| `albumPageSize` | Query | No | `500` | Positive integer | Page size for remote album listing. |
| `photoPageSize` | Query | No | `500` | Positive integer | Page size for remote photo listing. |
| `allowReviewRequired` | Query | No | `false` | `true` or `false` | When `false`, review-required repair actions are blocked. When `true`, reviewed repair actions may execute. `BLOCKED` actions still do not execute. |

#### Example

```http
POST /internal/image-hosting/item-inventories/335/repair-plan/apply
```

## Legacy Direct Sync

### POST `/internal/image-hosting/item-inventories/{itemInventoryId}/sync`

Run the older direct sync workflow. This endpoint predates Phase 3
reconciliation plans. It is still useful for compatibility and manual validation,
but the preferred Phase 3 workflow is:

1. `GET /sync-plan`
2. Review the plan.
3. `POST /sync-plan/apply`

#### Parameters

| Parameter | Location | Required | Default | Valid values | Description |
| --- | --- | --- | --- | --- | --- |
| `itemInventoryId` | Path | Yes | None | Existing `item_inventory.item_inventory_id` | Inventory item to sync. |
| `dryRun` | Query | No | `true` | `true` or `false` | When `true`, validates and reports without Flickr writes or DB writes. When `false`, uploads/updates Flickr and writes DB sync rows. |
| `provider` | Query | No | Default provider | Configured provider key | Selects the image-hosting provider. |
| `externalServiceId` | Query | No | Resolved provider external service id | External service id integer | Selects DB provider rows to use/write. |
| `retryFailed` | Query | No | `false` | `true` or `false` | When `false`, previously failed image rows are skipped. When `true`, the direct sync path retries previously failed image rows. |

#### Examples

Dry run:

```http
POST /internal/image-hosting/item-inventories/335/sync
```

Real direct sync:

```http
POST /internal/image-hosting/item-inventories/335/sync?dryRun=false
```

Retry failed direct sync rows:

```http
POST /internal/image-hosting/item-inventories/335/sync?dryRun=false&retryFailed=true
```

#### Response Fields

| Field | Description |
| --- | --- |
| `itemInventoryId` | Inventory item id used for the request. |
| `provider` | Resolved provider key. |
| `externalServiceId` | Resolved external service id. |
| `dryRun` | Whether the request ran in dry-run mode. |
| `retryFailed` | Whether previously failed rows were eligible for retry. |
| `outcome` | `SUCCESS`, `PARTIAL_FAILURE`, `FAILED`, or `DRY_RUN`. |
| `photosDiscovered` | Number of item inventory photos found locally. |
| `photosUploaded` | Number of photos uploaded to the provider. |
| `photosMetadataUpdated` | Number of hosted photo metadata updates performed. |
| `photosSkipped` | Number of photos skipped because they were already synced or otherwise not eligible. |
| `photosFailed` | Number of photo-level failures. |
| `externalImageAlbumId` | DB row id for the album record, when present. |
| `albumId` | Remote hosted album id, when present. |
| `albumUrl` | Remote hosted album URL, when present. |
| `albumCreated` | Whether a hosted album was created during the run. |
| `membershipUpdated` | Whether hosted album membership was updated during the run. |
| `uploadedPhotoIds` | Local `item_inventory_photo_id` values uploaded during the run. |
| `metadataUpdatedPhotoIds` | Local `item_inventory_photo_id` values whose hosted metadata was updated. |
| `skippedPhotoIds` | Local `item_inventory_photo_id` values skipped during the run. |
| `failedPhotoIds` | Local `item_inventory_photo_id` values that failed during the run. |
| `failureMessages` | Human-readable failure details. |

## Safety Values

| Safety | Meaning |
| --- | --- |
| `SAFE_AUTOMATIC` | The planner considers the action safe to apply automatically. |
| `REQUIRES_REVIEW` | The action may be valid, but it has enough risk to require manual review. By default apply endpoints block these actions unless `allowReviewRequired=true`. |
| `BLOCKED` | The action is not safe to execute. Apply endpoints do not execute blocked actions. |

## Result Status Values

| Status | Meaning |
| --- | --- |
| `PLANNED` | The action exists in a plan but has not been executed. |
| `SKIPPED` | The executor intentionally skipped the action, usually because the action type is unsupported by that executor. |
| `SUCCEEDED` | The action completed successfully. |
| `FAILED` | The action executed but failed. |
| `BLOCKED` | The action was not executed because it was blocked or required review that was not allowed. |

## Error Types

Provider errors in `SyncReport` results use these categories.

| Error type | Retryable | Meaning |
| --- | --- | --- |
| `ALBUM_NOT_FOUND` | No | Remote album/photoset was not found. |
| `PHOTO_NOT_FOUND` | No | Remote photo was not found. |
| `PRIMARY_PHOTO_NOT_FOUND` | No | Provider rejected the requested primary photo id. |
| `AUTHENTICATION_FAILED` | No | Provider credentials/token are invalid. |
| `AUTHORIZATION_FAILED` | No | Credentials are valid but not allowed to perform the operation. |
| `INVALID_API_KEY` | No | Provider API key is invalid. |
| `SERVICE_UNAVAILABLE` | Yes | Provider is temporarily unavailable. |
| `RATE_LIMITED` | Yes | Provider throttled the request. |
| `NETWORK_ERROR` | Yes | Network/timeout style failure. |
| `WRITE_FAILED` | Yes | Provider write operation failed transiently. |
| `VALIDATION_FAILED` | No | Local or provider validation failed. |
| `EMPTY_PHOTO_LIST` | No | Attempted album/membership operation had no photos. |
| `DUPLICATE_UPLOAD` | No | Provider reported a duplicate upload. |
| `UPLOAD_LIMIT_EXCEEDED` | No | Provider upload limit was exceeded. |
| `FILE_TOO_LARGE` | No | Photo exceeded provider limits. |
| `UNKNOWN` | No | Error could not be classified. |

## Configuration Reference

Relevant configuration lives under `lego.image-hosting`.

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
      temp-directory: ${java.io.tmpdir}/lego-data-ingress-image-hosting
      scheduled:
        enabled: false
        batch-size: 25
        concurrency: 5
        retry-failed: true
        apply: false
        fixed-delay-ms: 300000
        initial-delay-ms: 30000
        lock-at-most-for: 10m
        lock-at-least-for: 0s
      retry:
        enabled: true
        max-attempts: 3
        initial-backoff-ms: 500
        backoff-multiplier: 2.0
        max-backoff-ms: 5000
    readiness:
      enabled: true
      require-scheduled-sync-enabled: false
      require-bitly-when-apply-enabled: true
    publishing:
      photo:
        title-template: "{captionOrFilename}"
        description-template: "{captionOrTitle}"
        tags: []
        public-flag: true
        friend-flag: false
        family-flag: false
        hidden: false
        safety-level: safe
```

Scheduled sync candidate selection includes item inventories with processed DB
photos and missing Flickr album links, missing Flickr photo links, failed or
pending image-hosting sync rows, or metadata hash drift.

The base application default keeps scheduled sync disabled. The Kubernetes
profile enables it in dry-run mode with `enabled: true`, `apply: false`,
`batch-size: 25`, and `concurrency: 2`. In that mode, the job builds and logs
sync plans without executing Flickr writes or DB repair/write actions. Set
`apply: true` only after reviewing dry-run logs, candidate counts, readiness,
and metrics.

Kubernetes deployments include the custom actuator health component
`imageHostingReadiness` in the readiness probe. It validates the
configuration needed for scheduled Flickr sync:

| Component | Required When | What Is Checked |
| --- | --- | --- |
| `scheduled-sync` | `lego.image-hosting.readiness.require-scheduled-sync-enabled=true` | Scheduled sync is enabled, and batch size/concurrency are greater than zero. |
| `database` | Scheduled sync is enabled or required | `spring.datasource.database-key-name` is configured and a `DataSource` bean exists. |
| `s3` | Scheduled sync is enabled or required | `lego.s3.url`, `lego.s3.access-key`, and `lego.s3.secret-key` are configured. |
| `flickr` | Scheduled sync is enabled or required | Flickr provider config, external service id, user id, API key/secret, OAuth token, and OAuth token secret are configured. |
| `bitly` | Scheduled sync is enabled with `apply=true` | Bitly base URL, access token, and group GUID are configured. Missing Bitly config is only a warning in dry-run mode. |

Startup logs emit one summary line and one line per readiness check using the
`image_hosting.readiness.*` log keys. Secret values are never logged; only
missing property names are reported.

Kubernetes uses the `sandbox,kubernetes` profiles and imports projected
configuration files from `/etc/.credentials`. The sandbox profile imports
database, Kafka, and S3 config as required files and Flickr/Bitly config as
optional files. Flickr configuration is still required by image-hosting
readiness when scheduled sync is enabled or required. Bitly configuration is
required only when scheduled sync runs with `apply=true`.

## Observability

Phase 4 scheduled sync emits Prometheus metrics through the standard actuator
Prometheus endpoint. The Phase 4.5 adoption-specific counters are:

| Metric | Labels | Meaning |
| --- | --- | --- |
| `image_hosting_album_creation` | `provider`, `result` | Counts successful album creation. `result=created` means a new Flickr album was created and persisted. |
| `image_hosting_album_adoption` | `provider`, `result` | Counts DB adoption outcomes for existing remote albums. Expected results include `adopted`, `ambiguous`, `no_match`, `blocked`, and `failed`. |
| `image_hosting_short_url` | `provider`, `operation`, `result` | Counts Bitly short URL recovery/generation outcomes. `operation` is `recover` or `generate`; expected results include `recovered`, `generated`, `lookup_miss`, `disabled`, and `failed`. |

The same events also emit structured logs using:

| Log Key | Meaning |
| --- | --- |
| `image_hosting.album_creation` | Album creation outcome. |
| `image_hosting.album_adoption` | Adoption outcome, including ambiguity and no-match cases. |
| `image_hosting.short_url` | Bitly recovery/generation outcome. |

Publishing template variables:

| Variable | Meaning |
| --- | --- |
| `{captionOrFilename}` | Photo caption when present, otherwise filename. |
| `{captionOrTitle}` | Photo caption when present, otherwise fallback title. |
| `{caption}` | Photo caption or empty string. |
| `{filename}` | Stored photo filename or empty string. |
| `{itemInventoryPhotoId}` | Local photo row id or empty string. |
| `{md5}` | Stored photo MD5 or empty string. |

## Notes And Caveats

- Phase 3 sync is DB/S3-backed. It does not depend on local AlbumManifest JSON.
- Apply endpoints build a fresh plan at execution time. If DB or Flickr changes
  between a `GET /sync-plan` call and a later `POST /sync-plan/apply` call, the
  applied plan may differ from the one previously reviewed.
- Use `remoteOnlyPhotoIds` carefully. Those are Flickr photos currently in the
  album that are not part of DB desired state. Removing or overwriting membership
  may be correct, but it deserves review.
- `LocalDateTime` fields currently serialize as ISO local timestamps without a
  timezone offset, for example `2026-05-27T14:31:22.7515496`.
- The old direct `/sync` endpoint remains available, but the plan/report
  endpoints are preferred for Phase 3 manual operations.
