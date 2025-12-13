mod message_broker;

use std::{net::SocketAddr, sync::Arc};

use axum::{
  Router,
  body::Bytes,
  extract::State,
  http::{HeaderMap, StatusCode},
  response::{IntoResponse, Response},
  routing::post,
};
use prost::Message;
use snap::raw::Decoder;
use tokio::net::TcpListener;
use tower_http::trace::TraceLayer;
use tracing_subscriber::{
  layer::SubscriberExt, util::SubscriberInitExt,
};

use crate::message_broker::{
  Producer, kafka::KafkaProducerBuilder,
};

pub mod prometheus {
  include!(concat!(
    env!("OUT_DIR"),
    "/io.prometheus.write.v2.rs"
  ));
}

async fn handler_fallback() -> impl IntoResponse {
  (StatusCode::NOT_FOUND, "Not found\n")
}

async fn check_content_encoding(
  header_map: &HeaderMap,
) -> Result<&str, Response> {
  match header_map.get("Content-Encoding") {
    Some(value) if value == "snappy" => Ok("snappy"),
    Some(value) if value != "" => Err(
      (
        StatusCode::UNSUPPORTED_MEDIA_TYPE,
        "unknown encoding, only snappy supported",
      )
        .into_response(),
    ),
    _ => {
      return Err(
        (
          StatusCode::UNSUPPORTED_MEDIA_TYPE,
          "missing Content-Encoding header",
        )
          .into_response(),
      );
    }
  }
}

async fn check_content_type(
  header_map: &HeaderMap,
) -> Result<&str, Response> {
  match header_map.get("Content-Type") {
    Some(value)
      if value
        == "application/x-protobuf;proto=io.prometheus.write.v2.Request" =>
    {
      Ok(
        "application/x-protobuf;proto=io.prometheus.write.v2.Request",
      )
    }
    Some(value) if value != "" => Err(
      (
        StatusCode::UNSUPPORTED_MEDIA_TYPE,
        format!(
          "Unknown remote write content type: {:?}",
          value
        ),
      )
        .into_response(),
    ),
    _ => {
      return Err(
        (
          StatusCode::UNSUPPORTED_MEDIA_TYPE,
          "missing Content-Type header",
        )
          .into_response(),
      );
    }
  }
}

async fn decompress_body(
  body: Bytes,
) -> Result<Vec<u8>, Response> {
  let mut decoder = Decoder::new();
  match decoder.decompress_vec(&body[..]) {
    Ok(buf) => Ok(buf),
    Err(e) => Err(
      (StatusCode::BAD_REQUEST, e.to_string()).into_response(),
    ),
  }
}

async fn handler_receive_prometheus_remote_write(
  header_map: HeaderMap,
  State(state): State<AppState>,
  body: Bytes,
) -> Response {
  // tracing::debug!(
  //   "method = {} \nheader = {:#?}\n body = {:?}",
  //   method,
  //   header_map,
  //   body
  // );

  let _content_encoding =
    match check_content_encoding(&header_map).await {
      Ok(value) => value,
      Err(res) => return res,
    };
  // tracing::debug!("content encoding = {}", content_encoding);

  let _content_type = match check_content_type(&header_map).await
  {
    Ok(value) => value,
    Err(res) => return res,
  };
  // tracing::debug!("content type = {}", content_type);

  let buf = match decompress_body(body.clone()).await {
    Ok(buf) => buf,
    Err(res) => return res,
  };
  // tracing::debug!("decompress body = {:?}", buf);
  let request = match prometheus::Request::decode(buf.as_slice())
  {
    Ok(inner) => inner,
    Err(e) => {
      return (StatusCode::BAD_REQUEST, e.to_string())
        .into_response();
    }
  };
  // tracing::debug!("req = {:#?}", request);

  let mut samples: i64 = 0;
  let mut histograms: i64 = 0;
  let mut examplars: i64 = 0;

  println!("{:?}", request);

  for timeseries in request.timeseries {
    println!(
      "{:#?}\n{:#?}\n{:#?}",
      timeseries.samples,
      timeseries.histograms,
      timeseries.exemplars
    );
    samples += timeseries.samples.len() as i64;
    histograms += timeseries.histograms.len() as i64;
    examplars += timeseries.exemplars.len() as i64;
  }
  let mut header_map = HeaderMap::new();
  header_map.append(
    "X-Prometheus-Remote-Write-Samples-Written",
    samples.to_string().parse().unwrap(),
  );
  header_map.append(
    "X-Prometheus-Remote-Write-Histograms-Written",
    histograms.to_string().parse().unwrap(),
  );
  header_map.append(
    "X-Prometheus-Remote-Write-Exemplars-Written",
    examplars.to_string().parse().unwrap(),
  );
  println!("{:?}", header_map);
  match state
    .producer
    .produce("raw_metrics", b"", &body, &[])
    .await
  {
    Ok(_) => {
      (StatusCode::NO_CONTENT, header_map).into_response()
    }
    Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, e.to_string())
      .into_response(),
  }
}

#[derive(Clone)]
struct AppState {
  producer: Arc<dyn Producer>,
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

  let producer = KafkaProducerBuilder::new()
    .set_bootstrap(&[SocketAddr::from(([127, 0, 0, 1], 19092))])
    .build()
    .unwrap();

  let state = AppState {
    producer: Arc::new(producer),
  };

  let app: Router = Router::new()
    .route(
      "/receive",
      post(handler_receive_prometheus_remote_write)
        .with_state(state),
    )
    .fallback(handler_fallback)
    .layer(TraceLayer::new_for_http());
  let addr = SocketAddr::from(([0, 0, 0, 0], 1234));
  let listener = TcpListener::bind(addr).await.unwrap();
  tracing::debug!(
    "listening on {}",
    listener.local_addr().unwrap()
  );
  axum::serve(listener, app).await.unwrap();
}
