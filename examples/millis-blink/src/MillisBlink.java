import com.github.pfichtner.espressomachine.api.*;

class MillisBlink {
    static int lastToggle;
    static int ledState;

    static void setup() {
        GPIO.pinMode(13, GPIO.OUTPUT);
        lastToggle = Time.millis();
        ledState = 0;
    }

    static void loop() {
        int now = Time.millis();
        if (now - lastToggle >= 500) {
            ledState = 1 - ledState;
            GPIO.digitalWrite(13, ledState);
            lastToggle = now;
        }
    }
}
