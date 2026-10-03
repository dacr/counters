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
package counters.api

import counters.model.*
import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*

import java.util.UUID

/** Documented API endpoints definitions, the OpenAPI specification is generated from them. Server logic is provided separately, see the
  * routing package.
  */
object ApiEndpoints {

  /** Client IP address without port, unlike tapir's clientIp which may return the raw Remote-Address header value */
  private val clientAddress: EndpointInput[Option[String]] =
    extractFromRequest { request =>
      request
        .header(HeaderNames.XForwardedFor)
        .flatMap(_.split(",").headOption)
        .map(_.trim)
        .orElse(request.header("X-Real-Ip"))
        .orElse(request.connectionInfo.remote.flatMap(remote => Option(remote.getAddress)).map(_.getHostAddress))
    }

  /** Where the request comes from, computed from the request, not part of the documentation */
  val operationOrigin: EndpointInput[OperationOrigin] =
    clientAddress
      .and(extractFromRequest(_.header(HeaderNames.UserAgent)))
      .map((ip, agent) => OperationOrigin(ip, agent))(origin => (origin.createdByIpAddress, origin.createdByUserAgent))

  private val groupId   = path[UUID]("groupId").description("Counters group unique identifier")
  private val counterId = path[UUID]("counterId").description("Counter unique identifier")

  private def notFound(description: String): EndpointOutput[ApiError] =
    statusCode(StatusCode.NotFound).description(description).and(jsonBody[ApiError].example(ApiError(description)))

  private val adminEndpoint    = endpoint.tag("admin")
  private val countersEndpoint = endpoint.tag("counters").in("api")

  // -------------------------------------------------------------------------------------------------------------------

  val health: PublicEndpoint[Unit, Unit, Health, Any] =
    adminEndpoint
      .name("health")
      .summary("Service health check")
      .get
      .in("health")
      .out(jsonBody[Health].description("service health status"))

  val info: PublicEndpoint[Unit, Unit, ServiceInfo, Any] =
    countersEndpoint
      .name("info")
      .summary("General information about the service")
      .get
      .in("info")
      .out(jsonBody[ServiceInfo].description("service information"))

  val groupCreate: PublicEndpoint[(GroupCreateRequest, OperationOrigin), Unit, CountersGroup, Any] =
    countersEndpoint
      .name("groupCreate")
      .summary("Create a new group of counters")
      .post
      .in("group")
      .in(jsonBody[GroupCreateRequest].example(GroupCreateRequest("my web site", Some("my web site pages counters"))))
      .in(operationOrigin)
      .out(jsonBody[CountersGroup].description("the created group"))

  val counterCreate: PublicEndpoint[(UUID, CounterCreateRequest, OperationOrigin), ApiError, Counter, Any] =
    countersEndpoint
      .name("counterCreate")
      .summary("Create a new counter within the given group")
      .post
      .in("group" / groupId / "counter")
      .in(jsonBody[CounterCreateRequest].example(CounterCreateRequest("home page", Some("home page visits"), None)))
      .in(operationOrigin)
      .out(jsonBody[Counter].description("the created counter"))
      .errorOut(notFound("group not found"))

  val counterState: PublicEndpoint[(UUID, UUID), ApiError, CounterState, Any] =
    countersEndpoint
      .name("counterState")
      .summary("Get a counter current state")
      .get
      .in("group" / groupId / "counter" / counterId)
      .out(jsonBody[CounterState].description("the counter current state"))
      .errorOut(notFound("group or counter not found"))

  val counterIncrement: PublicEndpoint[(UUID, UUID, OperationOrigin), ApiError, CounterState, Any] =
    countersEndpoint
      .name("counterIncrement")
      .summary("Increment a counter")
      .get
      .in("increment" / groupId / counterId)
      .in(operationOrigin)
      .out(jsonBody[CounterState].description("the counter state after the increment"))
      .errorOut(notFound("group or counter not found"))

  val all: List[AnyEndpoint] = List(info, groupCreate, counterCreate, counterState, counterIncrement, health)
}
