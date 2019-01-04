/*
 * Copyright 2019 Square Inc.
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
package com.squareup.workflow.rx2

import com.squareup.workflow.EnterState
import com.squareup.workflow.FinishWith
import com.squareup.workflow.Reaction
import com.squareup.workflow.Workflow
import com.squareup.workflow.WorkflowPool
import com.squareup.workflow.workflow
import io.reactivex.Single
import kotlinx.coroutines.experimental.CoroutineName
import kotlinx.coroutines.experimental.rx2.await
import kotlin.coroutines.experimental.EmptyCoroutineContext
import com.squareup.workflow.discreteStateWorkflow as coreDiscreteStateWorkflow

/**
 * An Rx2 adapter for [com.squareup.workflow.StateTransitionFunction], allowing implementors to
 * ignore that interface's reliance on [kotlin.coroutines].
 *
 * A factory for [Workflow]s implemented as state machines that:
 *
 *  - move through states of `S`
 *  - accept events of type `E`
 *  - emit a result value of type `O` when completed
 *
 * When a new workflow is created with [discreteStateWorkflow], each consecutive state will be
 * passed to this function, along with an [EventChannel] that can be used to accept events, and a
 * [WorkflowPool] that can be used to delegate work to nested workflows.
 *
 * This function returns a [Single] (read "Future") that eventually emits a [Reaction]
 * indicating what to do next, one of:
 *
 *   - [EnterState][com.squareup.workflow.EnterState]:
 *     Emit [EnterState.state][[com.squareup.workflow.EnterState.state] from [Workflow.state], and
 *     pass it as the next state value of a new call to [onReact].
 *
 *   - [FinishWith][com.squareup.workflow.FinishWith]: Fire [Workflow.result] with
 *     [FinishWith.result][com.squareup.workflow.FinishWith.result], and terminate the workflow.
 *
 * ## Handling Inputs
 *
 * ### Events
 *
 * To handle events received from [Workflow.sendEvent], call [`events.select`][EventChannel.select]
 * from within your function:
 *
 *    fun nextState(
 *      state: MyState,
 *      events: EventChannel<MyEvent>,
 *      workflows: WorkflowPool
 *    ): Single<Reaction<MyState, MyResult>> = when(state) {
 *      FooOrDone -> events.select(state) {
 *        onEvent<Foo> { handleFoo() }
 *        onEvent<Done> { FinishWith(it.result) }
 *      }
 *
 *      FooOrBarState -> events.select(state) {
 *        onEvent<Foo> { handleFoo() }
 *        onEvent<Bar> { EnterState(FooOrDone) }
 *      }
 *    }
 *
 * ### `Single`s
 *
 * This function is not limited to using the given [EventChannel] to calculate its next state. For
 * example, a service call might be handled this way, mapping a [Single] generated by Retrofit to
 * the appropriate [Reaction].
 *
 *    fun nextState(
 *      state: MyState,
 *      events: EventChannel<MyEvent>,
 *      workflows: WorkflowPool
 *    ): Single<Reaction<MyState, MyResult>> = when(state) {
 *      WaitingForStatusResponse -> statusService.update().map { response ->
 *        if (response.success) EnterState(ShowingStatus(response))
 *        else EnterState(ShowingFailure(response)
 *      }
 *
 *      // ...
 *    }
 *
 * ### Combining Events and `Single`s
 *
 * If you need to mix such command-like [Single]s with workflow events, make a
 * [Worker][com.squareup.workflow.Worker] for your `Single` using [singleWorker].
 * [EventChannel] offers [onWorkerResult][EventSelectBuilder.onWorkerResult] in addition to
 * `onEvent`. Remember to call [WorkflowPool.abandonWorkflow] if you leave while the `Worker` is
 * still running!
 *
 *    private val updateWorker = singleWorker { statusService.update }
 *
 *    fun nextState(
 *      state: MyState,
 *      events: EventChannel<MyEvent>,
 *      workflows: WorkflowPool
 *    ): Single<Reaction<MyState, MyResult>> = when(state) {
 *      WaitingForStatusResponse -> events.select {
 *
 *        workflows.onWorkerResult(updateWorker) { response ->
 *          if (response.success) EnterState(ShowingStatus(response))
 *          else EnterState(ShowingFailure(response)
 *        }
 *
 *        onEvent<Cancel> {
 *          workflows.abandonWorker(updateWorker)
 *          EnterState(ShowingFailure(Canceled())
 *        }
 *      }
 *
 *      // ...
 *    }
 *
 * ### External Hot `Observable`s
 *
 * To monitor external hot observables, which might fire at any time, subscribe to them in the
 * [launch][WorkflowPool.Launcher.launch] method, and map their values to events passed to
 * [Workflow.sendEvent]. Use [Workflow.toCompletable][com.squareup.workflow.rx2.toCompletable] to
 * tear down those subscriptions when the workflow ends.
 *
 *     override fun launch(
 *       initialState: MyState,
 *       workflows: WorkflowPool
 *     ) : Workflow<MyState, MyEvent, MyResult> {
 *       val workflow = workflows.discreetStateWorkflow(initialState, workflows, ::nextState)
 *       val subs = CompositeSubscription()
 *       subs += connectivityMonitor.connectivity.subscribe {
 *         workflow.sendEvent(ConnectivityUpdate(it))
 *       }
 *       subs += workflow.toCompletable().subscribe { subs.clear() }
 *
 *       return workflow
 *     }
 *
 * ## Nesting Workflows
 *
 * To define a state that delegates to a nested workflow, have the `S` subtype that represents it
 * implement [com.squareup.workflow.Delegating]. Use
 * [onNextDelegateReaction][EventSelectBuilder.onWorkflowUpdate] when entering that state to
 * drive the nested workflow and react to its result.
 *
 * For example, in the simplest case, where the parent workflow accepts no events of its own while
 * the delegate is running, the delegating state type would look like this:
 *
 *     data class RunningNested(
 *       // Initial state of the nested workflow, and updated as the
 *       override val delegateState: NestedState = NestedState.start()
 *     ) : MyState(), Delegating<NestedState, NestedEvent, NestedResult> {
 *       override val id = NestedLauncher::class.makeWorkflowId()
 *     }
 *
 * You'd register a `NestedLauncher` instance with the [WorkflowPool] passed to your
 * [launch][WorkflowPool.Launcher] implementation:
 *
 *    class MyLauncher(
 *      private val nestedLauncher : NestedLauncher
 *    ) {
 *      override fun launch(
 *        initialState: MyState,
 *        workflows: WorkflowPool
 *      ) : Workflow<MyState, MyEvent, MyResult> {
 *        workflows.register(nestedReactor)
 *        return workflows.discreetStateWorkflow(initialState, workflows, ::nextState)
 *      }
 *
 * and in your `nextState` method, use
 * [onNextDelegateReaction][EventSelectBuilder.onWorkflowUpdate] to wait for the nested
 * workflow to do its job:
 *
 *    is Delegating -> events.select {
 *      workflows.onNextDelegateReaction(state) {
 *        when (it) {
 *          is EnterState -> EnterState(state.copy(delegateState = it.state))
 *          is FinishWith -> when (it.result) {
 *            is DoSomething -> EnterState(DoingSomething)
 *            is DoSomethingElse -> EnterState(DoingSomethingElse)
 *          }
 *        }
 *      }
 *    }
 *
 * If you need to handle other events while the workflow is running, remember to call
 * [WorkflowPool.abandonWorkflow] if you leave while the nested workflow is still running!
 *
 *    is Delegating -> events.select {
 *      workflows.onNextDelegateReaction(state) {
 *        when (it) {
 *          is EnterState -> EnterState(state.copy(delegateState = it.state))
 *          if FinishWith -> when (it.result) {
 *            is DoSomething -> EnterState(DoingSomething)
 *            is DoSomethingElse -> EnterState(DoingSomethingElse)
 *          }
 *        }
 *      }
 *
 *      onEvent<Cancel> {
 *        workflows.abandonDelegate(state.id)
 *        EnterState(NeverMind)
 *      }
 *    }
 *
 * To accept events for nested workflows, e.g. to drive a UI, define
 * [com.squareup.workflow.Renderer]s for both `S` and each of its
 * [Delegating][com.squareup.workflow.Delegating] subtypes. [WorkflowPool.input] can be used by
 * renderers to route events to any running workflow.
 */
