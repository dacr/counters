/*
 * Copyright 2020-2026 David Crosson
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

import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.util.Timeout
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import counters.ServiceConfig
import counters.model.*
import counters.tools.ApiTokens
import com.github.plokhotnyuk.jsoniter_scala.core.*
import org.apache.commons.io.FileUtils
import org.apache.commons.io.input.ReversedLinesFileReader
import org.slf4j.LoggerFactory

import java.io.{File, FileFilter}
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try, Using}

trait CountersStorage {
  def usersList(): Iterable[UserAccount]

  def userSave(account: UserAccount): Boolean

  def userDelete(userId: UUID): Boolean

  def groupsList(): Iterable[CountersGroup]

  def groupCounters(groupId: UUID): Iterable[Counter]

  def groupGet(groupId: UUID): Option[CountersGroup]

  def counterGet(groupId: UUID, counterId: UUID): Option[Counter]

  def stateGet(groupId: UUID, counterId: UUID): Option[CounterState]

  def groupSave(group: CountersGroup): Boolean

  def counterSave(counter: Counter): Boolean

  def stateSave(state: CounterState): Boolean

  def counterDelete(groupId: UUID, counterId: UUID): Boolean

  def groupDelete(groupId: UUID): Boolean

  def historyAppend(groupId: UUID, counterId: UUID, entry: CounterHistoryEntry): Boolean

  /** the most recent entries first */
  def historyGet(groupId: UUID, counterId: UUID, limit: Int): List[CounterHistoryEntry]
}

class NopCounterStorage(config: ServiceConfig) extends CountersStorage {
  override def usersList(): Iterable[UserAccount] = Iterable.empty

  override def userSave(account: UserAccount): Boolean = true

  override def userDelete(userId: UUID): Boolean = true

  override def groupsList(): Iterable[CountersGroup] = Iterable.empty

  override def groupCounters(groupId: UUID): Iterable[Counter] = Iterable.empty

  override def groupGet(groupId: UUID): Option[CountersGroup] = None

  override def counterGet(groupId: UUID, counterId: UUID): Option[Counter] = None

  override def stateGet(groupId: UUID, counterId: UUID): Option[CounterState] = None

  override def groupSave(group: CountersGroup): Boolean = true

  override def counterSave(counter: Counter): Boolean = true

  override def stateSave(state: CounterState): Boolean = true

  override def counterDelete(groupId: UUID, counterId: UUID): Boolean = true

  override def groupDelete(groupId: UUID): Boolean = true

  override def historyAppend(groupId: UUID, counterId: UUID, entry: CounterHistoryEntry): Boolean = true

  override def historyGet(groupId: UUID, counterId: UUID, limit: Int): List[CounterHistoryEntry] = Nil
}

/** Storage layout :
  *   - users/<userId>/user.json
  *   - groups/<groupId>/group.json
  *   - groups/<groupId>/<counterId>/counter.json
  *   - groups/<groupId>/<counterId>/state.json
  *   - groups/<groupId>/<counterId>/history.jsonl, one increment per line
  */
