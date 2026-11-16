# lego-data-ingress

The LCMS worker service: BrickLink pricing (want list, the shared market crawler, Reprice), marketplace sync to
BrickLink, store checks, order staging and fulfillment, photo and catalog ingestion from S3/Kafka, and Flickr
image hosting.

- **[runbook.md](runbook.md)** — what it does, the safety rules, what runs in which environment, how to
  operate and diagnose it, and where each setting is defined. Start here.
- **[docs/image-hosting-api.md](docs/image-hosting-api.md)** — the internal image-hosting endpoints.
- OpenAPI, when the service runs: `/swagger-ui/index.html` and `/v3/api-docs`.

Build and test: `mvn clean test` (Java 21). Local runs use the profiles `sandbox,local` and read credentials
from `/dev/config`. Feature branches deploy the SANDBOX preview, `develop` deploys DEV, `main` deploys PROD.

The earlier phase-by-phase README, runbook and inventory-sync playbook are in
`00-workspace/docs/legacy/lego-data-ingress/` in the workspace repository. They describe removed jobs and tables.
