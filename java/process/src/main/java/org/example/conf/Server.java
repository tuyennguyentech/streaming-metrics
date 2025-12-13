package org.example.conf;

import org.github.gestalt.config.annotations.ConfigPrefix;

import lombok.ToString;

@ToString
@ConfigPrefix(prefix = "server")
public class Server {
  public String host;
  public int port;
}
