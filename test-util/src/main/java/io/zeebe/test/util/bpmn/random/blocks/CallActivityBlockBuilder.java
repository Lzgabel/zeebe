/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.test.util.bpmn.random.blocks;

import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.model.bpmn.builder.AbstractFlowNodeBuilder;
import io.zeebe.test.util.bpmn.random.BlockBuilder;
import io.zeebe.test.util.bpmn.random.BlockBuilderFactory;
import io.zeebe.test.util.bpmn.random.ConstructionContext;
import io.zeebe.test.util.bpmn.random.ExecutionPathSegment;
import io.zeebe.test.util.bpmn.random.IDGenerator;
import java.util.Random;

/** Generates a call activity. The called process is a Process that contains any block sequence. */
public class CallActivityBlockBuilder implements BlockBuilder {

  private final boolean shouldPropagateAllChildVariables;
  private final String calledProcessId;
  private final BlockSequenceBuilder calledProcessBuilder;
  private final ConstructionContext context;
  private final String callActivityId;

  public CallActivityBlockBuilder(final ConstructionContext context) {
    final Random random = context.getRandom();
    final IDGenerator idGenerator = context.getIdGenerator();
    final int maxDepth = context.getMaxDepth();
    final int currentDepth = context.getCurrentDepth();
    final boolean goDeeper = random.nextInt(maxDepth) > currentDepth;

    this.context = context;
    shouldPropagateAllChildVariables = random.nextBoolean();

    final var reusableId = idGenerator.nextId();
    calledProcessId = "process_child_" + reusableId;
    callActivityId = "call_activity_" + reusableId;

    if (goDeeper) {
      calledProcessBuilder =
          context
              .getBlockSequenceBuilderFactory()
              .createBlockSequenceBuilder(context.withIncrementedDepth());
    } else {
      calledProcessBuilder = null;
    }
  }

  @Override
  public AbstractFlowNodeBuilder<?, ?> buildFlowNodes(
      final AbstractFlowNodeBuilder<?, ?> nodeBuilder) {

    buildChildProcess();

    return nodeBuilder
        .callActivity(callActivityId)
        .zeebeProcessId(calledProcessId)
        .zeebePropagateAllChildVariables(shouldPropagateAllChildVariables);
  }

  @Override
  public ExecutionPathSegment findRandomExecutionPath(final Random random) {
    final ExecutionPathSegment result = new ExecutionPathSegment();

    if (calledProcessBuilder != null) {
      result.append(calledProcessBuilder.findRandomExecutionPath(random));
    }

    return result;
  }

  private void buildChildProcess() {
    AbstractFlowNodeBuilder<?, ?> workInProgress =
        Bpmn.createExecutableProcess(calledProcessId).startEvent();

    if (calledProcessBuilder != null) {
      workInProgress = calledProcessBuilder.buildFlowNodes(workInProgress);
    }

    final BpmnModelInstance childModelInstance = workInProgress.endEvent().done();
    context.addCalledChildProcess(childModelInstance);
  }

  public static class Factory implements BlockBuilderFactory {

    @Override
    public BlockBuilder createBlockBuilder(final ConstructionContext context) {
      return new CallActivityBlockBuilder(context);
    }

    @Override
    public boolean isAddingDepth() {
      return true;
    }
  }
}
