package com.business.discovery.worker.constants;

public enum FailureType {
    CODE,        // compilation/build error — retryable with error context fed to LLM
    INFRA,       // network, disk, Docker, DB — retryable
    CONFIG_AUTH  // bad credentials, missing token — not retried, alerts human
}
