package org.example.operators;

import java.util.Collections;
import java.util.List;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.apache.flink.streaming.api.functions.async.RichAsyncFunction;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.example.conf.GestaltCache;
import org.example.conf.Mongo;
import org.github.gestalt.config.Gestalt;
import org.github.gestalt.config.annotations.ConfigPrefix;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.protobuf.util.JsonFormat;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.prometheus.write.v2.Types.Request;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import reactor.core.publisher.Flux;

public class MetadataEnrichment extends RichAsyncFunction<Request, Request> {
  @ToString
  @ConfigPrefix(prefix = "metadataEnrichment")
  public static class Conf {
    public Mongo mongo;
    public String collection;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Metadata {
    String pod;
    String service;
    String team;
    String tier;
  }

  private transient MongoClient client;
  private transient MongoDatabase database;
  private transient MongoCollection<Metadata> collection;

  @Override
  public void open(Configuration parameters) throws Exception {
    super.open(parameters);
    Conf conf = GestaltCache.getGestalt().getConfig("operators", Conf.class);
    // System.out.println(conf);

    String uri = String.format(
        "mongodb://%s:%s@%s:%d",
        conf.mongo.username,
        conf.mongo.password,
        conf.mongo.addr.host,
        conf.mongo.addr.port);

    try {
      CodecRegistry pojoCodecRegistry = CodecRegistries.fromRegistries(
          MongoClientSettings.getDefaultCodecRegistry(),
          CodecRegistries.fromProviders(PojoCodecProvider.builder()
              .automatic(true)
              .build()));
      MongoClientSettings settings = MongoClientSettings.builder()
          .applyConnectionString(new ConnectionString(uri))
          .codecRegistry(pojoCodecRegistry)
          .build();
      client = MongoClients.create(settings);
      database = client.getDatabase(conf.mongo.database);
      collection = database.getCollection(conf.collection, Metadata.class);
      // CodecRe
      System.out.println("Return: " + Flux.from(collection.find())
          .doOnNext(name -> System.out.println("Document: " + name)) // In từng tên
          .doOnComplete(() -> System.out.println("Đã liệt kê xong!")) // In khi xong
          .doOnError(e -> e.printStackTrace()) // In nếu lỗi
          .blockLast());
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    }
  }

  @Override
  public void close() throws Exception {
    super.close();
    client.close();
  }

  @Override
  public void timeout(Request input, ResultFuture<Request> resultFuture) throws Exception {
    // super.timeout(input, resultFuture);
    // resultFuture.complete(Collections.emptyList());
  }

  @Override
  public void asyncInvoke(Request input, ResultFuture<Request> resultFuture) throws Exception {
    System.out.println(MetadataEnrichment.class.getName());
    System.out.println(JsonFormat.printer().print(input));
  }
}
