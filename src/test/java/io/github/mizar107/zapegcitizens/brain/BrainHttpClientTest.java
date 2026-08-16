package io.github.mizar107.zapegcitizens.brain;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BrainHttpClientTest {

    @Test
    void boundedSubscriberReturnsResponsesWithinTheByteBudget() {
        BrainHttpClient.BoundedUtf8Subscriber subscriber =
                new BrainHttpClient.BoundedUtf8Subscriber(5);
        RecordingSubscription subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8))));
        subscriber.onComplete();

        assertEquals("hello", subscriber.getBody().toCompletableFuture().join());
        assertEquals(2, subscription.requests);
        assertTrue(!subscription.cancelled);
    }

    @Test
    void boundedSubscriberCancelsBeforeBufferingAnOversizedChunk() {
        BrainHttpClient.BoundedUtf8Subscriber subscriber =
                new BrainHttpClient.BoundedUtf8Subscriber(5);
        RecordingSubscription subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap("123456".getBytes(StandardCharsets.UTF_8))));

        CompletionException failure = assertThrows(CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join());
        assertInstanceOf(BrainHttpClient.BrainRequestException.class, failure.getCause());
        assertTrue(subscription.cancelled);
    }

    @Test
    void synchronousBodyConstructionFailureBecomesAFailedFuture() {
        BrainHttpClient client = new BrainHttpClient(new BrainConfig(
                URI.create("http://127.0.0.1:8787"),
                "bridge-secret",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofMinutes(10),
                4));

        var future = assertDoesNotThrow(() -> client.start(null, null, null, null, null));
        assertThrows(CompletionException.class, future::join);
    }

    private static final class RecordingSubscription implements Flow.Subscription {
        private long requests;
        private boolean cancelled;

        @Override
        public void request(long amount) {
            requests += amount;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
