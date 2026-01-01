package org.example.sinks;

import java.util.Collections;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.apache.flink.streaming.api.functions.async.RichAsyncFunction;
import org.example.conf.GestaltCache;
import org.github.gestalt.config.annotations.ConfigPrefix;
import org.xerial.snappy.Snappy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.prometheus.write.v2.Types.Request;
import lombok.ToString;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

public class ViewDuplicatedMetrics extends RichAsyncFunction<Request, Void> {
  @ToString
  @ConfigPrefix(prefix = "viewDuplicatedMetrics")
  public static class Conf {
    String endpoint;
  }

  private transient HttpClient client;
  private Conf conf;

  @Override
  public void open(Configuration parameters) throws Exception {
    super.open(parameters);
    conf = GestaltCache.getGestalt(getRuntimeContext().getGlobalJobParameters()).getConfig("sinks", Conf.class);
    client = HttpClient.create();
  }

  @Override
  public void close() throws Exception {
    super.close();
  }

  @Override
  public void timeout(Request input, ResultFuture<Void> resultFuture) throws Exception {
    super.timeout(input, resultFuture);
  }

  @Override
  public void asyncInvoke(Request input, ResultFuture<Void> resultFuture) throws Exception {
    byte[] body = Snappy.compress(input.toByteArray());
    ByteBuf buf = Unpooled.wrappedBuffer(body);
    client
        .headers(h -> {
          h.add("Content-Type", "application/x-protobuf;proto=io.prometheus.write.v2.Request");
          h.add("Content-Encoding", "snappy");
          h.add("X-Prometheus-Remote-Write-Version", "2.0.0");
          h.add("User-Agent", "flink.streaming-metrics");
        })
        .post()
        .uri(conf.endpoint)
        .send(Mono.just(buf))
        .responseSingle((resp, b) -> {
          if (resp.status().code() / 100 != 2) {
            return b.asString()
                .flatMap(msg -> Mono.error(
                    new RuntimeException(
                        "remote write failed: "
                            + resp.status() + " body=" + msg)));
          }
          return Mono.empty();
        })
        .subscribe(
            ok -> resultFuture.complete(
                Collections.emptyList()),
            err -> {
              err.printStackTrace();
              resultFuture.complete(
                  Collections.emptyList());
            });
  }
}
