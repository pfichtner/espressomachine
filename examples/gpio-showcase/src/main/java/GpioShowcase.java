import static com.github.pfichtner.espressomachine.api.GPIO.HIGH;
import static com.github.pfichtner.espressomachine.api.GPIO.INPUT_PULLUP;
import static com.github.pfichtner.espressomachine.api.GPIO.LOW;
import static com.github.pfichtner.espressomachine.api.GPIO.OUTPUT;

import java.util.concurrent.TimeUnit;

import com.github.pfichtner.espressomachine.api.Gpio;

class GpioShowcase {

	Gpio gpio = new Gpio();

	public static void main(String[] args) throws InterruptedException {
		new GpioShowcase().run();
	}

	void run() throws InterruptedException {
		setup();
		while (true) {
			loop();
		}
	}

	void setup() {
		gpio.pinMode(2, INPUT_PULLUP);
		gpio.pinMode(13, OUTPUT);
	}

	void loop() throws InterruptedException {
		if (gpio.digitalRead(2) == HIGH) {
			gpio.digitalWrite(13, HIGH);
			TimeUnit.MILLISECONDS.sleep(200);
			gpio.digitalWrite(13, LOW);
			TimeUnit.MILLISECONDS.sleep(200);
		} else {
			gpio.digitalWrite(13, LOW);
		}
	}

}
