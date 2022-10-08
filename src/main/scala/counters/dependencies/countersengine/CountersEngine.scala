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

import counters.model.{Counter, CounterCreateInputs, CounterState, CountersGroup, CountersGroupCreateInputs, OperationOrigin, ServiceStats}

import java.util.UUID
import scala.concurrent.Future

trait CountersEngine {

  def serviceStatsGet():Future[ServiceStats]

  def groupCreate(inputs:CountersGroupCreateInputs):Future[CountersGroup]
  def groupCounters(groupId: UUID):Future[Option[List[Counter]]]
  def groupStates(groupId: UUID):Future[Option[List[CounterState]]]

  def counterCreate(groupId:UUID, inputs:CounterCreateInputs):Future[Option[Counter]]
  def counterIncrement(groupId: UUID, counterId: UUID, origin: Option[OperationOrigin]): Future[Option[CounterState]]
  def counterGet(groupId: UUID, counterId: UUID):Future[Option[Counter]]

  def stateGet(groupId: UUID, counterId: UUID):Future[Option[CounterState]]

  def shutdown():Future[Boolean]
}
