/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.system.partitions.impl.steps;

import io.camunda.zeebe.broker.system.partitions.PartitionContext;
import io.camunda.zeebe.broker.system.partitions.PartitionStep;
import io.camunda.zeebe.broker.system.partitions.impl.AsyncSnapshotDirector;
import io.camunda.zeebe.util.sched.future.ActorFuture;
import java.time.Duration;

public class SnapshotDirectorPartitionStep implements PartitionStep {

  @Override
  public ActorFuture<Void> open(final PartitionContext context) {
    final Duration snapshotPeriod = context.getBrokerCfg().getData().getSnapshotPeriod();
    final AsyncSnapshotDirector director =
        new AsyncSnapshotDirector(
            context.getNodeId(),
            context.getStreamProcessor(),
            context.getSnapshotController(),
            context.getLogStream(),
            snapshotPeriod);

    context.setSnapshotDirector(director);
    context.getComponentHealthMonitor().registerComponent(director.getName(), director);
    return context.getScheduler().submitActor(director);
  }

  @Override
  public ActorFuture<Void> close(final PartitionContext context) {
    final ActorFuture<Void> future = context.getSnapshotDirector().closeAsync();
    context.setSnapshotDirector(null);
    return future;
  }

  @Override
  public String getName() {
    return "AsyncSnapshotDirector";
  }
}
