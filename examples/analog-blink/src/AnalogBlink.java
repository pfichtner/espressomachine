import com.github.pfichtner.espressomachine.api.*;

// Blinks LED on pin 13 at a rate determined by the analog value on A0.
// A0 > 512 → 100 ms half-period (fast).  A0 ≤ 512 → 500 ms half-period (slow).
class AnalogBlink {
    static void main() {
        GPIO.pinMode(13, GPIO.OUTPUT);
        while (true) {
            int ms = GPIO.analogRead(GPIO.A0) > 512 ? 100 : 500;
            GPIO.digitalWrite(13, GPIO.HIGH);
            Delay.ms(ms);
            GPIO.digitalWrite(13, GPIO.LOW);
            Delay.ms(ms);
        }
    }
}
