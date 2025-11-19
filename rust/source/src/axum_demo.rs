use std::sync::Arc;

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
  encoding::{EncodeLabelSet, EncodeLabelValue, text::encode},
  metrics::{counter::Counter, family::Family},
  registry::Registry,
};
use tokio::{net::TcpListener, sync::Mutex};
use tower_http::trace::TraceLayer;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};
use uuid::uuid;

#[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelValue)]
pub enum Method {
  GET,
  POST,
}

#[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
pub struct Labels {
  pub method: Method,
  pub lb_id: String,
}

#[derive(Debug)]
pub struct Metrics {
  requests: Family<Labels, Counter>,
}

#[derive(Debug)]
pub struct AppState {
  pub registry: Registry,
}

async fn metrics_handler(State(state): State<Arc<Mutex<AppState>>>) -> impl IntoResponse {
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

async fn handler_404() -> impl IntoResponse {
  (StatusCode::NOT_FOUND, "Not found\n")
}

async fn fluctuate_metrics_middleware(
  State(metrics): State<Arc<Mutex<Metrics>>>,
  req: Request,
  nxt: Next,
) -> Response {
  let lb_ids = [
    uuid!("9d6891a9-e46e-4ba0-9eb6-431910f21f14"),
    uuid!("eaae661b-6fde-4a66-a3c3-40c6ac613d63"),
  ];
  let metrics = metrics.lock().await;
  for lb_id in lb_ids {
    metrics
      .requests
      .get_or_create(&Labels {
        method: Method::GET,
        lb_id: lb_id.to_string(),
      })
      .inc_by(rand::random_range(0..10));
    metrics
      .requests
      .get_or_create(&Labels {
        method: Method::POST,
        lb_id: lb_id.to_string(),
      })
      .inc_by(rand::random_range(0..5));
  }
  let res = nxt.run(req).await;
  res
}

pub async fn demo() {
  tracing_subscriber::registry()
    .with(
      tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| {
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
    requests: Family::default(),
  };
  let mut state = AppState {
    registry: Registry::default(),
  };
  state
    .registry
    .register("requests", "Count of requests", metrics.requests.clone());
  let metrics = Arc::new(Mutex::new(metrics));
  let state = Arc::new(Mutex::new(state));

  let router = Router::new()
    .route("/metrics", get(metrics_handler))
    .with_state(state)
    .fallback(handler_404)
    .route_layer(middleware::from_fn_with_state(
      metrics.clone(),
      fluctuate_metrics_middleware,
    ))
    .with_state(metrics)
    .layer(TraceLayer::new_for_http());

  let port = 8081;
  let listener = TcpListener::bind(format!("0.0.0.0:{}", port))
    .await
    .unwrap();
  axum::serve(listener, router).await.unwrap();
}
