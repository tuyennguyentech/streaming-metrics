package org.example.operators;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.apache.flink.streaming.api.functions.async.RichAsyncFunction;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.example.conf.GestaltCache;
import org.example.conf.Mongo;
import org.github.gestalt.config.annotations.ConfigPrefix;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.Filters;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.prometheus.write.v2.Types.Request;
import io.prometheus.write.v2.Types.TimeSeries;
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
    super.timeout(input, resultFuture);
    // resultFuture.complete(Collections.emptyList());
  }

  static List<Integer> enrichLabels(
      TimeSeries ts,
      List<String> symbols,
      Metadata metadata) {
    Map<String, String> labels = new HashMap<>();

    List<Integer> refs = ts.getLabelsRefsList();
    for (int i = 0; i < refs.size(); i += 2) {
      labels.put(symbols.get(refs.get(i)), symbols.get(refs.get(i + 1)));
    }

    labels.put("service", metadata.service);
    labels.put("team", metadata.team);
    labels.put("tier", metadata.tier);

    List<String> names = new ArrayList<>(labels.keySet());
    Collections.sort(names);

    List<Integer> newRefs = new ArrayList<>(names.size() << 1);

    for (String name : names) {
      int nRef = Utils.getOrAddSymbol(symbols, name);
      int vRef = Utils.getOrAddSymbol(symbols, labels.get(name));

      newRefs.add(nRef);
      newRefs.add(vRef);
    }

    return newRefs;
  }

  @Override
  public void asyncInvoke(Request input, ResultFuture<Request> resultFuture) throws Exception {
    List<String> symbols = new ArrayList<>(input.getSymbolsList());
    List<TimeSeries> series = input.getTimeseriesList();

    Set<String> pods = new HashSet<>();
    for (TimeSeries ts : series) {
      Utils.getLabelValueByLabelName(
          symbols,
          ts.getLabelsRefsList(),
          "pod")
          .ifPresent(podName -> pods.add(podName));
    }
    if (pods.isEmpty()) {
      resultFuture.complete(Collections.singleton(input));
      return;
    }
    Flux.from(collection.find(Filters.in("pod", pods)))
        .collectMap(m -> m.pod)
        .subscribe(
            metadataByPodName -> {
              List<TimeSeries> enriched = new ArrayList<>(series.size());
              for (TimeSeries ts : series) {
                Utils.getLabelValueByLabelName(
                    symbols,
                    ts.getLabelsRefsList(), "pod")
                    .ifPresentOrElse(podName -> {
                      Metadata metadata = metadataByPodName.get(podName);
                      if (metadata == null) {
                        enriched.add(ts);
                        return;
                      }
                      List<Integer> newRefs = enrichLabels(ts, symbols, metadata);
                      enriched.add(
                          ts.toBuilder()
                              .clearLabelsRefs()
                              .addAllLabelsRefs(newRefs)
                              .build());

                    }, () -> {
                      enriched.add(ts);
                    });
              }
              resultFuture.complete(
                  Collections.singleton(
                      input.toBuilder()
                          .clearSymbols()
                          .addAllSymbols(symbols)
                          .clearTimeseries()
                          .addAllTimeseries(enriched)
                          .build()));
            },
            err -> {
              err.printStackTrace();
              resultFuture.complete(Collections.emptyList());
            });
  }
}
