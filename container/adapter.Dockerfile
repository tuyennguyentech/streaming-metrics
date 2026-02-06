FROM rust:1.92-alpine3.23 as builder

WORKDIR /app

RUN apk update && apk add --no-cache cmake build-base protoc protobuf-dev zlib zlib-static
COPY Cargo.toml Cargo.lock ./

RUN mkdir -p rust/source/src && echo "fn main() {}" > rust/source/src/main.rs
COPY rust/source/Cargo.toml rust/source/Cargo.toml
RUN mkdir -p rust/adapter/src && echo "fn main() {}" > rust/adapter/src/main.rs
COPY rust/adapter/Cargo.toml rust/adapter/Cargo.toml

RUN cargo fetch && cargo build -p adapter
RUN rm -rf rust

COPY rust ./rust/
COPY proto ./proto/

RUN cargo fetch

RUN cargo build --release -p adapter

FROM alpine:3.23

WORKDIR /app

COPY --from=builder /app/target/release/adapter ./

EXPOSE 1234

ENTRYPOINT [ "./adapter" ]