package gobblin.writer.http;

import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;

import com.typesafe.config.Config;

import gobblin.config.ConfigBuilder;
import gobblin.configuration.State;
import gobblin.writer.FluentDataWriterBuilder;

public abstract class AbstractHttpWriterBuilder<S, D, B extends AbstractHttpWriterBuilder<S, D, B>>
       extends FluentDataWriterBuilder<S, D, B> {
  public static final String CONF_PREFIX = "gobblin.writer.http.";

  private HttpClientBuilder _httpClientBuilder =
      HttpClientBuilder.create().disableCookieManagement().useSystemProperties();
  // TODO Add an option for connection pooling
  private HttpClientConnectionManager _httpConnManager = new BasicHttpClientConnectionManager();

  public B fromState(State state) {
    Config config = ConfigBuilder.create().loadProps(state.getProperties(), CONF_PREFIX).build();
    fromConfig(config);
    return typedSelf();
  }

  public B fromConfig(Config config) {
    return typedSelf();
  }

  public B withHttpClientBuilder(HttpClientBuilder builder) {
    _httpClientBuilder = builder;
    return typedSelf();
  }

  public HttpClientBuilder getHttpClientBuilder() {
    return _httpClientBuilder;
  }

  public B withHttpClientConnectionManager(HttpClientConnectionManager connManager) {
    _httpConnManager = connManager;
    return typedSelf();
  }

  HttpClientConnectionManager getHttpConnManager() {
    return _httpConnManager;
  }


}
