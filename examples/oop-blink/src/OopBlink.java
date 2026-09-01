class OopBlink {
    static void main() {
        Led led = new Led(13);

        while (true) {
            led.on();
            Delay.ms(500);
            led.off();
            Delay.ms(500);
        }
    }
}
