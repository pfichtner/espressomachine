import com.github.pfichtner.espressomachine.api.*;

class DigitalRead {
    static void setup() {
        GPIO.pinMode(2, GPIO.INPUT_PULLUP);
        GPIO.pinMode(13, GPIO.OUTPUT);
    }

    static void loop() {
        if (GPIO.digitalRead(2) == GPIO.HIGH) {
            GPIO.digitalWrite(13, GPIO.HIGH);
            Delay.ms(200);
            GPIO.digitalWrite(13, GPIO.LOW);
            Delay.ms(200);
        } else {
            GPIO.digitalWrite(13, GPIO.LOW);
        }
    }
}
