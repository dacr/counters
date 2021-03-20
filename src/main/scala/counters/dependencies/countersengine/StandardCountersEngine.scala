/*
 * Copyright 2021 David Crosson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package counters.dependencies.countersengine

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.util.Timeout
import akka.actor.typed.scaladsl.AskPattern._
import counters.ServiceConfig
import counters.model.{Counter, CounterCreateInputs, CounterState, CountersGroup, CountersGroupCreateInputs, OperationOrigin}
import org.slf4j.LoggerFactory

import java.time.Instant
import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration._


trait CountersStorage {
  def groupsList(): Iterator[CountersGroup]

  def groupsCounters(groupId: UUID): Iterator[Counter]

  def groupGet(groupId: UUID): Option[CountersGroup]

  def counterGet(groupId: UUID, counterId: UUID): Option[Counter]
}

class NopCounterStorage(config: ServiceConfig) extends CountersStorage {
  override def groupsList(): Iterator[CountersGroup] = Iterator.empty

  override def groupsCounters(groupId: UUID): Iterator[Counter] = Iterator.empty

  override def groupGet(groupId: UUID): Option[CountersGroup] = None

  override def counterGet(groupId: UUID, counterId: UUID): Option[Counter] = None
}


class BasicCountersFileSystemStorage(config: ServiceConfig) extends CountersStorage {
  override def groupsList(): Iterator[CountersGroup] = {
    ???
  }

  override def groupsCounters(groupId: UUID): Iterator[Counter] = {
    ???
  }

  override def groupGet(groupId: UUID): Option[CountersGroup] = {
    ???
  }

  override def counterGet(groupId: UUID, counterId: UUID): Option[Counter] = {
    ???
  }
}


object StandardCountersEngine {
  def apply(config: ServiceConfig): StandardCountersEngine = {
    //val storage = new BasicCountersFileSystemStorage(config)
    val storage = new NopCounterStorage(config)
    new StandardCountersEngine(config, storage)
  }
}

class StandardCountersEngine(config: ServiceConfig, storage: CountersStorage) extends CountersEngine {
  val logger = LoggerFactory.getLogger(getClass)

  // =================================================================================
  sealed trait CounterCommand

  case class CounterIncrementCommand(
    operationOrigin: Option[OperationOrigin],
    replyTo: ActorRef[Option[CounterState]]
  ) extends CounterCommand

  case class CounterGetCommand(
    replyTo: ActorRef[Option[Counter]]
  ) extends CounterCommand

  case class CounterStateCommand(
    replyTo: ActorRef[Option[CounterState]]
  ) extends CounterCommand

  def counterBehavior(groupActor: ActorRef[GroupCommand], currentState: CounterState): Behavior[CounterCommand] =
    Behaviors.receiveMessage {
      // ---------------------------------------------------------------------
      case CounterIncrementCommand(operationOrigin, replyTo) =>
        val newCount = currentState.count + 1
        val newLastUpdated = Instant.now()
        val newLastOrigin = operationOrigin
        val newState = CounterState(currentState.counter, newCount, newLastUpdated, newLastOrigin)
        replyTo ! Some(newState)
        groupActor ! GroupCounterUpdatedStateCommand(newState)
        counterBehavior(groupActor, newState)
      // ---------------------------------------------------------------------
      case CounterStateCommand(replyTo) =>
        replyTo ! Some(currentState)
        Behaviors.same
      // ---------------------------------------------------------------------
      case CounterGetCommand(replyTo) =>
        replyTo ! Some(currentState.counter)
        Behaviors.same
    }

  // =================================================================================
  sealed trait GroupCommand

  case class GroupCounterIncrementCommand(
    counterId: UUID,
    operationOrigin: Option[OperationOrigin],
    replyTo: ActorRef[Option[CounterState]]) extends GroupCommand

  case class GroupCounterUpdatedStateCommand(
    state: CounterState
  ) extends GroupCommand

  case class GroupCounterStateGetCommand(
    counterId: UUID,
    replyTo:ActorRef[Option[CounterState]]
  ) extends GroupCommand

  case class GroupCounterGetCommand(
    counterId: UUID,
    replyTo:ActorRef[Option[Counter]]
  ) extends GroupCommand

  case class GroupCounterCreateCommand(
    inputs: CounterCreateInputs,
    replyTo:ActorRef[Option[Counter]]
  ) extends GroupCommand

  def groupBehavior(group: CountersGroup, counters: Map[UUID, ActorRef[CounterCommand]], states: Map[UUID, CounterState]): Behavior[GroupCommand] = Behaviors.setup { context =>
    Behaviors.receiveMessage {
      // ---------------------------------------------------------------------
      case GroupCounterCreateCommand(inputs, replyTo) =>
        val groupId = group.id
        val counterId = UUID.randomUUID()
        val counter = Counter(
          id = counterId,
          groupId = group.id,
          name = inputs.name,
          description = inputs.description,
          origin = inputs.origin,
          redirect = inputs.redirect
        )
        val initialState = CounterState(
          counter = counter,
          count = 0L,
          lastOrigin = inputs.origin,
          lastUpdated = Instant.now
        )
        val counterActorName = s"group-$groupId-counter-$counterId"
        val counterRef = context.spawn(counterBehavior(context.self, initialState), counterActorName)
        val newCounters = counters + (counterId -> counterRef)
        val newStates = states + (counterId -> initialState)
        replyTo ! Some(counter)
        groupBehavior(group, newCounters, newStates)
      // ---------------------------------------------------------------------
      case GroupCounterUpdatedStateCommand(updatedState) =>
        val updatedStates = states + (updatedState.counter.id -> updatedState)
        groupBehavior(group, counters, updatedStates)
      // ---------------------------------------------------------------------
      case GroupCounterIncrementCommand(counterId, operationOrigin, replyTo) =>
        counters.get(counterId) match {
          case None => replyTo ! None
          case Some(counterActor) => counterActor ! CounterIncrementCommand(operationOrigin, replyTo)
        }
        Behaviors.same
      // ---------------------------------------------------------------------
      case GroupCounterStateGetCommand(counterId, replyTo) =>
        counters.get(counterId) match {
          case None => replyTo ! None
          case Some(counterActor) => counterActor ! CounterStateCommand(replyTo)
        }
        Behaviors.same
      // ---------------------------------------------------------------------
      case GroupCounterGetCommand(counterId, replyTo) =>
        counters.get(counterId) match {
          case None => replyTo ! None
          case Some(counterActor) => counterActor ! CounterGetCommand(replyTo)
        }
        Behaviors.same
    }
  }

  // =================================================================================

  sealed trait GuardianCommand

  object GuardianStopCommand extends GuardianCommand

  object GuardianSetupCommand extends GuardianCommand

  case class GuardianGroupCreateCommand(
    inputs: CountersGroupCreateInputs,
    replyTo: ActorRef[CountersGroup]) extends GuardianCommand

  case class GuardianGroupCountersCommand(
    groupId: UUID,
    replyTo: ActorRef[Option[List[Counter]]]) extends GuardianCommand

  case class GuardianGroupStatesCommand(
    groupId: UUID,
    replyTo: ActorRef[Option[List[CounterState]]]) extends GuardianCommand

  case class GuardianCounterCreateCommand(
    groupId: UUID,
    inputs: CounterCreateInputs,
    replyTo: ActorRef[Option[Counter]]) extends GuardianCommand

  case class GuardianCounterGetCommand(
    groupId: UUID,
    counterId: UUID,
    replyTo: ActorRef[Option[Counter]]) extends GuardianCommand

  case class GuardianCounterIncrementCommand(
    groupId: UUID,
    counterId: UUID,
    origin: Option[OperationOrigin],
    replyTo: ActorRef[Option[CounterState]]) extends GuardianCommand

  case class GuardianStateGetCommand(
    groupId: UUID,
    counterId: UUID,
    value: ActorRef[Option[CounterState]]) extends GuardianCommand


  def guardianRunningBehavior(groups: Map[UUID, ActorRef[GroupCommand]]): Behavior[GuardianCommand] = Behaviors.setup { context =>
    Behaviors.receiveMessage {
      // ---------------------------------------------------------------------
      case GuardianSetupCommand => // Ignore already done
        Behaviors.same
      // ---------------------------------------------------------------------
      case GuardianStopCommand =>
        Behaviors.stopped
      // ---------------------------------------------------------------------
      case GuardianGroupCreateCommand(inputs, replyTo) =>
        val groupId = UUID.randomUUID()
        val group = CountersGroup(id=groupId, name=inputs.name, description = inputs.description, origin = inputs.origin)
        val groupActorName = s"group-$groupId"
        val groupRef = context.spawn(groupBehavior(group, Map.empty, Map.empty), groupActorName)
        val updatedGroups = groups + (groupId -> groupRef)
        replyTo ! group
        guardianRunningBehavior(updatedGroups)
      // ---------------------------------------------------------------------
      case GuardianGroupCountersCommand(groupId, replyTo) =>
        Behaviors.same
      // ---------------------------------------------------------------------
      case GuardianGroupStatesCommand(groupId, replyTo) =>
        Behaviors.same
      // ---------------------------------------------------------------------
      case GuardianCounterCreateCommand(groupId, inputs, replyTo) =>
        groups.get(groupId) match {
          case None => replyTo ! None
          case Some(groupRef) => groupRef ! GroupCounterCreateCommand(inputs, replyTo)
        }
        Behaviors.same
      // ---------------------------------------------------------------------
      case GuardianCounterGetCommand(groupId, counterId, replyTo) =>
        groups.get(groupId) match {
          case None => replyTo ! None
          case Some(groupRef) => groupRef ! GroupCounterGetCommand(counterId, replyTo)
        }
        Behaviors.same
      // ---------------------------------------------------------------------
      case GuardianCounterIncrementCommand(groupId, counterId, origin, replyTo) =>
        groups.get(groupId) match {
          case None => replyTo ! None
          case Some(groupRef) => groupRef ! GroupCounterIncrementCommand(counterId, origin, replyTo)
        }
        Behaviors.same
      // ---------------------------------------------------------------------
      case GuardianStateGetCommand(groupId, counterId, replyTo) =>
        groups.get(groupId) match {
          case None => replyTo ! None
          case Some(groupRef) => groupRef ! GroupCounterStateGetCommand(counterId, replyTo)
        }
        Behaviors.same
    }
  }

  def guardianBehavior(): Behavior[GuardianCommand] = {
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case GuardianStopCommand =>
          Behaviors.stopped
        case GuardianSetupCommand =>
          val groups = Map.empty[UUID, ActorRef[GroupCommand]]
          guardianRunningBehavior(groups)
        case x => // Any other messages are ignored until the setup is received
          logger.warn(s"Can't process any standard messages until setup is done, received $x")
          Behaviors.same
      }
    }
  }

  // =================================================================================

  implicit val countersSystem: ActorSystem[GuardianCommand] = ActorSystem(guardianBehavior(), "StandardCountersEngineActorSystem")
  implicit val ec = countersSystem.executionContext
  implicit val timeout: Timeout = 3.seconds

  countersSystem ! GuardianSetupCommand

  // =================================================================================

  override def groupCreate(inputs: CountersGroupCreateInputs): Future[CountersGroup] = {
    countersSystem.ask(GuardianGroupCreateCommand(inputs, _))
  }

  override def groupCounters(groupId: UUID): Future[Option[List[Counter]]] = {
    countersSystem.ask(GuardianGroupCountersCommand(groupId, _))
  }

  override def groupStates(groupId: UUID): Future[Option[List[CounterState]]] = {
    countersSystem.ask(GuardianGroupStatesCommand(groupId, _))
  }

  override def counterCreate(groupId:UUID, inputs: CounterCreateInputs): Future[Option[Counter]] = {
    countersSystem.ask(GuardianCounterCreateCommand(groupId, inputs, _))
  }

  override def counterIncrement(groupId: UUID, counterId: UUID, origin: Option[OperationOrigin]): Future[Option[CounterState]] = {
    countersSystem.ask(GuardianCounterIncrementCommand(groupId, counterId, origin, _))
  }

  override def counterGet(groupId: UUID, counterId: UUID): Future[Option[Counter]] = {
    countersSystem.ask(GuardianCounterGetCommand(groupId, counterId, _))
  }

  override def stateGet(groupId: UUID, counterId: UUID): Future[Option[CounterState]] = {
    countersSystem.ask(GuardianStateGetCommand(groupId, counterId, _))
  }

  override def shutdown(): Future[Boolean] = {
    countersSystem ! GuardianStopCommand
    countersSystem.whenTerminated.map(_ => true)
  }
}
