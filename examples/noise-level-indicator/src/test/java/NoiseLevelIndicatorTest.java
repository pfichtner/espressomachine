import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.github.pfichtner.espressomachine.api.Gpio;

class NoiseLevelIndicatorTest {

    @Test
    void alternatelyHighAndLowOnInternalLed() {
        NoiseLevelIndicator sut = new NoiseLevelIndicator();
        sut.gpio = mock(Gpio.class);

        var executor = newSingleThreadExecutor();
        executor.execute(() -> {
            try { sut.main(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        try {
            await().atMost(3, SECONDS).untilAsserted(() -> {
                InOrder order = inOrder(sut.gpio);
                order.verify(sut.gpio).digitalWrite(13, 1);
                order.verify(sut.gpio).digitalWrite(13, 0);
            });
        } finally {
            executor.shutdownNow();
        }
    }
}
