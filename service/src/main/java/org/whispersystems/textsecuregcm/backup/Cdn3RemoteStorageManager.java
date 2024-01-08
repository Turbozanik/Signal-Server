package org.whispersystems.textsecuregcm.backup;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import javax.ws.rs.core.Response;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.configuration.Cdn3StorageManagerConfiguration;
import org.whispersystems.textsecuregcm.configuration.CircuitBreakerConfiguration;
import org.whispersystems.textsecuregcm.configuration.RetryConfiguration;
import org.whispersystems.textsecuregcm.http.FaultTolerantHttpClient;
import org.whispersystems.textsecuregcm.metrics.MetricsUtil;
import org.whispersystems.textsecuregcm.util.ExceptionUtils;
import org.whispersystems.textsecuregcm.util.HttpUtils;
import org.whispersystems.textsecuregcm.util.SystemMapper;

public class Cdn3RemoteStorageManager implements RemoteStorageManager {

  private static final Logger logger = LoggerFactory.getLogger(Cdn3RemoteStorageManager.class);

  private final FaultTolerantHttpClient cdnHttpClient;
  private final FaultTolerantHttpClient storageManagerHttpClient;
  private final String storageManagerBaseUrl;
  private final String clientId;
  private final String clientSecret;
  static final String CLIENT_ID_HEADER = "CF-Access-Client-Id";
  static final String CLIENT_SECRET_HEADER = "CF-Access-Client-Secret";

  private static final String STORAGE_MANAGER_STATUS_COUNTER_NAME = MetricsUtil.name(Cdn3RemoteStorageManager.class,
      "storageManagerStatus");

  private static final String STORAGE_MANAGER_TIMER_NAME = MetricsUtil.name(Cdn3RemoteStorageManager.class,
      "storageManager");
  private static final String OPERATION_TAG_NAME = "op";
  private static final String STATUS_TAG_NAME = "status";

  public Cdn3RemoteStorageManager(
      final ScheduledExecutorService retryExecutor,
      final CircuitBreakerConfiguration circuitBreakerConfiguration,
      final RetryConfiguration retryConfiguration,
      final List<String> cdnCaCertificates,
      final Cdn3StorageManagerConfiguration configuration) throws CertificateException {

    // strip trailing "/" for easier URI construction
    this.storageManagerBaseUrl = StringUtils.removeEnd(configuration.baseUri(), "/");
    this.clientId = configuration.clientId();
    this.clientSecret = configuration.clientSecret().value();

    // Client used to read/write to cdn
    this.cdnHttpClient = FaultTolerantHttpClient.newBuilder()
        .withName("cdn-client")
        .withCircuitBreaker(circuitBreakerConfiguration)
        .withExecutor(Executors.newCachedThreadPool())
        .withRetryExecutor(retryExecutor)
        .withRetry(retryConfiguration)
        .withConnectTimeout(Duration.ofSeconds(10))
        .withVersion(HttpClient.Version.HTTP_2)
        .withTrustedServerCertificates(cdnCaCertificates.toArray(new String[0]))
        .build();

    // Client used for calls to storage-manager
    // storage-manager has an external CA so uses a different client
    this.storageManagerHttpClient = FaultTolerantHttpClient.newBuilder()
        .withName("cdn3-storage-manager")
        .withCircuitBreaker(circuitBreakerConfiguration)
        .withExecutor(Executors.newCachedThreadPool())
        .withRetryExecutor(retryExecutor)
        .withRetry(retryConfiguration)
        .withConnectTimeout(Duration.ofSeconds(10))
        .withVersion(HttpClient.Version.HTTP_2)
        .build();
  }

  @Override
  public int cdnNumber() {
    return 3;
  }

