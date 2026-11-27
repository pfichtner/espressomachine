package com.github.pfichtner.espressomachine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards the per-block matching in {@link LlvmMethodEmitter#isBoxedNull}. A null is a dead
 * boxed stub only when its OWN block owns a primitive at the same raw index AND that index is
 * a spliced varargs element. The old global intersection (nullDef ∩ primDef) wrongly suppressed
 * an ordinary object null whose index merely collides with a primitive in another block.
 */
class BoxingNullTest {

    @Test
    void ordinaryNullCollidingWithOtherBlockPrimitiveSurvives() {
        var varargs = new HashSet<Integer>();
        varargs.add(7);    // a spliced varargs element owned by some OTHER block's primitive
        // This block's only primitive is at index 9, so a null at 7 here is an ordinary
        // object, not a boxed stub: per-block matching must keep it.
        assertFalse(LlvmMethodEmitter.isBoxedNull(Set.of(9), 7, 7, varargs));
    }

    @Test
    void sameBlockPrimitiveNullIsBoxed() {
        var varargs = new HashSet<Integer>();
        varargs.add(7);
        // This block's primitive at 7, and 7 is a varargs element: a genuine boxed stub.
        assertTrue(LlvmMethodEmitter.isBoxedNull(Set.of(7), 7, 7, varargs));
    }

    @Test
    void localPrimitiveButNotVarargsElementIsNotBoxed() {
        // A primitive at 7 whose value is a standalone object (not spliced into varargs):
        // its null is a real value and must survive.
        assertFalse(LlvmMethodEmitter.isBoxedNull(Set.of(7), 7, 7, Set.of()));
    }
}
