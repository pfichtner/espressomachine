import com.github.pfichtner.espressomachine.api.*;

class DigitalRead {
    static void setup() {
        GPIO.pinMode(2, GPIO.INPUT_PULLUP);
        GPIO.pinMode(13, GPIO.OUTPUT);
    }

    static void loop() {
        GPIO.digitalWrite(13, GPIO.digitalRead(2));
    }
}
