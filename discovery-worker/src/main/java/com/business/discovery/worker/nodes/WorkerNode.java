package com.business.discovery.worker.nodes;

import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;

public interface WorkerNode {

    void execute(WorkerContext ctx) throws WorkerException;
}
