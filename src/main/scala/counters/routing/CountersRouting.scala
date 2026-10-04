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
package counters.routing

import counters.ServiceDependencies
import counters.api.{ApiEndpoints, ApiError, CounterValue, ServiceInfo}
import counters.model.{CounterCreateInputs, CounterUpdateInputs, CountersGroupCreateInputs, GroupUpdateInputs}
import counters.tools.DateTimeTools

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

case class CountersRouting(dependencies: ServiceDependencies) extends Routing with DateTimeTools {
  private given ExecutionContext = ExecutionContext.global

  private val engine = dependencies.engine
  private val meta   = dependencies.config.counters.metaInfo

  private val serviceInfo = ServiceInfo(
    instanceUUID = UUID.randomUUID(),
    startedOn = epochToUTCDateTime(now()),
    version = meta.version,
    buildDate = meta.buildDateTime
  )

  private val notFound      = ApiError("group or counter not found")
  private val groupNotFound = ApiError("group not found")

  private val info = ApiEndpoints.info.serverLogicSuccess[Future](_ => Future.successful(serviceInfo))

  private val groupCreate = ApiEndpoints.groupCreate.serverLogicSuccess[Future] { (request, origin) =>
    engine.groupCreate(CountersGroupCreateInputs(request.name, request.description, Some(origin)))
  }

  private val counterCreate = ApiEndpoints.counterCreate.serverLogic[Future] { (groupId, request, origin) =>
    engine
      .counterCreate(groupId, CounterCreateInputs(request.name, request.description, request.redirect, Some(origin)))
      .map(_.toRight(groupNotFound))
  }

  private val counterGet = ApiEndpoints.counterGet.serverLogic[Future] { (groupId, counterId) =>
    engine.counterGet(groupId, counterId).map(_.toRight(notFound))
  }

  private val counterState = ApiEndpoints.counterState.serverLogic[Future] { (groupId, counterId) =>
    engine.stateGet(groupId, counterId).map(_.toRight(notFound))
  }

  private val counterIncrement = ApiEndpoints.counterIncrement.serverLogic[Future] { (groupId, counterId, origin) =>
    engine
      .counterIncrement(groupId, counterId, Some(origin))
      .map(_.map(state => CounterValue(state.count, state.lastUpdated)).toRight(notFound))
  }

  private val counterIncrementLegacy = ApiEndpoints.counterIncrementLegacy.serverLogic[Future] { (groupId, counterId, origin) =>
    engine.counterIncrement(groupId, counterId, Some(origin)).map(_.toRight(notFound))
  }

  private val groupGet = ApiEndpoints.groupGet.serverLogic[Future] { groupId =>
    engine.groupGet(groupId).map(_.toRight(groupNotFound))
  }

  private val groupUpdate = ApiEndpoints.groupUpdate.serverLogic[Future] { (groupId, request) =>
    engine
      .groupUpdate(groupId, GroupUpdateInputs(request.name, request.description))
      .map(_.toRight(groupNotFound))
  }

  private val groupDelete = ApiEndpoints.groupDelete.serverLogic[Future] { groupId =>
    engine.groupDelete(groupId).map(deleted => if (deleted) Right(()) else Left(groupNotFound))
  }

  private val groupCounters = ApiEndpoints.groupCounters.serverLogic[Future] { groupId =>
    engine.groupCounters(groupId).map(_.toRight(groupNotFound))
  }

  private val counterUpdate = ApiEndpoints.counterUpdate.serverLogic[Future] { (groupId, counterId, request) =>
    engine
      .counterUpdate(groupId, counterId, CounterUpdateInputs(request.name, request.description, request.redirect))
      .map(_.toRight(notFound))
  }

  private val counterDelete = ApiEndpoints.counterDelete.serverLogic[Future] { (groupId, counterId) =>
    engine.counterDelete(groupId, counterId).map(deleted => if (deleted) Right(()) else Left(notFound))
  }

  override def endpoints =
    List(
      info,
      groupCreate,
      groupGet,
      groupUpdate,
      groupDelete,
      groupCounters,
      counterCreate,
      counterGet,
      counterUpdate,
      counterDelete,
      counterState,
      counterIncrement,
      counterIncrementLegacy
    )
}
