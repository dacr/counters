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

import counters.model.*

import java.util.UUID
import scala.concurrent.Future

enum GroupDeleteOutcome {
  case Deleted, NotFound, DefaultGroup
}

/** Groups and counters operations are scoped to their owner, an unknown or not owned group or counter is reported as not found. Only the counter increment and the counter state, used by the public
  * pages, are not scoped.
  */
trait CountersEngine {

  def serviceStatsGet(): Future[ServiceStats]

  /** None when the email is already used by another user */
  def userRegister(inputs: UserCreateInputs): Future[Option[UserRegistered]]
  /** None when the code is unknown or expired */
  def userEmailValidate(validationCode: String): Future[Option[User]]
  def userAuthenticate(token: String): Future[Option[User]]
  def userGet(userId: UUID): Future[Option[User]]
  def userTokenRenew(userId: UUID): Future[Option[String]]
  def userDelete(userId: UUID): Future[Boolean]
  def userGroups(userId: UUID): Future[Option[List[CountersGroup]]]

  def groupCreate(ownerId: UUID, inputs: CountersGroupCreateInputs): Future[Option[CountersGroup]]
  def groupGet(ownerId: UUID, groupId: UUID): Future[Option[CountersGroup]]
  def groupUpdate(ownerId: UUID, groupId: UUID, inputs: GroupUpdateInputs): Future[Option[CountersGroup]]
  def groupDelete(ownerId: UUID, groupId: UUID): Future[GroupDeleteOutcome]
  def groupCounters(ownerId: UUID, groupId: UUID): Future[Option[List[Counter]]]
  def groupStates(ownerId: UUID, groupId: UUID): Future[Option[List[CounterState]]]

  def counterCreate(ownerId: UUID, groupId: UUID, inputs: CounterCreateInputs): Future[Option[Counter]]
  def counterGet(ownerId: UUID, groupId: UUID, counterId: UUID): Future[Option[Counter]]
  def counterUpdate(ownerId: UUID, groupId: UUID, counterId: UUID, inputs: CounterUpdateInputs): Future[Option[Counter]]
  def counterDelete(ownerId: UUID, groupId: UUID, counterId: UUID): Future[Boolean]
  def counterState(ownerId: UUID, groupId: UUID, counterId: UUID): Future[Option[CounterState]]
  def counterHistory(ownerId: UUID, groupId: UUID, counterId: UUID, limit: Int): Future[Option[List[CounterHistoryEntry]]]

  /** Not scoped, any one knowing the group and counter identifiers can increment the counter */
  def counterIncrement(groupId: UUID, counterId: UUID, origin: Option[OperationOrigin]): Future[Option[CounterState]]

  /** Not scoped, any one knowing the group and counter identifiers can get the counter state */
  def stateGet(groupId: UUID, counterId: UUID): Future[Option[CounterState]]

  def shutdown(): Future[Boolean]
}
