package org.example.conf;

import org.github.gestalt.config.annotations.ConfigPrefix;

import lombok.ToString;

@ToString
@ConfigPrefix(prefix = "mongo")
public class Mongo {
  public String username;
  public String password;
  public Server addr;
  public String database;
}