  @Override
  public CompletionStage<Void> copy(
      final URI sourceUri,
      final int expectedSourceLength,
      final MediaEncryptionParameters encryptionParameters,
      final MessageBackupUploadDescriptor uploadDescriptor) {

    if (uploadDescriptor.cdn() != cdnNumber()) {
      throw new IllegalArgumentException("Cdn3RemoteStorageManager can only copy to cdn3");
    }

    final Timer.Sample sample = Timer.start();
    final BackupMediaEncrypter encrypter = new BackupMediaEncrypter(encryptionParameters);
    final HttpRequest request = HttpRequest.newBuilder().GET().uri(sourceUri).build();
    return cdnHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofPublisher()).thenCompose(response -> {
          if (response.statusCode() == Response.Status.NOT_FOUND.getStatusCode()) {
            throw new CompletionException(new SourceObjectNotFoundException());
          } else if (response.statusCode() != Response.Status.OK.getStatusCode()) {
            throw new CompletionException(new IOException("error reading from source: " + response.statusCode()));
          }

          final int actualSourceLength = Math.toIntExact(response.headers().firstValueAsLong("Content-Length")
              .orElseThrow(() -> new CompletionException(new IOException("upstream missing Content-Length"))));

          if (actualSourceLength != expectedSourceLength) {
            throw new CompletionException(
                new InvalidLengthException("Provided sourceLength " + expectedSourceLength + " was " + actualSourceLength));
          }

          final int expectedEncryptedLength = encrypter.outputSize(actualSourceLength);
          final HttpRequest.BodyPublisher encryptedBody = HttpRequest.BodyPublishers.fromPublisher(
              encrypter.encryptBody(response.body()), expectedEncryptedLength);

          final String[] headers = Stream.concat(
                  uploadDescriptor.headers().entrySet()
                      .stream()
                      .flatMap(e -> Stream.of(e.getKey(), e.getValue())),
                  Stream.of("Upload-Length", Integer.toString(expectedEncryptedLength), "Tus-Resumable", "1.0.0"))
              .toArray(String[]::new);

          final HttpRequest put = HttpRequest.newBuilder()
              .uri(URI.create(uploadDescriptor.signedUploadLocation()))
              .headers(headers)
              .POST(encryptedBody)
              .build();

          return cdnHttpClient.sendAsync(put, HttpResponse.BodyHandlers.discarding());
        })
        .thenAccept(response -> {
          if (response.statusCode() != Response.Status.CREATED.getStatusCode() &&
              response.statusCode() != Response.Status.OK.getStatusCode()) {
            throw new CompletionException(new IOException("Failed to copy object: " + response.statusCode()));
          }
        })
        .whenComplete((ignored, ignoredException) ->
            sample.stop(Metrics.timer(STORAGE_MANAGER_TIMER_NAME, OPERATION_TAG_NAME, "copy")));
  }

  @Override
  public CompletionStage<ListResult> list(
      final String prefix,
      final Optional<String> cursor,
      final long limit) {
    final Timer.Sample sample = Timer.start();

    final Map<String, String> queryParams = new HashMap<>();
    queryParams.put("prefix", prefix);
    queryParams.put("limit", Long.toString(limit));
    cursor.ifPresent(s -> queryParams.put("cursor", cursor.get()));

    final HttpRequest request = HttpRequest.newBuilder().GET()
        .uri(URI.create("%s/%s/%s".formatted(
            storageManagerBaseUrl,
            Cdn3BackupCredentialGenerator.CDN_PATH,
            HttpUtils.queryParamString(queryParams.entrySet()))))
        .header(CLIENT_ID_HEADER, clientId)
        .header(CLIENT_SECRET_HEADER, clientSecret)
        .build();

    return this.storageManagerHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        .thenApply(response -> {
          Metrics.counter(STORAGE_MANAGER_STATUS_COUNTER_NAME,
                  OPERATION_TAG_NAME, "list",
                  STATUS_TAG_NAME, Integer.toString(response.statusCode()))
              .increment();
          try {
            return parseListResponse(response, prefix);
          } catch (IOException e) {
            throw ExceptionUtils.wrap(e);
          }
        })
        .whenComplete((ignored, ignoredException) ->
            sample.stop(Metrics.timer(STORAGE_MANAGER_TIMER_NAME, OPERATION_TAG_NAME, "list")));
  }

  /**
   * Serialized list response from storage manager
   */
  record Cdn3ListResponse(@NotNull List<Entry> objects, @Nullable String cursor) {

    record Entry(@NotNull String key, @NotNull long size) {}
  }

  private static ListResult parseListResponse(final HttpResponse<InputStream> httpListResponse, final String prefix)
      throws IOException {
    if (!HttpUtils.isSuccessfulResponse(httpListResponse.statusCode())) {
      throw new IOException("Failed to list objects: " + httpListResponse.statusCode());
    }
    final Cdn3ListResponse result = SystemMapper.jsonMapper()
        .readValue(httpListResponse.body(), Cdn3ListResponse.class);

    final List<ListResult.Entry> objects = new ArrayList<>(result.objects.size());
    for (Cdn3ListResponse.Entry entry : result.objects) {
      if (!entry.key().startsWith(prefix)) {
        logger.error("unexpected listing result from cdn3 - entry {} does not contain requested prefix {}",
            entry.key(), prefix);
        throw new IOException("prefix listing returned unexpected result");
      }
      objects.add(new ListResult.Entry(entry.key().substring(prefix.length()), entry.size()));
    }
    return new ListResult(objects, Optional.ofNullable(result.cursor));
  }


  /**
   * Serialized usage response from storage manager
   */
  record UsageResponse(@NotNull long numObjects, @NotNull long bytesUsed) {}

  @Override
  public CompletionStage<UsageInfo> calculateBytesUsed(final String prefix) {
    final Timer.Sample sample = Timer.start();
    final HttpRequest request = HttpRequest.newBuilder().GET()
        .uri(URI.create("%s/usage%s".formatted(
            storageManagerBaseUrl,
            HttpUtils.queryParamString(Map.of("prefix", prefix).entrySet()))))
        .header(CLIENT_ID_HEADER, clientId)
        .header(CLIENT_SECRET_HEADER, clientSecret)
        .build();
    return this.storageManagerHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        .thenApply(response -> {
          Metrics.counter(STORAGE_MANAGER_STATUS_COUNTER_NAME,
                  OPERATION_TAG_NAME, "usage",
                  STATUS_TAG_NAME, Integer.toString(response.statusCode()))
              .increment();
          try {
            return parseUsageResponse(response);
          } catch (IOException e) {
            throw ExceptionUtils.wrap(e);
          }
        })
        .whenComplete((ignored, ignoredException) ->
            sample.stop(Metrics.timer(STORAGE_MANAGER_TIMER_NAME, OPERATION_TAG_NAME, "usage")));
  }

  private static UsageInfo parseUsageResponse(final HttpResponse<InputStream> httpUsageResponse) throws IOException {
    if (!HttpUtils.isSuccessfulResponse(httpUsageResponse.statusCode())) {
      throw new IOException("Failed to retrieve usage: " + httpUsageResponse.statusCode());
    }
    final UsageResponse response = SystemMapper.jsonMapper().readValue(httpUsageResponse.body(), UsageResponse.class);
    return new UsageInfo(response.bytesUsed(), response.numObjects);
  }


}
