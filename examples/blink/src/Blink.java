import com.github.pfichtner.espressomachine.api.*;

class Blink {
    public static void main(String[] args) {
        GPIO.pinMode(13, GPIO.OUTPUT);

        while (true) {
            GPIO.digitalWrite(13, GPIO.HIGH);
            Delay.delay(500);
            GPIO.digitalWrite(13, GPIO.LOW);
            Delay.delay(500);
        }
    }
}
