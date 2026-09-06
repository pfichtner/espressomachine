class Counter {
    int value;

    void increment() {
        value++;
    }

    int get() {
        return value;
    }

    public static void main(String[] args) {
        Counter c = new Counter();
        c.increment();
        c.increment();
        c.get();
    }
}
