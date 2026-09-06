class MemoryTest {
    // Static object field — must become a global struct
    static Counter counter = new Counter();

    // Non-escaping local — OK with alloca
    static void localUse() {
        Counter c = new Counter();
        c.increment();
    }

    // Escaping allocation — must produce compile error
    static Counter escape() {
        return new Counter();
    }

    public static void main(String[] args) {
        counter.increment();
        localUse();
    }
}

class Counter {
    int value;
    void increment() { value++; }
    int get() { return value; }
}
