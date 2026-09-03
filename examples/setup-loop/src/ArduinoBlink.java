class ArduinoBlink {
    static int counter;

    static void setup() {
        counter = 0;
    }

    static void loop() {
        counter = counter + 1;
    }
}
