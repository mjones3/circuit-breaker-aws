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

public class LambdaHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // Shared HTTP client
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // Static CircuitBreaker instance
    private static final CircuitBreaker circuitBreaker;

    static {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                // trip if 50% of calls in the sliding window fail
                .failureRateThreshold(50)
                // use a count-based window of 10 calls
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                // stay open for 30 seconds before retrying
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        circuitBreaker = CircuitBreakerRegistry.of(config)
                .circuitBreaker("externalApiCB");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent request,
            Context context) {

        // Decorate your blocking call with the circuit breaker,
        // using a lambda that catches checked Exceptions.
        Supplier<String> decorated = CircuitBreaker
                .decorateSupplier(circuitBreaker, () -> {
                    try {
                        return callExternalApi();
                    } catch (Exception ex) {
                        // wrap checked exceptions as unchecked
                        throw new RuntimeException(ex);
                    }
                });

        try {
            String body = decorated.get();
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(body);
        } catch (CallNotPermittedException e) {
            // Circuit is open
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(503)
                    .withBody("{\"message\":\"Circuit is open\"}");
        } catch (Exception e) {
            // Downstream or other failure
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(502)
                    .withBody("{\"message\":\"Downstream failure: " + e.getMessage() + "\"}");
        }
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
