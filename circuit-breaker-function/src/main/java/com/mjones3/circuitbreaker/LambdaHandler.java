package com.mjones3.circuitbreaker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Supplier;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

public class LambdaHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // 1) Shared HTTP client
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // 2) CircuitBreaker + CloudWatch registry
    private static final CircuitBreaker circuitBreaker;
    private static final CloudWatchMeterRegistry meterRegistry;

    static {
        // a) Configure the circuit breaker
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(cbConfig);
        circuitBreaker = cbRegistry.circuitBreaker("externalApiCB");

        // b) Configure Micrometer → CloudWatch (async client)
        CloudWatchConfig cwConfig = new CloudWatchConfig() {
            @Override
            public String get(String key) {
                return null;
            }            // use defaults

            @Override
            public Duration step() {
                return Duration.ofMinutes(1);
            }// publish interval

            @Override
            public String namespace() {
                return "mjones3/CircuitBreakers";
            }
        };

        CloudWatchAsyncClient awsClient = CloudWatchAsyncClient.create();
        meterRegistry = new ExampleCloudWatchMeterRegistry(cwConfig, Clock.SYSTEM, awsClient);

        // c) Bind Resilience4j metrics to Micrometer
        TaggedCircuitBreakerMetrics
                .ofCircuitBreakerRegistry(cbRegistry)
                .bindTo(meterRegistry);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent request,
            Context context) {

        // 3) Decorate and invoke your blocking HTTP call
        Supplier<String> decorated = CircuitBreaker
                .decorateSupplier(circuitBreaker, () -> {
                    try {
                        return callExternalApi();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });

        APIGatewayProxyResponseEvent response;
        try {
            String body = decorated.get();
            response = new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(body);

        } catch (CallNotPermittedException open) {
            response = new APIGatewayProxyResponseEvent()
                    .withStatusCode(503)
                    .withBody("{\"message\":\"Circuit is open\"}");

        } catch (Exception e) {
            response = new APIGatewayProxyResponseEvent()
                    .withStatusCode(502)
                    .withBody("{\"message\":\"Downstream failure: " + e.getMessage() + "\"}");
        }

        ((ExampleCloudWatchMeterRegistry) meterRegistry).publishNow();
        // Note: we no longer call meterRegistry.publish() here
        // StepMeterRegistry (which CloudWatchMeterRegistry extends) 
        // will push metrics on its own schedule (every 'step' interval).
        return response;
    }

    private String callExternalApi() throws Exception {
        String url = System.getenv()
                .getOrDefault("EXTERNAL_API_URL", "https://httpstat.us/503");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 500) {
            throw new RuntimeException("Downstream error: " + resp.statusCode());
        }
        return resp.body();
    }
}
