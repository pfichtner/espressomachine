import java.util.concurrent.TimeUnit;

import com.github.pfichtner.espressomachine.api.Delay;
import com.github.pfichtner.espressomachine.api.GPIO;

class DelayTime {
    public static void main(String[] args) {
        GPIO.pinMode(13, GPIO.OUTPUT);

        while (true) {
            GPIO.digitalWrite(13, GPIO.HIGH);
            Delay.delay(1, TimeUnit.SECONDS);
            GPIO.digitalWrite(13, GPIO.LOW);
            Delay.delay(500, TimeUnit.MILLISECONDS);
        }
    }
}