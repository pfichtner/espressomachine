class Counter {
    int value;

    void increment() {
        value++;
    }

    int get() {
        return value;
    }

    static void main() {
        Counter c = new Counter();
        c.increment();
        c.increment();
        c.get();
    }
}
