/*
 * Copyright 2020-2022 David Crosson
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

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.util.Timeout
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import counters.ServiceConfig
import counters.model.{Counter, CounterCreateInputs, CounterState, CountersGroup, CountersGroupCreateInputs, OperationOrigin, ServiceStats}
import counters.tools.JsonImplicits
import org.apache.commons.io.FileUtils
import org.json4s.{Extraction, JValue}
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization.write
import org.slf4j.LoggerFactory

import java.io.{File, FileFilter}
import java.time.Instant
import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try


trait CountersStorage {
  def groupsList(): Iterable[CountersGroup]

  def groupCounters(groupId: UUID): Iterable[Counter]

  def groupGet(groupId: UUID): Option[CountersGroup]

  def counterGet(groupId: UUID, counterId: UUID): Option[Counter]

  def stateGet(groupId: UUID, counterId: UUID): Option[CounterState]

  def groupSave(group: CountersGroup): Boolean

  def counterSave(counter: Counter): Boolean

  def stateSave(state: CounterState): Boolean
}

class NopCounterStorage(config: ServiceConfig) extends CountersStorage {
  override def groupsList(): Iterable[CountersGroup] = Iterable.empty

  override def groupCounters(groupId: UUID): Iterable[Counter] = Iterable.empty

  override def groupGet(groupId: UUID): Option[CountersGroup] = None

  override def counterGet(groupId: UUID, counterId: UUID): Option[Counter] = None

  override def stateGet(groupId: UUID, counterId: UUID): Option[CounterState] = None

  override def groupSave(group: CountersGroup): Boolean = true

  override def counterSave(counter: Counter): Boolean = true

  override def stateSave(state: CounterState): Boolean = true
}


class BasicCountersFileSystemStorage(config: ServiceConfig) extends CountersStorage with JsonImplicits {
  private val logger = LoggerFactory.getLogger(getClass)
  private val storeConfig = config.counters.behavior.fileSystemStorage
  private val storeBaseDirectory = {
    val path = new File(storeConfig.path)
    if (!path.exists()) {
      logger.info(s"Creating base directory $path")
      if (path.mkdirs()) logger.info(s"base directory $path created")
      else {
        val message = s"Unable to create base directory $path"
        logger.error(message)
        throw new RuntimeException(message)
      }
    }
    logger.info(s"Using $path to store counters data")
    path
  }

  private val directoryUUIDNamedFilter = new FileFilter {
    override def accept(file: File): Boolean = {
      file.isDirectory && Try(UUID.fromString(file.getName)).toOption.isDefined
    }
  }

  def groupsDirectories(): Option[Array[File]] = {
    Option(storeBaseDirectory.listFiles(directoryUUIDNamedFilter))
  }

  def groupsUUIDs(): Iterable[UUID] = {
    groupsDirectories()
      .getOrElse(Array.empty)
      .map(_.getName)
      .flatMap(name => Try(UUID.fromString(name)).toOption)
  }

  def groupDirectory(groupId: UUID): File = {
    new File(storeBaseDirectory, groupId.toString)
  }

  def countersDirectories(groupId: UUID): Option[Array[File]] = {
    Option(groupDirectory(groupId).listFiles(directoryUUIDNamedFilter))
  }

  def counterDirectory(groupId: UUID, counterId: UUID): File = {
    new File(groupDirectory(groupId), counterId.toString)
  }

  def countersUUIDs(groupId: UUID): Iterable[UUID] = {
    countersDirectories(groupId)
      .getOrElse(Array.empty)
      .map(_.getName)
      .flatMap(name => Try(UUID.fromString(name)).toOption)
  }


  def jsonRead(file: File): JValue = {
    parse(FileUtils.readFileToString(file, "UTF-8"))
  }

  def jsonWrite(file: File, value: JValue) = {
    val tmpFile = new File(file.getParent, file.getName + ".tmp")
    FileUtils.write(tmpFile, write(value), "UTF-8")
    file.delete()
    tmpFile.renameTo(file)
  }

  def groupFile(groupId: UUID): File = {
    new File(groupDirectory(groupId), "group.json")
  }

  def counterFile(groupId: UUID, counterId: UUID): File = {
    new File(counterDirectory(groupId, counterId), "counter.json")
  }

  def stateFile(groupId: UUID, counterId: UUID): File = {
    new File(counterDirectory(groupId, counterId), "state.json")
  }

  def historyFile(groupId: UUID, counterId: UUID): File = {
    new File(counterDirectory(groupId, counterId), "history.json")
  }


  override def groupsList(): Iterable[CountersGroup] = {
    groupsUUIDs()
      .map(groupFile)
      .map(jsonRead)
      .flatMap(_.extractOpt[CountersGroup])
  }

  override def groupCounters(groupId: UUID): Iterable[Counter] = {
    countersUUIDs(groupId)
      .map(counterId => counterFile(groupId, counterId))
      .map(jsonRead)
      .flatMap(_.extractOpt[Counter])
  }

  override def groupGet(groupId: UUID): Option[CountersGroup] = {
    jsonRead(groupFile(groupId)).extractOpt[CountersGroup]
  }

  override def counterGet(groupId: UUID, counterId: UUID): Option[Counter] = {
    jsonRead(counterFile(groupId, counterId)).extractOpt[Counter]
  }

  override def stateGet(groupId: UUID, counterId: UUID): Option[CounterState] = {
    jsonRead(stateFile(groupId, counterId)).extractOpt[CounterState]
  }

  override def groupSave(group: CountersGroup): Boolean = {
    val dest = groupFile(group.id)
    if (!dest.getParentFile.exists()) dest.getParentFile.mkdirs()
    jsonWrite(dest, Extraction.decompose(group))
  }

  override def counterSave(counter: Counter): Boolean = {
    val dest = counterFile(counter.groupId, counter.id)
    if (!dest.getParentFile.exists()) dest.getParentFile.mkdirs()
    jsonWrite(dest, Extraction.decompose(counter))
  }

  override def stateSave(state: CounterState): Boolean = {
    val dest = stateFile(state.counter.groupId, state.counter.id)
    if (!dest.getParentFile.exists()) dest.getParentFile.mkdirs()
    jsonWrite(dest, Extraction.decompose(state))
  }
}


object StandardCountersEngine {
  def apply(config: ServiceConfig): StandardCountersEngine = {
    val storage = new BasicCountersFileSystemStorage(config)
    //val storage = new NopCounterStorage(config)
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

  def counterBehavior(group:CountersGroup, groupActor: ActorRef[GroupCommand], currentState: CounterState): Behavior[CounterCommand] =
    Behaviors.receiveMessage {
      // ---------------------------------------------------------------------
      case CounterIncrementCommand(operationOrigin, replyTo) =>
        val newStateId = UUID.randomUUID()
        val newCount = currentState.count + 1
        val newLastUpdated = Instant.now()
        val newLastOrigin = operationOrigin
        val newState = CounterState(newStateId, group, currentState.counter, newCount, newLastUpdated, newLastOrigin)
        storage.stateSave(newState)
        replyTo ! Some(newState)
        groupActor ! GroupCounterUpdatedStateCommand(newState)
        counterBehavior(group, groupActor, newState)
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

  object GroupRestoreCommand extends GroupCommand

  case class GroupCounterIncrementCommand(
    counterId: UUID,
    operationOrigin: Option[OperationOrigin],
    replyTo: ActorRef[Option[CounterState]]) extends GroupCommand

  case class GroupCounterUpdatedStateCommand(
    state: CounterState
  ) extends GroupCommand

  case class GroupCounterStateGetCommand(
    counterId: UUID,
    replyTo: ActorRef[Option[CounterState]]
  ) extends GroupCommand

  case class GroupCounterGetCommand(
    counterId: UUID,
    replyTo: ActorRef[Option[Counter]]
  ) extends GroupCommand

  case class GroupCounterCreateCommand(
    inputs: CounterCreateInputs,
    replyTo: ActorRef[Option[Counter]]
  ) extends GroupCommand

  def groupBehavior(counterKeeperRef: ActorRef[GuardianCounterAdded], group: CountersGroup, counters: Map[UUID, ActorRef[CounterCommand]], states: Map[UUID, CounterState]): Behavior[GroupCommand] = Behaviors.setup { context =>
    Behaviors.receiveMessage {
      // ---------------------------------------------------------------------
      case GroupRestoreCommand =>
        val storedCounters = storage.groupCounters(group.id)
        val restoredStates = storedCounters.map{counter =>
          counter.id -> storage.stateGet(counter.groupId, counter.id).get  // TODO dangerous .get !!
        }.toMap
        val restoredCounters = storedCounters.map{counter =>
          val counterActorName = s"group-${counter.groupId}-counter-${counter.id}"
          val counterState = restoredStates.get(counter.id).get // TODO dangerous .get !!
          val counterRef = context.spawn(counterBehavior(group, context.self, counterState), counterActorName)
          counter.id -> counterRef
        }.toMap
        counterKeeperRef ! GuardianCounterAdded(storedCounters.size)
        groupBehavior(counterKeeperRef, group, counters ++ restoredCounters, states ++ restoredStates)
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
          id = UUID.randomUUID(),
          group = group,
          counter = counter,
          count = 0L,
          lastOrigin = inputs.origin,
          lastUpdated = Instant.now
        )
        val counterActorName = s"group-$groupId-counter-$counterId"
        val counterRef = context.spawn(counterBehavior(group, context.self, initialState), counterActorName)
        val newCounters = counters + (counterId -> counterRef)
        val newStates = states + (counterId -> initialState)
        storage.counterSave(counter)
        storage.stateSave(initialState)
        replyTo ! Some(counter)
        counterKeeperRef ! GuardianCounterAdded(1)
        groupBehavior(counterKeeperRef, group, newCounters, newStates)
      // ---------------------------------------------------------------------
      case GroupCounterUpdatedStateCommand(updatedState) =>
        val updatedStates = states + (updatedState.counter.id -> updatedState)
        groupBehavior(counterKeeperRef, group, counters, updatedStates)
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

  case class GuardianCounterAdded(counterAddedCount: Int) extends GuardianCommand

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

  case class GuardianServiceStats(
    replyTo: ActorRef[ServiceStats]) extends GuardianCommand

  def guardianRunningBehavior(counterCount: Int, groups: Map[UUID, ActorRef[GroupCommand]]): Behavior[GuardianCommand] = Behaviors.setup { context =>
    Behaviors.receiveMessage {
      // ---------------------------------------------------------------------
      case GuardianSetupCommand => // Ignore already done
        Behaviors.same
      // ---------------------------------------------------------------------
      case GuardianStopCommand =>
        Behaviors.stopped
      // ---------------------------------------------------------------------
      case GuardianCounterAdded(counterAddedCount) =>
        guardianRunningBehavior(counterCount + counterAddedCount, groups)
      // ---------------------------------------------------------------------
      case GuardianGroupCreateCommand(inputs, replyTo) =>
        val groupId = UUID.randomUUID()
        val group = CountersGroup(id = groupId, name = inputs.name, description = inputs.description, origin = inputs.origin)
        val groupActorName = s"group-$groupId"
        val groupRef = context.spawn(groupBehavior(context.self, group, Map.empty, Map.empty), groupActorName)
        val updatedGroups = groups + (groupId -> groupRef)
        replyTo ! group
        storage.groupSave(group)
        guardianRunningBehavior(counterCount, updatedGroups)
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
      // ---------------------------------------------------------------------
      case GuardianServiceStats(replyTo) =>
        replyTo ! ServiceStats(groups.size, counterCount)
        Behaviors.same
    }
  }

  def guardianBehavior(): Behavior[GuardianCommand] = {
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case GuardianStopCommand =>
          Behaviors.stopped
        case GuardianSetupCommand =>
          val storedGroups = storage.groupsList()
          val groups: Map[UUID, ActorRef[GroupCommand]] =
            storedGroups.map { group =>
              val groupActorName = s"group-${group.id}"
              val groupActorRef = context.spawn(groupBehavior(context.self, group, Map.empty, Map.empty), groupActorName)
              groupActorRef ! GroupRestoreCommand
              group.id -> groupActorRef
            }.toMap
          guardianRunningBehavior(0, groups)
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

  override def counterCreate(groupId: UUID, inputs: CounterCreateInputs): Future[Option[Counter]] = {
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

  override def serviceStatsGet(): Future[ServiceStats] = {
    countersSystem.ask(GuardianServiceStats(_))
  }
}
