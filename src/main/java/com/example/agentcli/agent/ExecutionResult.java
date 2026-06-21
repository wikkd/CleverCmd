package com.example.agentcli.agent;

import java.util.List;

public record ExecutionResult(
	ExecutionStatus status,
	String message,
	List<UndoAction> undoActions
) {
	public static ExecutionResult success(String message, List<UndoAction> undoActions) {
		return new ExecutionResult(ExecutionStatus.SUCCESS, message, undoActions);
	}

	public static ExecutionResult noOp(String message) {
		return new ExecutionResult(ExecutionStatus.NO_OP, message, List.of());
	}

	public static ExecutionResult failed(String message) {
		return new ExecutionResult(ExecutionStatus.FAILED, message, List.of());
	}

	public static ExecutionResult partial(String message, List<UndoAction> undoActions) {
		return new ExecutionResult(ExecutionStatus.PARTIAL, message, undoActions);
	}
}
