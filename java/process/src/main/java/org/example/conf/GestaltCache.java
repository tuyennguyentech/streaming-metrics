package org.example.conf;

import org.github.gestalt.config.Gestalt;
import org.github.gestalt.config.builder.GestaltBuilder;
import org.github.gestalt.config.source.ClassPathConfigSourceBuilder;

import lombok.Setter;

public class GestaltCache {
  static Gestalt gestalt;
  @Setter
  static Environment environment;
  static void create() throws Exception {
    var builder = new GestaltBuilder()
      .addSource(ClassPathConfigSourceBuilder.builder().setResource("/application.properties").build());
    if (environment.equals(Environment.DEV)) {
      builder.addSource(ClassPathConfigSourceBuilder.builder().setResource("/dev.application.properties").build());
    }
    gestalt = builder.build();
    gestalt.loadConfigs();
  }
  public static Gestalt getGestalt() throws Exception {
    if (gestalt == null) {
      create();
    }
    return gestalt;
  }
}