typealias StateTransitionFunction<S, E, O> =
    (state: S, events: EventChannel<E>, workflows: WorkflowPool) -> Single<out Reaction<S, O>>

/**
 * Implements a [Workflow] using a [StateTransitionFunction].
 * Use this to implement [WorkflowPool.Launcher.launch].
 *
 * The react loop runs inside a [workflow coroutine][workflow].
 *
 * The [initial state][initialState], and then each [subsequent state][EnterState], are all sent
 * to the builder's state observable.
 *
 * _Note:_
 * If the state transition function immediately returns [FinishWith], the last state may not be
 * emitted. See [com.squareup.workflow.discreteStateWorkflow].
 *
 * ## Naming
 *
 * To help with debugging, this function accepts a [name]. This name will
 * be used in exceptions thrown from the workflow. Additionally, if coroutine debugging is enabled,
 * the name will show up in stack traces and thread names.
 */
fun <S : Any, E : Any, O : Any> WorkflowPool.discreteStateWorkflow(
  initialState: S,
  name: String? = null,
  nextState: StateTransitionFunction<S, E, O>
): Workflow<S, E, O> = coreDiscreteStateWorkflow(
    initialState = initialState,
    context = name?.let(::CoroutineName) ?: EmptyCoroutineContext
) { s, e, w -> nextState(s, e.asEventChannel(), w).await() }
