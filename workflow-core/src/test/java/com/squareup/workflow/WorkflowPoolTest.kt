/*
 * Copyright 2017 Square Inc.
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
package com.squareup.workflow

import com.squareup.workflow.WorkflowPool.Launcher
import kotlinx.coroutines.experimental.CancellationException
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("DeferredResultUnused", "MemberVisibilityCanBePrivate")
class WorkflowPoolTest {
  companion object {
    const val NEW = "*NEW*"
    const val STOP = "*STOP*"
  }

  val eventsSent = mutableListOf<String>()

  var abandonCount = 0
  var launchCount = 0

  inner class MyLauncher : Launcher<String, String, String> {
    override fun launch(
      initialState: String,
      workflows: WorkflowPool
    ): Workflow<String, String, String> {
      launchCount++
      val workflow = workflows.discreteStateWorkflow(initialState, nextState = onReact)
      workflow.invokeOnCompletion {
        if (it is CancellationException) {
          abandonCount++
        }
      }
      return workflow
    }

    private val onReact: StateTransitionFunction<String, String, String> = { state, events, _ ->
      events.receive()
          .let {
            eventsSent += it
            if (it == STOP) FinishWith(state) else EnterState(it)
          }
    }
  }

  val myLauncher = MyLauncher()
  val pool = WorkflowPool().apply { register(myLauncher) }

  private fun handle(
    state: String = "",
    name: String = ""
  ) = RunWorkflow(myLauncher.workflowType.makeWorkflowId(name), state)

  @Test fun `meta test myReactor reports states and result`() {
    val workflow = myLauncher.launch(NEW, pool)
    val stateSub = workflow.openSubscriptionToState()

    assertEquals(NEW, stateSub.poll())
    workflow.sendEvent("able")
    assertEquals("able", stateSub.poll())
    workflow.sendEvent("baker")
    assertEquals("baker", stateSub.poll())
    workflow.sendEvent("charlie")
    assertEquals("charlie", stateSub.poll())
    workflow.sendEvent(STOP)
    assertTrue(stateSub.isClosedForReceive)

    assertEquals("charlie", workflow.getCompleted())
  }

  @Test fun `meta test myReactor abandons and state completes`() {
    val workflow = myLauncher.launch(NEW, pool)
    val abandoned = AtomicBoolean(false)

    workflow.invokeOnCompletion { abandoned.set(true) }

    assertFalse(abandoned.get())
    workflow.cancel()
    assertTrue(abandoned.get())
  }

  @Test fun `no eager launch`() {
    assertEquals(0, launchCount)
  }

  @Test fun `waits for state after current`() {
    val handle = handle(NEW)
    val nestedStateSub = pool.workflowUpdate(handle)
    assertFalse(nestedStateSub.isCompleted)

    val input = pool.input(handle)
    input.sendEvent(NEW)
    input.sendEvent(NEW)
    input.sendEvent(NEW)
    assertFalse(nestedStateSub.isCompleted)

    input.sendEvent("fnord")
    assertEquals(handle("fnord"), nestedStateSub.getCompleted())
  }

  @Test fun `reports result`() {
    val firstState = handle(NEW)

    // We don't actually care about the reaction, just want the workflow to start.
    pool.workflowUpdate(firstState)

    // Advance the state a bit.
    val input = pool.input(firstState)
    input.sendEvent("able")
    input.sendEvent("baker")
    input.sendEvent("charlie")

    // Check that we get the result we expect after the above events.
    val resultSub = pool.workflowUpdate(handle("charlie"))
    assertFalse(resultSub.isCompleted)

    input.sendEvent(STOP)
    assertEquals(FinishedWorkflow(firstState.id, "charlie"), resultSub.getCompleted())
  }

  @Test fun `reports immediate result`() {
    val handle = handle(NEW)
    val resultSub = pool.workflowUpdate(handle)
    assertFalse(resultSub.isCompleted)

    val input = pool.input(handle)
    input.sendEvent(STOP)
    assertEquals(FinishedWorkflow(handle.id, NEW), resultSub.getCompleted())
  }

  @Test fun `inits once per next state`() {
    pool.workflowUpdate(handle())
    assertEquals(1, launchCount)

    pool.workflowUpdate(handle())
    assertEquals(1, launchCount)

    pool.workflowUpdate(handle())
    assertEquals(1, launchCount)
  }

  @Test fun `inits once per result`() {
    pool.workflowUpdate(handle())
    assertEquals(1, launchCount)

    pool.workflowUpdate(handle())
    assertEquals(1, launchCount)

    pool.workflowUpdate(handle())
    assertEquals(1, launchCount)
  }

  @Test fun `routes events`() {
    pool.workflowUpdate(handle())
    val input = pool.input(handle())

    input.sendEvent("able")
    input.sendEvent("baker")
    input.sendEvent("charlie")

    assertEquals(listOf("able", "baker", "charlie"), eventsSent)
  }

  @Test fun `drops late events`() {
    val input = pool.input(handle())
    pool.workflowUpdate(handle())

    input.sendEvent("able")
    input.sendEvent("baker")
    // End the workflow.
    input.sendEvent(STOP)
    input.sendEvent("charlie")
    assertEquals(listOf("able", "baker", STOP), eventsSent)
  }

  @Test fun `drops early events`() {
    val input = pool.input(handle())
    input.sendEvent("able")
    pool.workflowUpdate(handle())
    input.sendEvent("baker")

    assertEquals(listOf("baker"), eventsSent)
  }

  @Test fun `workflow isnt dropped until result reported`() {
    pool.workflowUpdate(handle())
    val input = pool.input(handle())

    input.sendEvent("able")
    input.sendEvent("baker")
    // End the workflow.
    input.sendEvent(STOP)

    pool.workflowUpdate(handle())

    assertEquals(listOf("able", "baker", STOP), eventsSent)
  }

  @Test fun `resumes routing events`() {
    pool.workflowUpdate(handle())
    val input = pool.input(handle())

    input.sendEvent("able")
    input.sendEvent("baker")
    // End the workflow.
    input.sendEvent(STOP)
    // Consume the completed workflow.
    pool.workflowUpdate(handle())

    input.sendEvent("charlie")
    input.sendEvent("delta")
    pool.workflowUpdate(handle())

    input.sendEvent("echo")
    input.sendEvent("foxtrot")

    assertEquals(listOf("able", "baker", STOP, "echo", "foxtrot"), eventsSent)
  }

  @Test fun `abandons only once`() {
    assertEquals(0, abandonCount)
    pool.workflowUpdate(handle(NEW))
    pool.abandonWorkflow(handle().id)
    pool.abandonWorkflow(handle().id)
    pool.abandonWorkflow(handle().id)
    assertEquals(1, abandonCount)
  }

  @Test fun `abandon cancels deferred`() {
    val alreadyInNewState = handle(NEW)
    val id = alreadyInNewState.id

    val stateSub = pool.workflowUpdate(alreadyInNewState)

    pool.abandonWorkflow(id)

    assertTrue(stateSub.isCompletedExceptionally)
    assertTrue(stateSub.getCompletionExceptionOrNull() is CancellationException)
  }
}
