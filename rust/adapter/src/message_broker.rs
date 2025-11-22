pub mod kafka;

use async_trait::async_trait;

use rdkafka::error::KafkaResult;
pub use rdkafka::producer::future_producer::Delivery;

pub type ProducerResult<T> = KafkaResult<T>;

#[async_trait]
pub trait Producer: Send + Sync {
  async fn produce(
    &self,
    topic: &str,
    key: &[u8],
    value: &[u8],
    headers: &[(&str, &[u8])],
  ) -> ProducerResult<Delivery>;
}
