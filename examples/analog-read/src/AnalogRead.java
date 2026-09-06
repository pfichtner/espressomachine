import com.github.pfichtner.espressomachine.api.*;

class AnalogRead {
    public static void main(String[] args) {
        GPIO.pinMode(13, GPIO.OUTPUT);

        while (true) {
            int value = GPIO.analogRead(GPIO.A0);
            if (value > 512) {
                GPIO.digitalWrite(13, GPIO.HIGH);
                Delay.delay(100);
            } else {
                GPIO.digitalWrite(13, GPIO.LOW);
                Delay.delay(500);
            }
        }
    }
}
