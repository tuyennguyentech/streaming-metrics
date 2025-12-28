pub mod server;

use std::{net::SocketAddr, sync::Arc};

use axum::{
  Router,
  body::Body,
  extract::{Request, State},
  http::{StatusCode, header::CONTENT_TYPE},
  middleware::{self, Next},
  response::{IntoResponse, Response},
  routing::get,
};
use prometheus_client::{
  encoding::{EncodeLabelSet, text::encode},
  metrics::{counter::Counter, family::Family},
  registry::Registry,
};
use tokio::{net::TcpListener, sync::Mutex};
use tower_http::trace::TraceLayer;
use tracing_subscriber::{
  layer::SubscriberExt, util::SubscriberInitExt,
};

#[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
struct OrderCreateFailedLabels {
  pod: String,
  endpoint: String,
  error_type: String,
}

#[derive(Debug)]
struct Metrics {
  order_create_failed: Family<OrderCreateFailedLabels, Counter>,
}

#[derive(Debug)]
struct AppState {
  registry: Registry,
}

async fn metrics_handler(
  State(state): State<Arc<Mutex<AppState>>>,
) -> impl IntoResponse {
  let state = state.lock().await;
  let mut buf = String::new();
  encode(&mut buf, &state.registry).unwrap();
  Response::builder()
    .status(StatusCode::OK)
    .header(
        CONTENT_TYPE,
        "application/openmetrics-text; version=1.0.0; charset=utf-8",
    )
    .body(Body::from(buf))
    .unwrap()
}

async fn handler_not_found() -> impl IntoResponse {
  (StatusCode::NOT_FOUND, "Not found\n")
}

async fn fluctuate_metrics_middleware(
  State(metrics): State<Arc<Mutex<Metrics>>>,
  req: Request,
  nxt: Next,
) -> Response {
  let pod = "checkout-6c8f9";
  let endpoint = "/orders";
  let error_type = "timeout";
  let metrics = metrics.lock().await;
  metrics
    .order_create_failed
    .get_or_create(&OrderCreateFailedLabels {
      pod: pod.to_string(),
      endpoint: endpoint.to_string(),
      error_type: error_type.to_string(),
    })
    .inc_by(rand::random_range(0..10));
  nxt.run(req).await
}

pub async fn run() {
  tracing_subscriber::registry()
    .with(
      tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| {
          format!(
            "{}=debug,tower_http=debug,axum::rejection=trace",
            env!("CARGO_CRATE_NAME")
          )
          .into()
        }),
    )
    .with(tracing_subscriber::fmt::layer())
    .init();

  let metrics = Metrics {
    order_create_failed: Family::default(),
  };
  let mut state = AppState {
    registry: Registry::default(),
  };
  state.registry.register(
    "order_create_failed",
    "Count of order create failed",
    metrics.order_create_failed.clone(),
  );

  let metrics = Arc::new(Mutex::new(metrics));
  let state = Arc::new(Mutex::new(state));

  let router = Router::new()
    .route("/metrics", get(metrics_handler))
    .with_state(state)
    .fallback(handler_not_found)
    .route_layer(middleware::from_fn_with_state(
      metrics.clone(),
      fluctuate_metrics_middleware,
    ))
    .layer(TraceLayer::new_for_http());
  let ip = [0, 0, 0, 0];
  let port = 8081;
  let addr = SocketAddr::from((ip, port));
  let listener = TcpListener::bind(addr).await.unwrap();
  axum::serve(listener, router).await.unwrap();
}
