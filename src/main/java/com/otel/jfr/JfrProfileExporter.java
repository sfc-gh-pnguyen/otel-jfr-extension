package com.otel.jfr;

import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal OTLP/HTTP profiles exporter: serializes an
 * {@link ExportProfilesServiceRequest} to protobuf and POSTs it to the
 * configured {@code /v1development/profiles} endpoint.
 *
 * <p>Hand-rolled on purpose (JDK {@link HttpClient} only): the OpenTelemetry
 * Java agent does not autoconfigure a profiles pipeline, so there is no SDK
 * exporter to reuse, and bundling the alpha SDK exporter into an agent
 * extension risks classloader/relocation issues. This mirrors the "build OTLP
 * by hand" approach already used in the repo's Python receiver.
 */
final class JfrProfileExporter {

  private static final Logger logger = Logger.getLogger(JfrProfileExporter.class.getName());

  private final HttpClient httpClient;
  private final URI endpoint;
  private final Map<String, String> headers;

  JfrProfileExporter(String endpoint, Map<String, String> headers) {
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.endpoint = URI.create(endpoint);
    this.headers = headers;
  }

  void export(ExportProfilesServiceRequest request) {
    byte[] body = request.toByteArray();
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(endpoint)
              .timeout(Duration.ofSeconds(30))
              .header("Content-Type", "application/x-protobuf")
              .POST(HttpRequest.BodyPublishers.ofByteArray(body));
      for (Map.Entry<String, String> h : headers.entrySet()) {
        // Content-Type is set above; skip a user-supplied duplicate.
        if (!"content-type".equalsIgnoreCase(h.getKey())) {
          builder.header(h.getKey(), h.getValue());
        }
      }
      HttpResponse<Void> resp =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
      int sc = resp.statusCode();
      if (sc >= 200 && sc < 300) {
        logger.fine(
            "otel-jfr-extension: profiles export OK ("
                + body.length
                + " bytes, HTTP "
                + sc
                + ")");
      } else {
        logger.warning(
            "otel-jfr-extension: profiles export got HTTP "
                + sc
                + " ("
                + body.length
                + " bytes) to "
                + endpoint);
      }
    } catch (Exception e) {
      logger.log(
          Level.WARNING,
          "otel-jfr-extension: profiles export failed to " + endpoint + ": " + e.getMessage());
    }
  }

  /** Build a proto Resource from a flat attribute map (service.name, etc.). */
  static io.opentelemetry.proto.resource.v1.Resource protoResource(Map<String, String> attributes) {
    io.opentelemetry.proto.resource.v1.Resource.Builder builder =
        io.opentelemetry.proto.resource.v1.Resource.newBuilder();
    if (attributes != null) {
      for (Map.Entry<String, String> e : attributes.entrySet()) {
        builder.addAttributes(
            KeyValue.newBuilder()
                .setKey(e.getKey())
                .setValue(AnyValue.newBuilder().setStringValue(e.getValue()).build())
                .build());
      }
    }
    return builder.build();
  }
}
