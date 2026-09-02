import bytelight.api.*;

class Blink {
    static void main() {
        GPIO.pinMode(13, GPIO.OUTPUT);

        while (true) {
            GPIO.digitalWrite(13, GPIO.HIGH);
            Delay.ms(500);
            GPIO.digitalWrite(13, GPIO.LOW);
            Delay.ms(500);
        }
    }
}
