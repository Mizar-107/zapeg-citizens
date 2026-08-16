package io.github.mizar107.zapegcitizens.brain;

import com.google.gson.JsonArray;
import io.github.mizar107.zapegcitizens.brain.BrainProtocol.ActorIdentity;
import io.github.mizar107.zapegcitizens.brain.BrainProtocol.BrainReply;
import io.github.mizar107.zapegcitizens.brain.BrainProtocol.CitizenIdentity;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

final class BrainHttpClient {

    private final BrainConfig config;
    private final HttpClient client;

    BrainHttpClient(BrainConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    CompletableFuture<BrainReply> start(
            UUID requestId,
            CitizenIdentity citizen,
            ActorIdentity actor,
            String prompt,
            JsonArray tools) {
        return guarded(() -> post("/v1/turn/start", BrainProtocol.startBody(
                requestId, citizen, actor, prompt, tools)).thenApply(BrainProtocol::parseReply));
    }

    CompletableFuture<BrainReply> continueTurn(
            String turnId, String toolCallId, String resultJson) {
        return guarded(() -> post("/v1/turn/continue",
                BrainProtocol.continueBody(turnId, toolCallId, resultJson))
                .thenApply(BrainProtocol::parseReply));
    }

    CompletableFuture<Void> cancel(String turnId) {
        return guarded(() -> post("/v1/turn/cancel", BrainProtocol.cancelBody(turnId))
                .thenApply(ignored -> null));
    }

    CompletableFuture<Void> cancelRequest(UUID requestId) {
        return guarded(() -> post("/v1/turn/cancel", BrainProtocol.cancelRequestBody(requestId))
                .thenApply(ignored -> null));
    }

    CompletableFuture<Boolean> health() {
        return guarded(() -> {
            HttpRequest request = request("/healthz")
                    .GET()
                    .timeout(config.connectTimeout())
                    .build();
            return client.sendAsync(request, boundedUtf8Body())
                    .thenApply(response -> response.statusCode() == 200);
        })
                .exceptionally(ignored -> false);
    }

    private CompletableFuture<String> post(String path, String body) {
        HttpRequest request = request(path)
                .timeout(config.requestTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.sendAsync(request, boundedUtf8Body())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new BrainRequestException(
                                "brain returned HTTP " + response.statusCode() + ": "
                                        + BrainProtocol.parseError(response.body()));
                    }
                    return response.body();
                });
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(config.endpoint(path))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.token());
    }

    private static HttpResponse.BodyHandler<String> boundedUtf8Body() {
        return ignored -> new BoundedUtf8Subscriber(BrainProtocol.MAX_RESPONSE_BYTES);
    }

    private static <T> CompletableFuture<T> guarded(
            Supplier<CompletableFuture<T>> operation) {
        try {
            return Objects.requireNonNull(operation.get(), "HTTP operation returned no future");
        } catch (RuntimeException exception) {
            BrainRequestException failure = exception instanceof BrainRequestException requestException
                    ? requestException
                    : new BrainRequestException("could not construct brain request", exception);
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Buffers at most {@code limit} wire bytes, then cancels the HTTP body
     * subscription. Unlike {@code BodyHandlers.ofString}, an oversized peer can
     * never force the complete response into memory before rejection.
     */
    static final class BoundedUtf8Subscriber implements HttpResponse.BodySubscriber<String> {

        private static final int COPY_BUFFER_BYTES = 8 * 1024;

        private final int limit;
        private final ByteArrayOutputStream bytes;
        private final byte[] copyBuffer = new byte[COPY_BUFFER_BYTES];
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int received;

        BoundedUtf8Subscriber(int limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("response limit must be positive");
            }
            this.limit = limit;
            this.bytes = new ByteArrayOutputStream(Math.min(limit, COPY_BUFFER_BYTES));
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription newSubscription) {
            Objects.requireNonNull(newSubscription, "subscription");
            if (subscription != null) {
                newSubscription.cancel();
                return;
            }
            subscription = newSubscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            try {
                long nextSize = received;
                for (ByteBuffer buffer : buffers) {
                    nextSize += buffer.remaining();
                    if (nextSize > limit) {
                        fail(new BrainRequestException("brain response exceeded the size limit"));
                        return;
                    }
                }
                for (ByteBuffer buffer : buffers) {
                    while (buffer.hasRemaining()) {
                        int length = Math.min(buffer.remaining(), copyBuffer.length);
                        buffer.get(copyBuffer, 0, length);
                        bytes.write(copyBuffer, 0, length);
                    }
                }
                received = (int) nextSize;
                subscription.request(1);
            } catch (RuntimeException exception) {
                fail(exception);
            }
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toString(StandardCharsets.UTF_8));
        }

        private void fail(Throwable error) {
            if (subscription != null) {
                subscription.cancel();
            }
            body.completeExceptionally(error);
        }
    }

    static final class BrainRequestException extends RuntimeException {
        BrainRequestException(String message) {
            super(message);
        }

        BrainRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