class BasicCountersFileSystemStorage(config: ServiceConfig) extends CountersStorage {
  private val logger              = LoggerFactory.getLogger(getClass)
  private val storeConfig         = config.counters.behavior.fileSystemStorage
  private val storeBaseDirectory  = {
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
  private val usersBaseDirectory  = new File(storeBaseDirectory, "users")
  private val groupsBaseDirectory = new File(storeBaseDirectory, "groups")

  private val directoryUUIDNamedFilter = new FileFilter {
    override def accept(file: File): Boolean = {
      file.isDirectory && Try(UUID.fromString(file.getName)).toOption.isDefined
    }
  }

  private def subDirectoriesUUIDs(directory: File): Iterable[UUID] = {
    Option(directory.listFiles(directoryUUIDNamedFilter))
      .getOrElse(Array.empty[File])
      .map(_.getName)
      .flatMap(name => Try(UUID.fromString(name)).toOption)
  }

  def userDirectory(userId: UUID): File = {
    new File(usersBaseDirectory, userId.toString)
  }

  def groupDirectory(groupId: UUID): File = {
    new File(groupsBaseDirectory, groupId.toString)
  }

  def counterDirectory(groupId: UUID, counterId: UUID): File = {
    new File(groupDirectory(groupId), counterId.toString)
  }

  def jsonRead[T: JsonValueCodec](file: File): Option[T] = {
    if (!file.exists()) None
    else
      Try(readFromArray[T](FileUtils.readFileToByteArray(file))) match {
        case Success(value) => Some(value)
        case Failure(err)   =>
          logger.error(s"Unable to read $file : ${err.getMessage}")
          None
      }
  }

  def jsonWrite[T: JsonValueCodec](file: File, value: T): Boolean = {
    if (!file.getParentFile.exists()) file.getParentFile.mkdirs()
    val tmpFile = new File(file.getParent, file.getName + ".tmp")
    FileUtils.writeByteArrayToFile(tmpFile, writeToArray(value))
    file.delete()
    tmpFile.renameTo(file)
  }

  private def directoryDelete(directory: File, what: String): Boolean = {
    Try(FileUtils.deleteDirectory(directory)) match {
      case Success(_)   => true
      case Failure(err) =>
        logger.error(s"Unable to delete $what : ${err.getMessage}")
        false
    }
  }

  def userFile(userId: UUID): File = {
    new File(userDirectory(userId), "user.json")
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
    new File(counterDirectory(groupId, counterId), "history.jsonl")
  }

  override def usersList(): Iterable[UserAccount] = {
    subDirectoriesUUIDs(usersBaseDirectory)
      .map(userFile)
      .flatMap(jsonRead[UserAccount])
  }

  override def userSave(account: UserAccount): Boolean = {
    jsonWrite(userFile(account.user.id), account)
  }

  override def userDelete(userId: UUID): Boolean = {
    directoryDelete(userDirectory(userId), s"user $userId")
  }

  override def groupsList(): Iterable[CountersGroup] = {
    subDirectoriesUUIDs(groupsBaseDirectory)
      .map(groupFile)
      .flatMap(jsonRead[CountersGroup])
  }

  override def groupCounters(groupId: UUID): Iterable[Counter] = {
    subDirectoriesUUIDs(groupDirectory(groupId))
      .map(counterId => counterFile(groupId, counterId))
      .flatMap(jsonRead[Counter])
  }

  override def groupGet(groupId: UUID): Option[CountersGroup] = {
    jsonRead[CountersGroup](groupFile(groupId))
  }

  override def counterGet(groupId: UUID, counterId: UUID): Option[Counter] = {
    jsonRead[Counter](counterFile(groupId, counterId))
  }

  override def stateGet(groupId: UUID, counterId: UUID): Option[CounterState] = {
    jsonRead[CounterState](stateFile(groupId, counterId))
  }

  override def groupSave(group: CountersGroup): Boolean = {
    jsonWrite(groupFile(group.id), group)
  }

  override def counterSave(counter: Counter): Boolean = {
    jsonWrite(counterFile(counter.groupId, counter.id), counter)
  }

  override def stateSave(state: CounterState): Boolean = {
    jsonWrite(stateFile(state.counter.groupId, state.counter.id), state)
  }

  override def counterDelete(groupId: UUID, counterId: UUID): Boolean = {
    directoryDelete(counterDirectory(groupId, counterId), s"counter $counterId of group $groupId")
  }

  override def groupDelete(groupId: UUID): Boolean = {
    directoryDelete(groupDirectory(groupId), s"group $groupId")
  }

  override def historyAppend(groupId: UUID, counterId: UUID, entry: CounterHistoryEntry): Boolean = {
    Try(FileUtils.writeStringToFile(historyFile(groupId, counterId), writeToString(entry) + "\n", StandardCharsets.UTF_8, true)) match {
      case Success(_)   => true
      case Failure(err) =>
        logger.error(s"Unable to append to counter $counterId history : ${err.getMessage}")
        false
    }
  }

  override def historyGet(groupId: UUID, counterId: UUID, limit: Int): List[CounterHistoryEntry] = {
    val file = historyFile(groupId, counterId)
    if (!file.exists()) Nil
    else {
      val reader = ReversedLinesFileReader.builder().setFile(file).setCharset(StandardCharsets.UTF_8).get()
      Using(reader) { reader =>
        Iterator
          .continually(reader.readLine())
          .takeWhile(_ != null)
          .filter(_.nonEmpty)
          .flatMap(line => Try(readFromString[CounterHistoryEntry](line)).toOption)
          .take(limit)
          .toList
      } match {
        case Success(entries) => entries
        case Failure(err)     =>
          logger.error(s"Unable to read counter $counterId history : ${err.getMessage}")
          Nil
      }
    }
  }
}

object StandardCountersEngine {
  def apply(config: ServiceConfig): StandardCountersEngine = {
    val storage = new BasicCountersFileSystemStorage(config)
    // val storage = new NopCounterStorage(config)
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

  case class CounterHistoryCommand(
    limit: Int,
    replyTo: ActorRef[Option[List[CounterHistoryEntry]]]
  ) extends CounterCommand

  case class CounterUpdateCommand(
    inputs: CounterUpdateInputs,
    replyTo: ActorRef[Option[Counter]]
  ) extends CounterCommand

  case class CounterGroupUpdatedCommand(
    group: CountersGroup
  ) extends CounterCommand

  def counterBehavior(group: CountersGroup, groupActor: ActorRef[GroupCommand], currentState: CounterState): Behavior[CounterCommand] =
    Behaviors.receiveMessage {
      // ---------------------------------------------------------------------
      case CounterIncrementCommand(operationOrigin, replyTo) =>
        val newStateId     = UUID.randomUUID()
        val newCount       = currentState.count + 1
        val newLastUpdated = Instant.now()
        val newLastOrigin  = operationOrigin
        val newState       = CounterState(newStateId, group, currentState.counter, newCount, newLastUpdated, newLastOrigin)
        storage.stateSave(newState)
        storage.historyAppend(group.id, currentState.counter.id, CounterHistoryEntry(newStateId, newCount, newLastUpdated, newLastOrigin))
        replyTo ! Some(newState)
        groupActor ! GroupCounterUpdatedStateCommand(newState)
        counterBehavior(group, groupActor, newState)
      // ---------------------------------------------------------------------
      case CounterStateCommand(replyTo)                      =>
        replyTo ! Some(currentState)
        Behaviors.same
      // ---------------------------------------------------------------------
      case CounterHistoryCommand(limit, replyTo)             =>
        // read from the counter actor, so never concurrently with an append
        replyTo ! Some(storage.historyGet(group.id, currentState.counter.id, limit))
        Behaviors.same
      // ---------------------------------------------------------------------
      case CounterGetCommand(replyTo)                        =>
        replyTo ! Some(currentState.counter)
        Behaviors.same
      // ---------------------------------------------------------------------
      case CounterUpdateCommand(inputs, replyTo)             =>
        val newCounter = currentState.counter.copy(
          name = inputs.name,
          description = inputs.description,
          redirect = inputs.redirect,
          publicIncrement = inputs.publicIncrement
        )
        val newState   = currentState.copy(counter = newCounter)
        storage.counterSave(newCounter)
        storage.stateSave(newState)
        replyTo ! Some(newCounter)
        groupActor ! GroupCounterUpdatedStateCommand(newState)
        counterBehavior(group, groupActor, newState)
      // ---------------------------------------------------------------------
      case CounterGroupUpdatedCommand(updatedGroup)          =>
        // the group is embedded within the stored state, so it must be saved again
        val newState = currentState.copy(group = updatedGroup)
        storage.stateSave(newState)
        counterBehavior(updatedGroup, groupActor, newState)
    }

  // =================================================================================
  sealed trait GroupCommand

  object GroupRestoreCommand extends GroupCommand

  case class GroupCounterIncrementCommand(counterId: UUID, operationOrigin: Option[OperationOrigin], replyTo: ActorRef[Option[CounterState]]) extends GroupCommand

  case class GroupCounterUpdatedStateCommand(
    state: CounterState
  ) extends GroupCommand

  case class GroupCounterStateGetCommand(
    counterId: UUID,
    replyTo: ActorRef[Option[CounterState]]
  ) extends GroupCommand

  case class GroupCounterHistoryCommand(
    counterId: UUID,
    limit: Int,
    replyTo: ActorRef[Option[List[CounterHistoryEntry]]]
  ) extends GroupCommand

  case class GroupCounterGetCommand(
    counterId: UUID,
    replyTo: ActorRef[Option[Counter]]
  ) extends GroupCommand

  case class GroupCounterCreateCommand(
    inputs: CounterCreateInputs,
    replyTo: ActorRef[Option[Counter]]
  ) extends GroupCommand

  case class GroupGetCommand(
    replyTo: ActorRef[Option[CountersGroup]]
  ) extends GroupCommand

  case class GroupCountersCommand(
    replyTo: ActorRef[Option[List[Counter]]]
  ) extends GroupCommand

  case class GroupStatesCommand(
    replyTo: ActorRef[Option[List[CounterState]]]
  ) extends GroupCommand

  case class GroupCounterUpdateCommand(
    counterId: UUID,
    inputs: CounterUpdateInputs,
    replyTo: ActorRef[Option[Counter]]
  ) extends GroupCommand

  case class GroupCounterDeleteCommand(
    counterId: UUID,
    replyTo: ActorRef[Boolean]
  ) extends GroupCommand

  case class GroupUpdateCommand(
    inputs: GroupUpdateInputs,
    replyTo: ActorRef[Option[CountersGroup]]
  ) extends GroupCommand

  case class GroupDeleteCommand(
    replyTo: ActorRef[Boolean]
  ) extends GroupCommand

  def groupBehavior(counterKeeperRef: ActorRef[GuardianCounterAdded], group: CountersGroup, counters: Map[UUID, ActorRef[CounterCommand]], states: Map[UUID, CounterState]): Behavior[GroupCommand] =
    Behaviors.setup { context =>
      def toCounter(counterId: UUID, notFound: => Unit)(command: CounterCommand): Behavior[GroupCommand] = {
        counters.get(counterId) match {
          case None               => notFound
          case Some(counterActor) => counterActor ! command
        }
        Behaviors.same
      }
      Behaviors.receiveMessage {
        // ---------------------------------------------------------------------
        case GroupRestoreCommand                                                                          =>
          val storedCounters   = storage.groupCounters(group.id)
          val restoredStates   = storedCounters.map { counter =>
            counter.id -> storage.stateGet(counter.groupId, counter.id).get // TODO dangerous .get !!
          }.toMap
          val restoredCounters = storedCounters.map { counter =>
            val counterActorName = s"group-${counter.groupId}-counter-${counter.id}"
            val counterState     = restoredStates.get(counter.id).get // TODO dangerous .get !!
            val counterRef       = context.spawn(counterBehavior(group, context.self, counterState), counterActorName)
            counter.id -> counterRef
          }.toMap
          counterKeeperRef ! GuardianCounterAdded(storedCounters.size)
          groupBehavior(counterKeeperRef, group, counters ++ restoredCounters, states ++ restoredStates)
        // ---------------------------------------------------------------------
        case GroupCounterCreateCommand(inputs, replyTo)                                                   =>
          val groupId          = group.id
          val counterId        = UUID.randomUUID()
          val counter          = Counter(
            id = counterId,
            groupId = group.id,
            name = inputs.name,
            description = inputs.description,
            origin = inputs.origin,
            redirect = inputs.redirect,
            publicIncrement = inputs.publicIncrement
          )
          val initialState     = CounterState(
            id = UUID.randomUUID(),
            group = group,
            counter = counter,
            count = 0L,
            lastOrigin = inputs.origin,
            lastUpdated = Instant.now
          )
          val counterActorName = s"group-$groupId-counter-$counterId"
          val counterRef       = context.spawn(counterBehavior(group, context.self, initialState), counterActorName)
          val newCounters      = counters + (counterId -> counterRef)
          val newStates        = states + (counterId   -> initialState)
          storage.counterSave(counter)
          storage.stateSave(initialState)
          counterKeeperRef ! GuardianCounterAdded(1) // before replying, so that stats are up to date for the caller
          replyTo ! Some(counter)
          groupBehavior(counterKeeperRef, group, newCounters, newStates)
        // ---------------------------------------------------------------------
        case GroupCounterUpdatedStateCommand(updatedState) if !counters.contains(updatedState.counter.id) =>
          Behaviors.same // the counter has been deleted in the meantime
        // ---------------------------------------------------------------------
        case GroupCounterUpdatedStateCommand(updatedState)                     =>
          val updatedStates = states + (updatedState.counter.id -> updatedState)
          groupBehavior(counterKeeperRef, group, counters, updatedStates)
        // ---------------------------------------------------------------------
        case GroupGetCommand(replyTo)                                          =>
          replyTo ! Some(group)
          Behaviors.same
        // ---------------------------------------------------------------------
        case GroupCountersCommand(replyTo)                                     =>
          replyTo ! Some(states.values.map(_.counter).toList.sortBy(_.name))
          Behaviors.same
        // ---------------------------------------------------------------------
        case GroupStatesCommand(replyTo)                                       =>
          replyTo ! Some(states.values.toList.sortBy(_.counter.name))
          Behaviors.same
        // ---------------------------------------------------------------------
        case GroupCounterUpdateCommand(counterId, inputs, replyTo)             =>
          toCounter(counterId, replyTo ! None)(CounterUpdateCommand(inputs, replyTo))
        // ---------------------------------------------------------------------
        case GroupCounterDeleteCommand(counterId, replyTo)                     =>
          counters.get(counterId) match {
            case None               =>
              replyTo ! false
              Behaviors.same
            case Some(counterActor) =>
              context.stop(counterActor)
              storage.counterDelete(group.id, counterId)
              counterKeeperRef ! GuardianCounterAdded(-1) // before replying, so that stats are up to date for the caller
              replyTo ! true
              groupBehavior(counterKeeperRef, group, counters - counterId, states - counterId)
          }
        // ---------------------------------------------------------------------
        case GroupUpdateCommand(inputs, replyTo)                               =>
          val updatedGroup  = group.copy(name = inputs.name, description = inputs.description)
          storage.groupSave(updatedGroup)
          counters.values.foreach(_ ! CounterGroupUpdatedCommand(updatedGroup))
          val updatedStates = states.view.mapValues(_.copy(group = updatedGroup)).toMap
          replyTo ! Some(updatedGroup)
          groupBehavior(counterKeeperRef, updatedGroup, counters, updatedStates)
        // ---------------------------------------------------------------------
        case GroupDeleteCommand(replyTo)                                       =>
          storage.groupDelete(group.id)
          counterKeeperRef ! GuardianCounterAdded(-counters.size) // before replying, so that stats are up to date for the caller
          replyTo ! true
          Behaviors.stopped // counters actors, as children, are stopped as well
        // ---------------------------------------------------------------------
        case GroupCounterIncrementCommand(counterId, operationOrigin, replyTo) =>
          toCounter(counterId, replyTo ! None)(CounterIncrementCommand(operationOrigin, replyTo))
        // ---------------------------------------------------------------------
        case GroupCounterStateGetCommand(counterId, replyTo)                   =>
          toCounter(counterId, replyTo ! None)(CounterStateCommand(replyTo))
        // ---------------------------------------------------------------------
        case GroupCounterHistoryCommand(counterId, limit, replyTo)             =>
          toCounter(counterId, replyTo ! None)(CounterHistoryCommand(limit, replyTo))
        // ---------------------------------------------------------------------
        case GroupCounterGetCommand(counterId, replyTo)                        =>
          toCounter(counterId, replyTo ! None)(CounterGetCommand(replyTo))
      }
    }

  // =================================================================================

  sealed trait GuardianCommand

  object GuardianStopCommand extends GuardianCommand

  object GuardianSetupCommand extends GuardianCommand

  case class GuardianCounterAdded(counterAddedCount: Int) extends GuardianCommand

  case class GuardianServiceStats(replyTo: ActorRef[ServiceStats]) extends GuardianCommand

  object GuardianPurgeCommand extends GuardianCommand

  case class GuardianUserRegisterCommand(inputs: UserCreateInputs, replyTo: ActorRef[Option[UserRegistered]]) extends GuardianCommand

  case class GuardianUserEmailValidateCommand(validationCode: String, replyTo: ActorRef[Option[User]]) extends GuardianCommand

  case class GuardianUserAuthenticateCommand(token: String, replyTo: ActorRef[Option[User]]) extends GuardianCommand

  case class GuardianUserGetCommand(userId: UUID, replyTo: ActorRef[Option[User]]) extends GuardianCommand

  case class GuardianUserTokenRenewCommand(userId: UUID, replyTo: ActorRef[Option[String]]) extends GuardianCommand

  /** Replies with the user groups actors */
  case class GuardianUserGroupsCommand(userId: UUID, replyTo: ActorRef[Option[List[ActorRef[GroupCommand]]]]) extends GuardianCommand

  /** Forgets the user and its groups, replies with the groups actors which still have to be deleted */
  case class GuardianUserRemoveCommand(userId: UUID, replyTo: ActorRef[Option[List[ActorRef[GroupCommand]]]]) extends GuardianCommand

  case class GuardianGroupCreateCommand(ownerId: UUID, inputs: CountersGroupCreateInputs, replyTo: ActorRef[Option[CountersGroup]]) extends GuardianCommand

  /** Forgets the group, replies with the group actor which still has to be deleted */
  case class GuardianGroupRemoveCommand(ownerId: UUID, groupId: UUID, replyTo: ActorRef[Either[GroupDeleteOutcome, ActorRef[GroupCommand]]]) extends GuardianCommand

  /** Forwards the command to the group, when the group exists and, if an owner is given, belongs to this owner */
  case class GuardianGroupForwardCommand(ownerId: Option[UUID], groupId: UUID, command: GroupCommand, notFound: () => Unit) extends GuardianCommand

  case class GroupEntry(ownerId: UUID, ref: ActorRef[GroupCommand])

  case class GuardianState(
    counterCount: Int,
    users: Map[UUID, UserAccount],
    tokens: Map[String, UUID], // token hash to user id
    groups: Map[UUID, GroupEntry]
  ) {
    def userGroupsIds(userId: UUID): List[UUID] = groups.collect { case (groupId, entry) if entry.ownerId == userId => groupId }.toList
  }

  def spawnGroup(context: ActorContext[GuardianCommand], group: CountersGroup): ActorRef[GroupCommand] = {
    context.spawn(groupBehavior(context.self, group, Map.empty, Map.empty), s"group-${group.id}")
  }

  private val emailValidationDelay = config.counters.behavior.emailValidationDelay

  /** Drops, with their groups, the registrations whose email has not been validated in time */
  def purgeExpiredRegistrations(context: ActorContext[GuardianCommand], state: GuardianState, now: Instant): GuardianState = {
    val expired = state.users.values.filter(_.validationExpiresOn.exists(_.isBefore(now))).toList
    expired.foldLeft(state) { (current, account) =>
      val userId    = account.user.id
      val groupsIds = current.userGroupsIds(userId)
      logger.info(s"Dropping user $userId registration, its email has not been validated in time")
      groupsIds.foreach(groupId => current.groups(groupId).ref ! GroupDeleteCommand(context.system.ignoreRef))
      storage.userDelete(userId)
      current.copy(
        users = current.users - userId,
        tokens = current.tokens - account.tokenHash,
        groups = current.groups -- groupsIds
      )
    }
  }

  def guardianRunningBehavior(state: GuardianState): Behavior[GuardianCommand] = Behaviors.setup { context =>
    Behaviors.receiveMessage {
      // ---------------------------------------------------------------------
      case GuardianSetupCommand                                             => // Ignore already done
        Behaviors.same
      // ---------------------------------------------------------------------
      case GuardianStopCommand                                              =>
        Behaviors.stopped
      // ---------------------------------------------------------------------
      case GuardianCounterAdded(counterAddedCount)                          =>
        guardianRunningBehavior(state.copy(counterCount = state.counterCount + counterAddedCount))
      // ---------------------------------------------------------------------
      case GuardianServiceStats(replyTo)                                    =>
        replyTo ! ServiceStats(state.users.size, state.groups.size, state.counterCount)
        Behaviors.same
      // ---------------------------------------------------------------------
      case GuardianPurgeCommand                                             =>
        guardianRunningBehavior(purgeExpiredRegistrations(context, state, Instant.now()))
      // ---------------------------------------------------------------------
      case GuardianUserRegisterCommand(inputs, replyTo)                     =>
        val now    = Instant.now()
        // an expired registration must not keep its email address
        val purged = purgeExpiredRegistrations(context, state, now)
        val email  = inputs.email.trim.toLowerCase
        if (purged.users.values.exists(_.user.email == email)) {
          replyTo ! None
          guardianRunningBehavior(purged)
        } else {
          val userId    = UUID.randomUUID()
          val group     = CountersGroup(id = UUID.randomUUID(), ownerId = userId, name = "default", description = Some("default group"), origin = inputs.origin)
          val user      = User(id = userId, name = inputs.name, email = email, emailValidated = false, defaultGroupId = group.id, origin = inputs.origin)
          val token     = ApiTokens.generate()
          val code      = ApiTokens.generate()
          val expiresOn = now.plusMillis(emailValidationDelay.toMillis)
          val account   = UserAccount(user, ApiTokens.hash(token), Some(ApiTokens.hash(code)), Some(expiresOn))
          storage.userSave(account)
          storage.groupSave(group)
          val groupRef  = spawnGroup(context, group)
          replyTo ! Some(UserRegistered(UserRegistration(user, group, token), code, expiresOn))
          guardianRunningBehavior(
            purged.copy(
              users = purged.users + (userId              -> account),
              tokens = purged.tokens + (account.tokenHash -> userId),
              groups = purged.groups + (group.id          -> GroupEntry(userId, groupRef))
            )
          )
        }
      // ---------------------------------------------------------------------
      case GuardianUserEmailValidateCommand(validationCode, replyTo)        =>
        val codeHash = ApiTokens.hash(validationCode)
        val now      = Instant.now()
        state.users.values.find(account => account.validationCodeHash.contains(codeHash) && account.validationExpiresOn.exists(_.isAfter(now))) match {
          case None          =>
            replyTo ! None
            Behaviors.same
          case Some(account) =>
            val validated = account.copy(user = account.user.copy(emailValidated = true), validationCodeHash = None, validationExpiresOn = None)
            storage.userSave(validated)
            replyTo ! Some(validated.user)
            guardianRunningBehavior(state.copy(users = state.users + (validated.user.id -> validated)))
        }
      // ---------------------------------------------------------------------
      case GuardianUserAuthenticateCommand(token, replyTo)                  =>
        replyTo ! state.tokens.get(ApiTokens.hash(token)).flatMap(state.users.get).map(_.user)
        Behaviors.same
      // ---------------------------------------------------------------------
      case GuardianUserGetCommand(userId, replyTo)                          =>
        replyTo ! state.users.get(userId).map(_.user)
        Behaviors.same
      // ---------------------------------------------------------------------
      case GuardianUserTokenRenewCommand(userId, replyTo)                   =>
        state.users.get(userId) match {
          case None          =>
            replyTo ! None
            Behaviors.same
          case Some(account) =>
            val token   = ApiTokens.generate()
            val renewed = account.copy(tokenHash = ApiTokens.hash(token))
            storage.userSave(renewed)
            replyTo ! Some(token)
            guardianRunningBehavior(
              state.copy(
                users = state.users + (userId                                  -> renewed),
                tokens = state.tokens - account.tokenHash + (renewed.tokenHash -> userId)
              )
            )
        }
      // ---------------------------------------------------------------------
      case GuardianUserGroupsCommand(userId, replyTo)                       =>
        if (!state.users.contains(userId)) replyTo ! None
        else replyTo ! Some(state.userGroupsIds(userId).map(state.groups(_).ref))
        Behaviors.same
      // ---------------------------------------------------------------------
      case GuardianUserRemoveCommand(userId, replyTo)                       =>
        state.users.get(userId) match {
          case None          =>
            replyTo ! None
            Behaviors.same
          case Some(account) =>
            val groupsIds = state.userGroupsIds(userId)
            storage.userDelete(userId)
            replyTo ! Some(groupsIds.map(state.groups(_).ref))
            guardianRunningBehavior(
              state.copy(
                users = state.users - userId,
                tokens = state.tokens - account.tokenHash,
                groups = state.groups -- groupsIds
              )
            )
        }
      // ---------------------------------------------------------------------
      case GuardianGroupCreateCommand(ownerId, inputs, replyTo)             =>
        if (!state.users.contains(ownerId)) {
          replyTo ! None
          Behaviors.same
        } else {
          val group    = CountersGroup(id = UUID.randomUUID(), ownerId = ownerId, name = inputs.name, description = inputs.description, origin = inputs.origin)
          storage.groupSave(group)
          val groupRef = spawnGroup(context, group)
          replyTo ! Some(group)
          guardianRunningBehavior(state.copy(groups = state.groups + (group.id -> GroupEntry(ownerId, groupRef))))
        }
      // ---------------------------------------------------------------------
      case GuardianGroupRemoveCommand(ownerId, groupId, replyTo)            =>
        state.groups.get(groupId).filter(_.ownerId == ownerId) match {
          case None                                                                         =>
            replyTo ! Left(GroupDeleteOutcome.NotFound)
            Behaviors.same
          case Some(_) if state.users.get(ownerId).exists(_.user.defaultGroupId == groupId) =>
            replyTo ! Left(GroupDeleteOutcome.DefaultGroup)
            Behaviors.same
          case Some(entry)                                                                  =>
            // forgotten right now, so no more messages are routed to the group actor which is going to stop
            replyTo ! Right(entry.ref)
            guardianRunningBehavior(state.copy(groups = state.groups - groupId))
        }
      // ---------------------------------------------------------------------
      case GuardianGroupForwardCommand(ownerId, groupId, command, notFound) =>
        state.groups.get(groupId).filter(entry => ownerId.forall(_ == entry.ownerId)) match {
          case None        => notFound()
          case Some(entry) => entry.ref ! command
        }
        Behaviors.same
    }
  }

  def guardianBehavior(): Behavior[GuardianCommand] = {
    Behaviors.withTimers { timers =>
      Behaviors.setup { context =>
        Behaviors.receiveMessage {
        case GuardianStopCommand  =>
          Behaviors.stopped
        case GuardianSetupCommand =>
          val users            = storage.usersList().map(account => account.user.id -> account).toMap
          val (owned, orphans) = storage.groupsList().partition(group => users.contains(group.ownerId))
          orphans.foreach(group => logger.warn(s"Ignoring group ${group.id} as its owner ${group.ownerId} is unknown"))
          val groups           =
            owned.map { group =>
              val groupActorRef = spawnGroup(context, group)
              groupActorRef ! GroupRestoreCommand
              group.id -> GroupEntry(group.ownerId, groupActorRef)
            }.toMap
          val tokens           = users.values.map(account => account.tokenHash -> account.user.id).toMap
          timers.startTimerWithFixedDelay(GuardianPurgeCommand, 1.hour)
          guardianRunningBehavior(purgeExpiredRegistrations(context, GuardianState(0, users, tokens, groups), Instant.now()))
        case x                    => // Any other messages are ignored until the setup is received
          logger.warn(s"Can't process any standard messages until setup is done, received $x")
          Behaviors.same
        }
      }
    }
  }

  // =================================================================================

  implicit val countersSystem: ActorSystem[GuardianCommand] = ActorSystem(guardianBehavior(), "StandardCountersEngineActorSystem")
  implicit val ec: ExecutionContextExecutor                 = countersSystem.executionContext
  implicit val timeout: Timeout                             = 3.seconds

  countersSystem ! GuardianSetupCommand

  // =================================================================================

  private def toGroup[R](ownerId: Option[UUID], groupId: UUID, notFound: R)(command: ActorRef[R] => GroupCommand): Future[R] = {
    countersSystem.ask[R](replyTo => GuardianGroupForwardCommand(ownerId, groupId, command(replyTo), () => replyTo ! notFound))
  }

  private def groupsDelete(groupsRefs: List[ActorRef[GroupCommand]]): Future[Unit] = {
    Future.traverse(groupsRefs)(_.ask[Boolean](GroupDeleteCommand(_))).map(_ => ())
  }

  override def serviceStatsGet(): Future[ServiceStats] = {
    countersSystem.ask(GuardianServiceStats(_))
  }

  override def userRegister(inputs: UserCreateInputs): Future[Option[UserRegistered]] = {
    countersSystem.ask(GuardianUserRegisterCommand(inputs, _))
  }

  override def userEmailValidate(validationCode: String): Future[Option[User]] = {
    countersSystem.ask(GuardianUserEmailValidateCommand(validationCode, _))
  }

  override def userAuthenticate(token: String): Future[Option[User]] = {
    countersSystem.ask(GuardianUserAuthenticateCommand(token, _))
  }

  override def userGet(userId: UUID): Future[Option[User]] = {
    countersSystem.ask(GuardianUserGetCommand(userId, _))
  }

  override def userTokenRenew(userId: UUID): Future[Option[String]] = {
    countersSystem.ask(GuardianUserTokenRenewCommand(userId, _))
  }

  override def userDelete(userId: UUID): Future[Boolean] = {
    countersSystem.ask[Option[List[ActorRef[GroupCommand]]]](GuardianUserRemoveCommand(userId, _)).flatMap {
      case None             => Future.successful(false)
      case Some(groupsRefs) => groupsDelete(groupsRefs).map(_ => true)
    }
  }

  override def userGroups(userId: UUID): Future[Option[List[CountersGroup]]] = {
    countersSystem.ask[Option[List[ActorRef[GroupCommand]]]](GuardianUserGroupsCommand(userId, _)).flatMap {
      case None             => Future.successful(None)
      case Some(groupsRefs) =>
        Future
          .traverse(groupsRefs)(_.ask[Option[CountersGroup]](GroupGetCommand(_)))
          .map(groups => Some(groups.flatten.sortBy(_.name)))
    }
  }

  override def groupCreate(ownerId: UUID, inputs: CountersGroupCreateInputs): Future[Option[CountersGroup]] = {
    countersSystem.ask(GuardianGroupCreateCommand(ownerId, inputs, _))
  }

  override def groupGet(ownerId: UUID, groupId: UUID): Future[Option[CountersGroup]] = {
    toGroup(Some(ownerId), groupId, None)(GroupGetCommand(_))
  }

  override def groupUpdate(ownerId: UUID, groupId: UUID, inputs: GroupUpdateInputs): Future[Option[CountersGroup]] = {
    toGroup(Some(ownerId), groupId, None)(GroupUpdateCommand(inputs, _))
  }

  override def groupDelete(ownerId: UUID, groupId: UUID): Future[GroupDeleteOutcome] = {
    countersSystem.ask[Either[GroupDeleteOutcome, ActorRef[GroupCommand]]](GuardianGroupRemoveCommand(ownerId, groupId, _)).flatMap {
      case Left(outcome)   => Future.successful(outcome)
      case Right(groupRef) => groupsDelete(List(groupRef)).map(_ => GroupDeleteOutcome.Deleted)
    }
  }

  override def groupCounters(ownerId: UUID, groupId: UUID): Future[Option[List[Counter]]] = {
    toGroup(Some(ownerId), groupId, None)(GroupCountersCommand(_))
  }

  override def groupStates(ownerId: UUID, groupId: UUID): Future[Option[List[CounterState]]] = {
    toGroup(Some(ownerId), groupId, None)(GroupStatesCommand(_))
  }

  override def counterCreate(ownerId: UUID, groupId: UUID, inputs: CounterCreateInputs): Future[Option[Counter]] = {
    toGroup(Some(ownerId), groupId, None)(GroupCounterCreateCommand(inputs, _))
  }

  override def counterGet(ownerId: UUID, groupId: UUID, counterId: UUID): Future[Option[Counter]] = {
    toGroup(Some(ownerId), groupId, None)(GroupCounterGetCommand(counterId, _))
  }

  override def counterUpdate(ownerId: UUID, groupId: UUID, counterId: UUID, inputs: CounterUpdateInputs): Future[Option[Counter]] = {
    toGroup(Some(ownerId), groupId, None)(GroupCounterUpdateCommand(counterId, inputs, _))
  }

  override def counterDelete(ownerId: UUID, groupId: UUID, counterId: UUID): Future[Boolean] = {
    toGroup(Some(ownerId), groupId, false)(GroupCounterDeleteCommand(counterId, _))
  }

  override def counterState(ownerId: UUID, groupId: UUID, counterId: UUID): Future[Option[CounterState]] = {
    toGroup(Some(ownerId), groupId, None)(GroupCounterStateGetCommand(counterId, _))
  }

  override def counterHistory(ownerId: UUID, groupId: UUID, counterId: UUID, limit: Int): Future[Option[List[CounterHistoryEntry]]] = {
    toGroup(Some(ownerId), groupId, None)(GroupCounterHistoryCommand(counterId, limit, _))
  }

  override def counterIncrement(groupId: UUID, counterId: UUID, origin: Option[OperationOrigin]): Future[Option[CounterState]] = {
    toGroup(None, groupId, None)(GroupCounterIncrementCommand(counterId, origin, _))
  }

  override def stateGet(groupId: UUID, counterId: UUID): Future[Option[CounterState]] = {
    toGroup(None, groupId, None)(GroupCounterStateGetCommand(counterId, _))
  }

  override def shutdown(): Future[Boolean] = {
    countersSystem ! GuardianStopCommand
    countersSystem.whenTerminated.map(_ => true)
  }
}
