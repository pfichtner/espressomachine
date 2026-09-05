import java.util.concurrent.TimeUnit;

import com.github.pfichtner.espressomachine.api.Gpio;

public class NoiseLevelIndicator {

    int INTERNAL_LED = 13;
    Gpio gpio = new Gpio();

    public static void main(String... args) throws InterruptedException {
        new NoiseLevelIndicator().main();
    }

    void main() throws InterruptedException {
        gpio.pinMode(INTERNAL_LED, 1);
        while (true) {
            gpio.digitalWrite(13, 1);
            TimeUnit.MILLISECONDS.sleep(500);
            gpio.digitalWrite(13, 0);
            TimeUnit.MILLISECONDS.sleep(500);
        }
    }
}
