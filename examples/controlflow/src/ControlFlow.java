class ControlFlow {
    static int test(int x) {
        if (x > 10) return 1;
        else return 0;
    }

    static int count() {
        int x = 0;
        while (x < 10) x++;
        return x;
    }

    static void main() {
        test(5);
        count();
    }
}
