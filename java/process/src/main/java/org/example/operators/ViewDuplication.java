package org.example.operators;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.apache.flink.streaming.api.functions.async.RichAsyncFunction;

import io.prometheus.write.v2.Types.Request;

public class ViewDuplication extends RichAsyncFunction<Request, Request> {
  @Override
  public void open(Configuration parameters) throws Exception {
    // TODO Auto-generated method stub
    super.open(parameters);
  }

  @Override
  public void close() throws Exception {
    // TODO Auto-generated method stub
    super.close();
  }

  @Override
  public void asyncInvoke(Request input, ResultFuture<Request> resultFuture) throws Exception {
    // TODO Auto-generated method stub

  }
}
