# docker build -t astra/cdc:dev astra-cdc-rust
FROM rust:1.95-slim-trixie AS build
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN cargo build --release --locked

FROM debian:trixie-slim
RUN useradd --uid 1000 --no-create-home cdc
COPY --from=build /src/target/release/astra-cdc /usr/local/bin/astra-cdc
COPY cdc-routes.yaml /etc/astra/cdc-routes.yaml
ENV CDC_ROUTES=/etc/astra/cdc-routes.yaml RUST_LOG=info
USER 1000
ENTRYPOINT ["/usr/local/bin/astra-cdc"]
