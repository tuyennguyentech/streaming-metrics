# streaming-metrics

![Architecture](./docs/gif/architecture.gif)

## Development

- Run `process` Flink job at root directory: `./gradlew run --args="--env local`.
- Currently have `local`(run locally), `dev`(submit to Flink Job Manager in compose) environment.
- Build: `./gradlew clean shadowJar`.
- Stop a job and create savepoint: `root@25f8eb2196a2:/opt/flink# bin/flink stop --savepointPath file://$(pwd)/savepoints/ f7b8a6bb0a2f1efccc5a4416e7d6f284`.

## How are metrics processed

### Statement

Checkout/order service:

- Each failure request increases counter.
- Each metric:
  - SRE needs to know which endpoint is error.
  - Business needs to know whether important service is running properly.

### Input metrics

``` bash
order_create_failed_total{
  pod="checkout-6c8f9",
  endpoint="/orders",
  error_type="timeout"
} 37
```

### Metadata for enrichment

``` json
{
  "pod": "checkout-6c8f9",
  "service": "checkout-service",
  "team": "ecommerce",
  "tier": "critical"
}

```

### Metrics Enrichments

``` bash
order_create_failed_total{
  service="checkout-service",
  team="ecommerce",
  tier="critical",
  endpoint="/orders",
  error_type="timeout"
} 37
```

### Metric Duplication for Multiple View

- Operational view:

``` bash
order_create_failed_total{
  view="operational",
  service="checkout-service",
  endpoint="/orders",
  error_type="timeout"
} 37
```

- Business view:

``` bash
order_create_failed_total{
  view="business",
  service="checkout-service",
  tier="critical"
} 37
```

### Summary

``` bash
Raw counter metrics
   → Enrich bằng metadata (service, tier)
       → Duplicate:
           - Operational view (chi tiết)
           - Business view (tổng hợp)
```
