package org.example.sources;

import java.io.IOException;
import java.util.stream.Collectors;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.example.conf.GestaltCache;
import org.github.gestalt.config.Gestalt;
import org.github.gestalt.config.annotations.ConfigPrefix;
import org.xerial.snappy.Snappy;

import io.prometheus.write.v2.Types.Request;
import lombok.ToString;

public class RawMetrics {
  @ToString
  @ConfigPrefix(prefix = "rawMetrics")
  public static class Conf {
    public org.example.conf.KafkaSource kafkaSource;
  }

  public static KafkaSource<Request> createSource() throws Exception {
    Gestalt gestalt = GestaltCache.getGestalt();
    Conf conf = gestalt.getConfig("sources", Conf.class);
    String bootstrapServers = conf.kafkaSource.brokers
        .parallelStream()
        .map(broker -> broker.host + ':' + broker.port)
        .collect(Collectors.joining(","));
    var kafkaSource = KafkaSource.<Request>builder()
        .setBootstrapServers(bootstrapServers)
        .setTopics(conf.kafkaSource.topics)
        .setGroupId(conf.kafkaSource.groupId)
        .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST))
        .setProperty("partition.discovery.interval.ms", "10000")
        .setProperty("commit.offsets.on.checkpoint", "true")
        .setDeserializer(new KafkaRecordDeserializationSchema<Request>() {
          @Override
          public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<Request> out) throws IOException {
            try {
              byte[] uncompressed = Snappy.uncompress(record.value());
              out.collect(Request.parseFrom(uncompressed));
            } catch (Exception e) {
              e.printStackTrace();
              throw e;
            }
          }

          @Override
          public TypeInformation<Request> getProducedType() {
            return TypeInformation.of(Request.class);
          }
        })
        .build();
    return kafkaSource;
  }
}
