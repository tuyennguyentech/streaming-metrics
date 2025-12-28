# streaming-metrics

![Architecture](./docs/gif/architecture.gif)

## Development

- Run `process` Flink job at root directory: `./gradlew run --args="--env local`.
- Currently have `local`(run locally), `dev`(submit to Flink Job Manager in compose) environment.

- Stop a job and create savepoint: `root@25f8eb2196a2:/opt/flink# bin/flink stop --savepointPath file://$(pwd)/savepoints/ f7b8a6bb0a2f1efccc5a4416e7d6f284`.
