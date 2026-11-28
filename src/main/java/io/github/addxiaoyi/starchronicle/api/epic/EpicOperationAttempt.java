package io.github.addxiaoyi.starchronicle.api.epic;

public record EpicOperationAttempt(String operationId, String rootOperationId, long attempt,
                                   String previousOperationId, EpicRejectionProof rejectionProof) {}
