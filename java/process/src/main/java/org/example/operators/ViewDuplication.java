package org.example.operators;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

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

public class ViewDuplication extends RichAsyncFunction<Request, Request> {
  @ToString
  @ConfigPrefix(prefix = "viewDuplication")
  public static class Conf {
    public Mongo mongo;
    public String collection;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Duplication {
    String view;
    Set<String> labels;
  }

  private transient MongoClient client;
  private transient MongoDatabase database;
  private transient MongoCollection<Duplication> collection;

  @Override
  public void open(Configuration parameters) throws Exception {
    super.open(parameters);
    Conf conf = GestaltCache.getGestalt(getRuntimeContext().getGlobalJobParameters()).getConfig("operators", Conf.class);

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
      collection = database.getCollection(conf.collection, Duplication.class);
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
  }

  @Override
  public void asyncInvoke(Request input, ResultFuture<Request> resultFuture) throws Exception {
    Flux.from(collection.find())
        .collectList()
        .subscribe(
            dups -> {
              List<String> symbols = new ArrayList<>(input.getSymbolsList());
              List<TimeSeries> series = input.getTimeseriesList();
              List<TimeSeries> duplicated = new ArrayList<>(series.size() * dups.size());
              for (TimeSeries ts : series) {
                Optional<String> name = Utils.getLabelValueByLabelName(symbols, ts.getLabelsRefsList(), "__name__");
                if (name.isPresent() && !name.get().equals("order_create_failed_total")) {
                  duplicated.add(ts);
                  continue;
                }

                for (Duplication dup : dups) {
                  Map<String, String> newLabels = new TreeMap<>();
                  newLabels.put("view", dup.view);
                  List<Integer> refs = ts.getLabelsRefsList();
                  for (int i = 0; i < refs.size(); i += 2) {
                    String labelName = symbols.get(refs.get(i));
                    if ("__name__".equals(labelName) || dup.labels.contains(labelName)) {
                      newLabels.put(labelName, symbols.get(refs.get(i + 1)));
                    }
                  }

                  List<Integer> newRefs = new ArrayList<>(newLabels.size() << 1);
                  for (Map.Entry<String, String> e : newLabels.entrySet()) {
                    int n = Utils.getOrAddSymbol(symbols, e.getKey());
                    int v = Utils.getOrAddSymbol(symbols, e.getValue());
                    newRefs.add(n);
                    newRefs.add(v);
                  }

                  duplicated.add(
                      ts.toBuilder()
                          .clearLabelsRefs()
                          .addAllLabelsRefs(newRefs)
                          .build());
                }
              }
              resultFuture.complete(
                  Collections.singleton(
                      input.toBuilder()
                          .clearSymbols()
                          .addAllSymbols(symbols)
                          .clearTimeseries()
                          .addAllTimeseries(duplicated)
                          .build()));
            },
            err -> {
              err.printStackTrace();
              resultFuture.complete(Collections.emptyList());
            });
  }
}
