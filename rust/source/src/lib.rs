pub mod server;

use prometheus_client::{
  encoding::{EncodeLabelSet, EncodeLabelValue, text::encode},
  metrics::{counter::Counter, family::Family},
  registry::Registry,
};

#[allow(dead_code)]
#[derive(
  Clone, Debug, Hash, PartialEq, Eq, EncodeLabelValue,
)]
enum Method {
  GET,
  PUT,
}

#[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
struct Labels {
  method: Method,
  path: String,
}

pub fn demo() {
  let mut registry = <Registry>::default();
  let http_requests = Family::<Labels, Counter>::default();
  registry.register(
    "http_requests",
    "Number of HTTP requests received",
    http_requests.clone(),
  );
  http_requests
    .get_or_create(&Labels {
      method: Method::GET,
      path: "/metrics".to_string(),
    })
    .inc();
  let mut buf = String::new();
  encode(&mut buf, &registry).unwrap();
  println!("{}", buf)
}
