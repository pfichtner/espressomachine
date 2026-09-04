package com.github.pfichtner.espressomachine.emit;

import java.util.Collections;
import java.util.Map;
import java.util.function.Predicate;

import org.teavm.model.BasicBlock;
import org.teavm.model.Instruction;
import org.teavm.model.Program;
import org.teavm.model.instructions.InvokeInstruction;

public final class InvokeInstructions {
	private InvokeInstructions() {}
	
	public static Predicate<InvokeInstruction> isClassname(String className) {
		return i -> className.equals(i.getMethod().getClassName());
	}

	public static Predicate<InvokeInstruction> isMethodname(String methodName) {
		return i -> methodName.equals(i.getMethod().getName());
	}

	public static boolean isUsedIn(Map<String, Program> programs, Predicate<InvokeInstruction> p) {
		for (Program prog : programs.values()) {
			for (int bi = 0; bi < (prog == null ? 0 : prog.basicBlockCount()); bi++) {
				for (BasicBlock basicBlock : prog.getBasicBlocks()) {
					for (Instruction insn : basicBlock == null ? Collections.<Instruction>emptyList() : basicBlock) {
						if (insn instanceof InvokeInstruction inv && p.test(inv)) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

}
