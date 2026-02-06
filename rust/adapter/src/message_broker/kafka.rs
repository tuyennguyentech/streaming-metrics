use std::{net::SocketAddr, time::Duration};

use crate::message_broker::{Producer, ProducerResult};

use async_trait::async_trait;
use rdkafka::{
  ClientConfig,
  error::KafkaError,
  message::{Header, OwnedHeaders},
  producer::{
    FutureProducer, FutureRecord, future_producer::Delivery,
  },
};

pub struct KafkaProducerBuilder {
  client_config: ClientConfig,
}

pub struct KafkaProducer {
  producer: FutureProducer,
}

pub enum BrokerAddr {
  SocketAddr(SocketAddr),
  DomainName(String, u16),
}

impl KafkaProducerBuilder {
  pub fn new() -> Self {
    KafkaProducerBuilder {
      client_config: ClientConfig::new(),
    }
  }
  pub fn set_bootstrap(
    mut self,
    brokers: &[BrokerAddr],
  ) -> Self {
    self.client_config.set(
      "bootstrap.servers",
      brokers
        .iter()
        .map(|addr| match addr {
          BrokerAddr::SocketAddr(addr) => {
            format!("{}:{}", addr.ip(), addr.port())
          }
          BrokerAddr::DomainName(domain, port) => {
            format!("{}:{}", domain, port)
          }
        })
        .collect::<Vec<_>>()
        .join(","),
    );
    self
  }
  pub fn build(self) -> Result<KafkaProducer, KafkaError> {
    match self.client_config.create() {
      Ok(producer) => Ok(KafkaProducer { producer }),
      Err(e) => Err(e),
    }
  }
}

#[async_trait]
impl Producer for KafkaProducer {
  async fn produce(
    &self,
    topic: &str,
    key: &[u8],
    value: &[u8],
    headers: &[(&str, &[u8])],
  ) -> ProducerResult<Delivery> {
    self
      .producer
      .send(
        FutureRecord::to(topic).key(key).payload(value).headers(
          headers.into_iter().fold(
            OwnedHeaders::new(),
            |acc, x| {
              acc.insert(Header {
                key: x.0,
                value: Some(x.1),
              })
            },
          ),
        ),
        Duration::from_secs(5),
      )
      .await
      .map_err(|err| err.0)
  }
}

#[allow(unused)]
mod demo {
  use futures::{TryStreamExt, future};
  use std::time::Duration;

  use rdkafka::{
    ClientConfig,
    consumer::{Consumer, StreamConsumer},
    producer::{FutureProducer, FutureRecord},
  };

  pub(super) async fn demo() {
    let output_topic = "raw_metrics";
    let brokers = "localhost:19092";
    let producer: FutureProducer = ClientConfig::new()
      .set("bootstrap.servers", brokers)
      .set("message.timeout.ms", "5000")
      .create()
      .expect("Producer creation should be successful");
    let produce_fut = producer.send(
      FutureRecord::to(output_topic)
        .key("key3")
        .payload("payload3"),
      Duration::from_secs(5),
    );
    match produce_fut.await {
      Ok(delivery) => println!("Sent: {:?}", delivery),
      Err((e, _)) => println!("Error: {:?}", e),
    }
    let consumer: StreamConsumer = ClientConfig::new()
      .set("group.id", "demo")
      .set("bootstrap.servers", brokers)
      .set("enable.partition.eof", "false")
      .set("session.timeout.ms", "6000")
      .set("enable.auto.commit", "false")
      .set("auto.offset.reset", "earliest")
      .create()
      .expect("Consumer creation should be successful");
    consumer
      .subscribe(&[output_topic])
      .expect("Consumer subscribes successfully");
    consumer
      .stream()
      .try_for_each(|message| {
        let owned_message = message.detach();
        println!("{:?}", owned_message);
        consumer
          .commit_message(
            &message,
            rdkafka::consumer::CommitMode::Async,
          )
          .unwrap();
        future::ready(Ok(()))
      })
      .await
      .expect("Consumer streams successfully");
  }
}
