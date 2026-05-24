package com.business.discovery.worker.errorhandler;

import com.business.discovery.worker.constants.FailureType;

public class WorkerException extends RuntimeException {

    private final FailureType failureType;

    public WorkerException(FailureType failureType, String message) {
        super(message);
        this.failureType = failureType;
    }

    public WorkerException(FailureType failureType, String message, Throwable cause) {
        super(message, cause);
        this.failureType = failureType;
    }

    public FailureType getFailureType() {
        return failureType;
    }
}
