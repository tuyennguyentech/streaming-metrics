package org.example.conf;

import java.util.List;

import org.github.gestalt.config.annotations.ConfigPrefix;

import lombok.ToString;

@ToString
@ConfigPrefix(prefix = "kafkaSource")
public class KafkaSource {
  public List<Server> brokers;
  public String[] topics;
  public String groupId;
}
