/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.it.startup;

import static io.zeebe.broker.it.util.ZeebeAssertHelper.assertElementReady;
import static io.zeebe.broker.it.util.ZeebeAssertHelper.assertIncidentCreated;
import static io.zeebe.broker.it.util.ZeebeAssertHelper.assertIncidentResolveFailed;
import static io.zeebe.broker.it.util.ZeebeAssertHelper.assertIncidentResolved;
import static io.zeebe.broker.it.util.ZeebeAssertHelper.assertJobCompleted;
import static io.zeebe.broker.it.util.ZeebeAssertHelper.assertJobCreated;
import static io.zeebe.broker.it.util.ZeebeAssertHelper.assertWorkflowInstanceCompleted;
import static io.zeebe.broker.it.util.ZeebeAssertHelper.assertWorkflowInstanceCreated;
import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.Assert.fail;

import io.zeebe.broker.it.util.GrpcClientRule;
import io.zeebe.broker.test.EmbeddedBrokerRule;
import io.zeebe.client.api.response.ActivateJobsResponse;
import io.zeebe.client.api.response.ActivatedJob;
import io.zeebe.client.api.response.DeploymentEvent;
import io.zeebe.client.api.response.WorkflowInstanceEvent;
import io.zeebe.engine.processing.job.JobTimeoutTrigger;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.ValueType;
import io.zeebe.protocol.record.intent.IncidentIntent;
import io.zeebe.protocol.record.intent.JobIntent;
import io.zeebe.protocol.record.intent.TimerIntent;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import io.zeebe.protocol.record.intent.WorkflowInstanceSubscriptionIntent;
import io.zeebe.protocol.record.value.IncidentRecordValue;
import io.zeebe.test.util.record.RecordingExporter;
import io.zeebe.test.util.record.WorkflowInstances;
import io.zeebe.util.sched.clock.ControlledActorClock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public final class BrokerReprocessingTest {

  private static final String PROCESS_ID = "process";
  private static final String NULL_VARIABLES = "{}";
  private static final BpmnModelInstance WORKFLOW =
      Bpmn.createExecutableProcess(PROCESS_ID)
          .startEvent("start")
          .serviceTask("task", t -> t.zeebeJobType("foo"))
          .endEvent("end")
          .done();
  private static final BpmnModelInstance WORKFLOW_TWO_TASKS =
      Bpmn.createExecutableProcess(PROCESS_ID)
          .startEvent("start")
          .serviceTask("task1", t -> t.zeebeJobType("foo"))
          .serviceTask("task2", t -> t.zeebeJobType("bar"))
          .endEvent("end")
          .done();
  private static final BpmnModelInstance WORKFLOW_INCIDENT =
      Bpmn.createExecutableProcess(PROCESS_ID)
          .startEvent("start")
          .serviceTask("task", t -> t.zeebeJobType("test").zeebeInputExpression("foo", "foo"))
          .endEvent("end")
          .done();
  private static final BpmnModelInstance WORKFLOW_MESSAGE =
      Bpmn.createExecutableProcess(PROCESS_ID)
          .startEvent()
          .intermediateCatchEvent("catch-event")
          .message(m -> m.name("order canceled").zeebeCorrelationKeyExpression("orderId"))
          .sequenceFlowId("to-end")
          .endEvent()
          .done();
  private static final BpmnModelInstance WORKFLOW_TIMER =
      Bpmn.createExecutableProcess(PROCESS_ID)
          .startEvent()
          .intermediateCatchEvent("timer", c -> c.timerWithDuration("PT10S"))
          .endEvent()
          .done();

  @Parameter(0)
  public Consumer<BrokerReprocessingTest> reprocessingTrigger;

  @Parameter(1)
  public String name;

  public final EmbeddedBrokerRule brokerRule = new EmbeddedBrokerRule();
  public final GrpcClientRule clientRule = new GrpcClientRule(brokerRule);
  @Rule public RuleChain ruleChain = RuleChain.outerRule(brokerRule).around(clientRule);
  @Rule public ExpectedException exception = ExpectedException.none();
  @Rule public Timeout timeout = new Timeout(120, TimeUnit.SECONDS);
  private Runnable restartAction = () -> {};

  @Parameters(name = "{index}: {1}")
  public static Object[][] reprocessingTriggers() {
    return new Object[][] {
      new Object[] {
        (Consumer<BrokerReprocessingTest>) BrokerReprocessingTest::restartBroker, "restart"
      },
      new Object[] {
        (Consumer<BrokerReprocessingTest>) BrokerReprocessingTest::deleteSnapshotsAndRestart,
        "restart-without-snapshot"
      }
    };
  }

  @Test
  public void shouldDirectlyRestart() {
    // given

    // when
    reprocessingTrigger.accept(this);

    // then - no error
  }

  @Test
  public void shouldCreateWorkflowInstanceAfterRestart() {
    // given
    deploy(WORKFLOW, "workflow.bpmn");

    // when
    reprocessingTrigger.accept(this);

    clientRule
        .getClient()
        .newCreateInstanceCommand()
        .bpmnProcessId(PROCESS_ID)
        .latestVersion()
        .send()
        .join();

    // then
    assertWorkflowInstanceCreated();
  }

  @Test
  public void shouldContinueWorkflowInstanceAtTaskAfterRestart() {
    // given
    deploy(WORKFLOW, "workflow.bpmn");

    clientRule
        .getClient()
        .newCreateInstanceCommand()
        .bpmnProcessId(PROCESS_ID)
        .latestVersion()
        .send()
        .join();

    final var jobFoo = RecordingExporter.jobRecords(JobIntent.CREATED).withType("foo").getFirst();

    // when
    reprocessingTrigger.accept(this);
    completeJobWithKey(jobFoo.getKey());

    // then
    assertJobCompleted();
    assertWorkflowInstanceCompleted(PROCESS_ID);
  }

  @Test
  public void shouldContinueWorkflowInstanceWithLockedTaskAfterRestart() {
    // given
    deploy(WORKFLOW, "workflow.bpmn");

    clientRule
        .getClient()
        .newCreateInstanceCommand()
        .bpmnProcessId(PROCESS_ID)
        .latestVersion()
        .send()
        .join();

    final var activatedJobFoo = activateJob("foo");

    // when
    reprocessingTrigger.accept(this);

    awaitGateway();
    clientRule.getClient().newCompleteCommand(activatedJobFoo.getKey()).send().join();

    // then
    assertJobCompleted();
    assertWorkflowInstanceCompleted(PROCESS_ID);
  }

  @Test
  public void shouldContinueWorkflowInstanceAtSecondTaskAfterRestart() throws Exception {
    // given
    deploy(WORKFLOW_TWO_TASKS, "two-tasks.bpmn");

    final Duration defaultJobTimeout =
        clientRule.getClient().getConfiguration().getDefaultJobTimeout();

    clientRule
        .getClient()
        .newCreateInstanceCommand()
        .bpmnProcessId(PROCESS_ID)
        .latestVersion()
        .send()
        .join();

    final var jobFoo = RecordingExporter.jobRecords(JobIntent.CREATED).withType("foo").getFirst();
    completeJobWithKey(jobFoo.getKey());

    RecordingExporter.jobRecords(JobIntent.CREATED).withType("bar").getFirst();
    var activatedJobBar = activateJob("bar");

    // when
    restartAction = () -> brokerRule.getClock().addTime(defaultJobTimeout);
    reprocessingTrigger.accept(this);

    awaitJobTimeout();
    activatedJobBar = activateJob("bar");
    completeJobWithKey(activatedJobBar.getKey());

    // then
    assertJobCompleted("foo");
    assertJobCompleted("bar");
    assertWorkflowInstanceCompleted(PROCESS_ID);
  }

  @Test
  public void shouldDeployNewWorkflowVersionAfterRestart() {
    // given
    deploy(WORKFLOW, "workflow.bpmn");

    // when
    reprocessingTrigger.accept(this);

    final DeploymentEvent deploymentResult =
        clientRule
            .getClient()
            .newDeployCommand()
            .addWorkflowModel(WORKFLOW, "workflow-2.bpmn")
            .send()
            .join();

    // then
    assertThat(deploymentResult.getWorkflows().get(0).getVersion()).isEqualTo(2);

    final WorkflowInstanceEvent workflowInstanceV1 =
        clientRule
            .getClient()
            .newCreateInstanceCommand()
            .bpmnProcessId(PROCESS_ID)
            .version(1)
            .send()
            .join();

    final WorkflowInstanceEvent workflowInstanceV2 =
        clientRule
            .getClient()
            .newCreateInstanceCommand()
            .bpmnProcessId(PROCESS_ID)
            .latestVersion()
            .send()
            .join();

    // then
    assertThat(workflowInstanceV1.getVersion()).isEqualTo(1);
    assertThat(workflowInstanceV2.getVersion()).isEqualTo(2);
  }

  @Test
  public void shouldNotReceiveLockedJobAfterRestart() {
    // given
    clientRule.createSingleJob("foo");
    activateJob("foo");

    // when
    reprocessingTrigger.accept(this);

    // then
    awaitGateway();
    final ActivateJobsResponse jobsResponse =
        clientRule
            .getClient()
            .newActivateJobsCommand()
            .jobType("foo")
            .maxJobsToActivate(10)
            .workerName("this")
            .send()
            .join();

    assertThat(jobsResponse.getJobs()).isEmpty();
  }

  @Test
  public void shouldReceiveLockExpiredJobAfterRestart() {
    // given
    clientRule.createSingleJob("foo");
    final Duration defaultJobTimeout =
        clientRule.getClient().getConfiguration().getDefaultJobTimeout();
    activateJob("foo");

    // when
    restartAction = () -> brokerRule.getClock().addTime(defaultJobTimeout);
    reprocessingTrigger.accept(this);
    awaitJobTimeout();
    final var activatedJob = activateJob("foo");

    // then
    completeJobWithKey(activatedJob.getKey());
    assertJobCompleted();
  }

  @Test
  public void shouldResolveIncidentAfterRestart() {
    // given
    deploy(WORKFLOW_INCIDENT, "incident.bpmn");

    final WorkflowInstanceEvent instanceEvent =
        clientRule
            .getClient()
            .newCreateInstanceCommand()
            .bpmnProcessId(PROCESS_ID)
            .latestVersion()
            .send()
            .join();

    assertIncidentCreated();
    assertElementReady("task");

    final Record<IncidentRecordValue> incident =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED).getFirst();

    // when
    reprocessingTrigger.accept(this);

    clientRule
        .getClient()
        .newSetVariablesCommand(instanceEvent.getWorkflowInstanceKey())
        .variables("{\"foo\":\"bar\"}")
        .send()
        .join();

    clientRule.getClient().newResolveIncidentCommand(incident.getKey()).send().join();

    // then
    assertIncidentResolved();
    assertJobCreated("test");
  }

  @Test
  public void shouldResolveFailedIncidentAfterRestart() {
    // given
    deploy(WORKFLOW_INCIDENT, "incident.bpmn");

    final WorkflowInstanceEvent instanceEvent =
        clientRule
            .getClient()
            .newCreateInstanceCommand()
            .bpmnProcessId(PROCESS_ID)
            .latestVersion()
            .send()
            .join();

    assertIncidentCreated();
    assertElementReady("task");

    Record<IncidentRecordValue> incident =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED).getFirst();

    clientRule
        .getClient()
        .newSetVariablesCommand(instanceEvent.getWorkflowInstanceKey())
        .variables("{\"x\":\"y\"}")
        .send()
        .join();

    clientRule.getClient().newResolveIncidentCommand(incident.getKey()).send().join();

    assertIncidentResolveFailed();

    // when
    reprocessingTrigger.accept(this);

    incident = RecordingExporter.incidentRecords(IncidentIntent.CREATED).getLast();

    clientRule
        .getClient()
        .newSetVariablesCommand(instanceEvent.getWorkflowInstanceKey())
        .variables("{\"foo\":\"bar\"}")
        .send()
        .join();

    clientRule.getClient().newResolveIncidentCommand(incident.getKey()).send().join();

    // then
    assertIncidentResolved();
    assertJobCreated("test");
  }

  @Test
  public void shouldAssignUniqueWorkflowInstanceKeyAfterRestart() {
    // given
    deploy(WORKFLOW, "workflow.bpmn");

    final long workflowInstance1Key = startWorkflowInstance(PROCESS_ID).getWorkflowInstanceKey();

    // when
    reprocessingTrigger.accept(this);

    final long workflowInstance2Key = startWorkflowInstance(PROCESS_ID).getWorkflowInstanceKey();

    // then
    assertThat(workflowInstance2Key).isGreaterThan(workflowInstance1Key);
  }

  @Test
  public void shouldAssignUniqueJobKeyAfterRestart() {
    // given
    deploy(WORKFLOW, "workflow.bpmn");

    final Supplier<Long> jobCreator = () -> clientRule.createSingleJob("foo");

    final long job1Key = jobCreator.get();

    // when
    reprocessingTrigger.accept(this);

    final long job2Key = jobCreator.get();

    // then
    assertThat(job2Key).isGreaterThan(job1Key);
  }

  @Test
  public void shouldAssignUniqueIncidentKeyAfterRestart() {
    // given
    deploy(WORKFLOW_INCIDENT, "incident.bpmn");

    final long workflowInstanceKey = startWorkflowInstance(PROCESS_ID).getWorkflowInstanceKey();
    assertIncidentCreated();

    // when
    reprocessingTrigger.accept(this);

    final long workflowInstanceKey2 = startWorkflowInstance(PROCESS_ID).getWorkflowInstanceKey();

    // then
    final long firstIncidentKey =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED)
            .withWorkflowInstanceKey(workflowInstanceKey)
            .getFirst()
            .getKey();

    final long secondIncidentKey =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED)
            .withWorkflowInstanceKey(workflowInstanceKey2)
            .getFirst()
            .getKey();

    assertThat(firstIncidentKey).isLessThan(secondIncidentKey);
  }

  @Test
  public void shouldAssignUniqueDeploymentKeyAfterRestart() {
    // given
    final long deployment1Key =
        clientRule
            .getClient()
            .newDeployCommand()
            .addWorkflowModel(WORKFLOW_INCIDENT, "incident.bpmn")
            .send()
            .join()
            .getKey();

    clientRule.waitUntilDeploymentIsDone(deployment1Key);
    // when
    reprocessingTrigger.accept(this);

    final long deployment2Key =
        clientRule
            .getClient()
            .newDeployCommand()
            .addWorkflowModel(WORKFLOW_INCIDENT, "incident.bpmn")
            .send()
            .join()
            .getKey();

    // then
    assertThat(deployment2Key).isGreaterThan(deployment1Key);
  }

  @Test
  public void shouldCorrelateMessageAfterRestartIfEnteredBefore() throws Exception {
    // given
    deploy(WORKFLOW_MESSAGE, "message.bpmn");

    final long workflowInstanceKey =
        startWorkflowInstance(PROCESS_ID, singletonMap("orderId", "order-123"))
            .getWorkflowInstanceKey();

    assertThat(
            RecordingExporter.workflowInstanceSubscriptionRecords(
                    WorkflowInstanceSubscriptionIntent.OPENED)
                .exists())
        .isTrue();

    reprocessingTrigger.accept(this);

    // when
    publishMessage("order canceled", "order-123", singletonMap("foo", "bar"));

    // then
    assertThat(
            RecordingExporter.workflowInstanceRecords(WorkflowInstanceIntent.ELEMENT_COMPLETED)
                .withElementId("catch-event")
                .exists())
        .isTrue();

    assertWorkflowInstanceCompleted(workflowInstanceKey);
    assertThat(WorkflowInstances.getCurrentVariables(workflowInstanceKey))
        .containsOnly(entry("foo", "\"bar\""), entry("orderId", "\"order-123\""));
  }

  @Test
  public void shouldCorrelateMessageAfterRestartIfPublishedBefore() throws Exception {
    // given
    deploy(WORKFLOW_MESSAGE, "message.bpmn");

    publishMessage("order canceled", "order-123", singletonMap("foo", "bar"));
    reprocessingTrigger.accept(this);

    // when
    final long workflowInstanceKey =
        startWorkflowInstance(PROCESS_ID, singletonMap("orderId", "order-123"))
            .getWorkflowInstanceKey();
    assertThat(
            RecordingExporter.workflowInstanceRecords(WorkflowInstanceIntent.ELEMENT_COMPLETED)
                .withElementId("catch-event")
                .exists())
        .isTrue();

    // then
    assertWorkflowInstanceCompleted(workflowInstanceKey);
    assertThat(WorkflowInstances.getCurrentVariables(workflowInstanceKey))
        .containsOnly(entry("foo", "\"bar\""), entry("orderId", "\"order-123\""));
  }

  @Test
  public void shouldTriggerTimerAfterRestart() {
    // given
    deploy(WORKFLOW_TIMER, "timer.bpmn");

    startWorkflowInstance(PROCESS_ID);

    assertThat(RecordingExporter.timerRecords(TimerIntent.CREATED).exists()).isTrue();

    // when
    restartAction = () -> brokerRule.getClock().addTime(Duration.ofSeconds(10));
    reprocessingTrigger.accept(this);

    // then
    assertThat(
            RecordingExporter.workflowInstanceRecords(WorkflowInstanceIntent.ELEMENT_COMPLETED)
                .withElementId("timer")
                .exists())
        .isTrue();
  }

  private WorkflowInstanceEvent startWorkflowInstance(final String bpmnProcessId) {
    return clientRule
        .getClient()
        .newCreateInstanceCommand()
        .bpmnProcessId(bpmnProcessId)
        .latestVersion()
        .send()
        .join();
  }

  protected WorkflowInstanceEvent startWorkflowInstance(
      final String bpmnProcessId, final Map<String, Object> variables) {
    return clientRule
        .getClient()
        .newCreateInstanceCommand()
        .bpmnProcessId(bpmnProcessId)
        .latestVersion()
        .variables(variables)
        .send()
        .join();
  }

  protected void publishMessage(
      final String messageName, final String correlationKey, final Map<String, Object> variables) {
    clientRule
        .getClient()
        .newPublishMessageCommand()
        .messageName(messageName)
        .correlationKey(correlationKey)
        .variables(variables)
        .send()
        .join();
  }

  private void completeJobWithKey(final long key) {
    clientRule.getClient().newCompleteCommand(key).variables(NULL_VARIABLES).send().join();
  }

  private ActivatedJob activateJob(final String jobType) {
    RecordingExporter.jobRecords(JobIntent.CREATED).withType(jobType).await();
    return Awaitility.await("activateJob")
        .until(
            () ->
                clientRule
                    .getClient()
                    .newActivateJobsCommand()
                    .jobType(jobType)
                    .maxJobsToActivate(1)
                    .send()
                    .join()
                    .getJobs(),
            jobs -> !jobs.isEmpty())
        .stream()
        .findFirst()
        .orElseThrow();
  }

  protected void deleteSnapshotsAndRestart() {
    brokerRule.stopBroker();

    // delete snapshot files to trigger recovery
    try {
      brokerRule.purgeSnapshots();
    } catch (final Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }

    restartAction.run();

    brokerRule.startBroker();
  }

  protected void restartBroker() {
    brokerRule.stopBroker();

    restartAction.run();

    brokerRule.startBroker();
  }

  private void deploy(final BpmnModelInstance workflowTwoTasks, final String s) {
    final DeploymentEvent deploymentEvent =
        clientRule
            .getClient()
            .newDeployCommand()
            .addWorkflowModel(workflowTwoTasks, s)
            .send()
            .join();

    clientRule.waitUntilDeploymentIsDone(deploymentEvent.getKey());
  }

  private void awaitGateway() {
    Awaitility.await("awaitGateway")
        .until(
            () -> {
              try {
                clientRule.getClient().newTopologyRequest().send().join();
                return true;
              } catch (final Exception e) {
                return false;
              }
            });
  }

  private void awaitJobTimeout() {
    final Duration defaultJobTimeout =
        clientRule.getClient().getConfiguration().getDefaultJobTimeout();

    final ControlledActorClock clock = brokerRule.getClock();
    final Duration pollingInterval =
        JobTimeoutTrigger.TIME_OUT_POLLING_INTERVAL
            // this shouldn't be needed but is caused by the fact hat on reprocessing without
            // a snapshot a new deadline is set for the job
            // https://github.com/zeebe-io/zeebe/issues/1800
            .plus(defaultJobTimeout);

    Awaitility.await("awaitJobTimeout")
        .until(
            () -> {
              clock.addTime(pollingInterval);
              // not using RecordingExporter.jobRecords cause it is blocking
              return RecordingExporter.getRecords().stream()
                  .filter(r -> r.getValueType() == ValueType.JOB)
                  .anyMatch(r -> r.getIntent() == JobIntent.TIMED_OUT);
            });
  }
}
