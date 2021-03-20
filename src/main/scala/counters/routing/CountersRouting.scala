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
package counters.routing

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
import org.slf4j.LoggerFactory
import counters.ServiceDependencies
import counters.model.{CounterCreateInputs, CountersGroupCreateInputs, OperationOrigin}
import counters.tools.DateTimeTools

import java.util.UUID

case class CountersRouting(dependencies: ServiceDependencies) extends Routing with DateTimeTools {
  private val logger = LoggerFactory.getLogger(getClass)

  val apiURL = dependencies.config.counters.site.apiURL
  val meta = dependencies.config.counters.metaInfo
  val startedDate = now()
  val instanceUUID = UUID.randomUUID().toString

  implicit val ec = scala.concurrent.ExecutionContext.global

  override def routes: Route = pathPrefix("api") {
    concat(
      info,
      groupCreate,
      counterCreate,
      counterState,
      increment
    )
  }

  def info: Route = {
    get {
      path("info") {
        complete(
          Map(
            "instanceUUID" -> instanceUUID,
            "startedOn" -> epochToUTCDateTime(startedDate),
            "version" -> meta.version,
            "buildDate" -> meta.buildDateTime
          )
        )
      }
    }
  }

  def groupCreate: Route = {
    path("group") {
      post {
        entity(as[CountersGroupCreateInputs]) { inputs =>
          optionalHeaderValueByName("User-Agent") { agent =>
            extractClientIP { ip =>
              val origin = OperationOrigin(ip.toOption.map(_.getHostAddress), agent)
              val updatedInputs = inputs.copy(origin = Some(origin))
              onSuccess(dependencies.engine.groupCreate(updatedInputs)) { result =>
                complete(result)
              }
            }
          }
        }
      }
    }
  }

  def counterCreate: Route = {
    path("group" / JavaUUID / "counter") { groupId =>
      post {
        entity(as[CounterCreateInputs]) { inputs =>
          optionalHeaderValueByName("User-Agent") { agent =>
            extractClientIP { ip =>
              val origin = OperationOrigin(ip.toOption.map(_.getHostAddress), agent)
              val updatedInputs = inputs.copy(origin = Some(origin))
              onSuccess(dependencies.engine.counterCreate(groupId, updatedInputs)) { result =>
                complete(result)
              }
            }
          }
        }
      }
    }
  }

  def counterState: Route = {
    path("group" / JavaUUID / "counter" / JavaUUID) { (groupId, counterId) =>
      get {
        onSuccess(dependencies.engine.stateGet(groupId, counterId)) {
          case Some(state) => complete(state)
          case None => complete(StatusCodes.NotFound -> "group or counter not found")
        }
      }
    }
  }

  def increment: Route = {
    path("increment" / JavaUUID / JavaUUID) { (groupId, counterId) =>
      get {
        optionalHeaderValueByName("User-Agent") { agent =>
          extractClientIP { ip =>
            val origin = OperationOrigin(ip.toOption.map(_.getHostAddress), agent)
            onSuccess(dependencies.engine.counterIncrement(groupId, counterId, Some(origin))) {
              case Some(state) => complete(state)
              case None => complete(StatusCodes.NotFound -> "group or counter not found")
            }
          }
        }
      }
    }
  }

}
