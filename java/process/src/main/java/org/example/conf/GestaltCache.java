package org.example.conf;

import java.util.Map;

import org.apache.flink.api.java.utils.ParameterTool;
import org.github.gestalt.config.Gestalt;
import org.github.gestalt.config.builder.GestaltBuilder;
import org.github.gestalt.config.source.ClassPathConfigSourceBuilder;

public class GestaltCache {
  static Gestalt gestalt;
  static void create(Map<String, String> globalJobParameters) throws Exception {
    ParameterTool parameters = ParameterTool.fromMap(globalJobParameters);
    String environmentString = parameters.get("env", "local");
    Environment environment = Environment.fromString(environmentString);
    var builder = new GestaltBuilder()
      .addSource(ClassPathConfigSourceBuilder.builder().setResource("/application.properties").build());
    if (environment.equals(Environment.DEV)) {
      builder.addSource(ClassPathConfigSourceBuilder.builder().setResource("/dev.application.properties").build());
    }
    gestalt = builder.build();
    gestalt.loadConfigs();
  }
  public static Gestalt getGestalt(Map<String, String> globalJobParameters) throws Exception {
    if (gestalt == null) {
      create(globalJobParameters);
    }
    return gestalt;
  }
}
