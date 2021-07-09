/*
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.raft.impl;

import static org.mockito.Mockito.spy;

import io.atomix.utils.concurrent.SingleThreadContext;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PriorityElectionTimerTest {

  private final Logger log = LoggerFactory.getLogger(PriorityElectionTimerTest.class);
  private final SingleThreadContext threadContext = new SingleThreadContext("priorityElectionTest");

  @After
  public void after() {
    threadContext.close();
  }

  @Test
  public void shouldLowerPriorityNodeEventuallyStartsAnElection() {
    // given
    final AtomicInteger triggerCount = new AtomicInteger();

    final PriorityElectionTimer timer =
        new PriorityElectionTimer(
            Duration.ofMillis(100), threadContext, triggerCount::getAndIncrement, log, 4, 1);

    // when
    timer.reset();

    // then
    Awaitility.await().until(() -> triggerCount.get() >= 1);
  }

  @Test
  public void shouldHighPriorityNodeStartElectionFirst() {
    // given
    final AtomicBoolean highPrioElectionTriggered = spy(new AtomicBoolean());
    final AtomicBoolean lowPrioElectionTriggered = spy(new AtomicBoolean());

    final int targetPriority = 4;
    final PriorityElectionTimer timerHighPrio =
        new PriorityElectionTimer(
            Duration.ofMillis(100),
            threadContext,
            () -> highPrioElectionTriggered.set(true),
            log,
            targetPriority,
            targetPriority);

    final PriorityElectionTimer timerLowPrio =
        new PriorityElectionTimer(
            Duration.ofMillis(100),
            threadContext,
            () -> lowPrioElectionTriggered.set(true),
            log,
            targetPriority,
            1);

    // when
    timerLowPrio.reset();
    timerHighPrio.reset();
    Awaitility.await().until(highPrioElectionTriggered::get);
    Awaitility.await().until(lowPrioElectionTriggered::get);

    // then
    final var inorder = Mockito.inOrder(highPrioElectionTriggered, lowPrioElectionTriggered);
    inorder.verify(highPrioElectionTriggered).set(true);
    inorder.verify(lowPrioElectionTriggered).set(true);
  }
}
