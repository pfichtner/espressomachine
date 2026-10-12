import static com.github.pfichtner.espressomachine.api.GPIO.HIGH;
import static com.github.pfichtner.espressomachine.api.GPIO.INPUT_PULLUP;
import static com.github.pfichtner.espressomachine.api.GPIO.LOW;
import static com.github.pfichtner.espressomachine.api.GPIO.OUTPUT;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.github.pfichtner.espressomachine.api.Gpio;

class GpioShowcaseTest {

	@Test
	void drivesLedFromButton() {
		GpioShowcase sut = new GpioShowcase();
		sut.gpio = mock(Gpio.class);
		when(sut.gpio.digitalRead(2)).thenReturn(HIGH);

		var executor = newSingleThreadExecutor();
		executor.execute(() -> {
			try {
				sut.run();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});

		try {
			await().atMost(3, SECONDS).untilAsserted(() -> {
				InOrder order = inOrder(sut.gpio);
				order.verify(sut.gpio).pinMode(2, INPUT_PULLUP);
				order.verify(sut.gpio).pinMode(13, OUTPUT);
				order.verify(sut.gpio).digitalWrite(13, HIGH);
				order.verify(sut.gpio).digitalWrite(13, LOW);
			});
		} finally {
			executor.shutdownNow();
		}
	}

}
