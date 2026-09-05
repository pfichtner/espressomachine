import java.util.concurrent.TimeUnit;

import com.github.pfichtner.espressomachine.api.GPIO;

class ThreadSleep {
    static void main() throws InterruptedException {
        GPIO.pinMode(13, GPIO.OUTPUT);

        while (true) {
            GPIO.digitalWrite(13, GPIO.HIGH);
            Thread.sleep(1000);
            GPIO.digitalWrite(13, GPIO.LOW);
            Thread.sleep(500, 0);
            GPIO.digitalWrite(13, GPIO.HIGH);
            TimeUnit.SECONDS.sleep(2);
            GPIO.digitalWrite(13, GPIO.LOW);
            TimeUnit.MILLISECONDS.sleep(250);
        }
    }
}
