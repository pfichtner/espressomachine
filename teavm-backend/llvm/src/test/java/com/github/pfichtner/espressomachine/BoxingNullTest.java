package com.github.pfichtner.espressomachine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

/**
* Guards the boxed-null suppression in {@link LlvmMethodEmitter#collectBoxedNulls}. A null is a
* dead boxed stub only when its canonical variable is a spliced varargs element AND it has no
* standalone object use. An ordinary null whose raw index merely collides with some primitive
* is never a varargs element, so it is never suppressed.
*/
class BoxingNullTest {

    @Test
    void splicedVarargsElementIsSuppressed() {
         // A boxed varargs element with no standalone object use: its primitive stands in
         // for the null, so the null is dead.
        assertTrue(LlvmMethodEmitter.isBoxedNull(varargs(7), 7, false));
     }

    @Test
    void varargsElementWithStandaloneUseSurvives() {
         // A boxed value that also escapes as a real object must keep its null.
        assertFalse(LlvmMethodEmitter.isBoxedNull(varargs(7), 7, true));
     }

    @Test
    void ordinaryNullCollidingWithPrimitiveSurvives() {
         // An ordinary object null whose canonical index is not a spliced varargs element
         // (even if it collides with some primitive's index) is the real value.
        assertFalse(LlvmMethodEmitter.isBoxedNull(new HashSet<>(), 7, false));
     }

     private static HashSet<Integer> varargs(int... indexes) {
        var s = new HashSet<Integer>();
        for (int i : indexes) s.add(i);
        return s;
     }
}
