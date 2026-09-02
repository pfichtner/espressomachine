class DelayTime {
    static void main() {
        GPIO.pinMode(13, GPIO.OUTPUT);

        while (true) {
            GPIO.digitalWrite(13, GPIO.HIGH);
            Delay.time(1, TimeUnit.SECONDS);
            GPIO.digitalWrite(13, GPIO.LOW);
            Delay.time(500, TimeUnit.MILLISECONDS);
        }
    }
}