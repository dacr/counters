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

/** Documented API endpoints definitions, the OpenAPI specification is generated from them. Server logic is provided separately, see the routing package.
  */
object ApiEndpoints {

  val description: String =
    """Count anything : web pages visits, downloads, events...
      |
      |Counters are organized within **groups**. Create a group, then create counters within it, both are identified by UUIDs.
      |
      |Counters can be incremented in two ways :
      |- through the API, `POST /api/group/{groupId}/counter/{counterId}/increment`, which returns the new counter value,
      |- through a browser, with the count page `GET /{groupId}/count/{counterId}`, which increments the counter and then redirects
      |  the browser to the counter `redirect` URL, or to the counter state page when no redirect URL has been defined.
      |
      |Each operation records its origin (client IP address and user agent).""".stripMargin

  object Tags {
    val groups   = "groups"
    val counters = "counters"
    val pages    = "pages"
    val service  = "service"

    val descriptions: List[(String, String)] = List(
      groups   -> "Groups of counters management",
      counters -> "Counters management, increments and states",
      pages    -> "Browser oriented pages, not returning JSON",
      service  -> "Service information and health"
    )
  }

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

  private val serviceEndpoint  = endpoint.tag(Tags.service)
  private val groupsEndpoint   = endpoint.tag(Tags.groups).in("api" / "group")
  private val countersEndpoint = endpoint.tag(Tags.counters).in("api" / "group")
  private val pagesEndpoint    = endpoint.tag(Tags.pages)

  // -------------------------------------------------------------------------------------------------------------------
  // service

  val health: PublicEndpoint[Unit, Unit, Health, Any] =
    serviceEndpoint
      .name("health")
      .summary("Service health check")
      .get
      .in("health")
      .out(jsonBody[Health].description("service health status"))

  val info: PublicEndpoint[Unit, Unit, ServiceInfo, Any] =
    serviceEndpoint
      .name("info")
      .summary("General information about the service")
      .get
      .in("api" / "info")
      .out(jsonBody[ServiceInfo].description("service information"))

  // -------------------------------------------------------------------------------------------------------------------
  // groups

  val groupCreate: PublicEndpoint[(GroupCreateRequest, OperationOrigin), Unit, CountersGroup, Any] =
    groupsEndpoint
      .name("groupCreate")
      .summary("Create a new group of counters")
      .post
      .in(jsonBody[GroupCreateRequest].example(GroupCreateRequest("my web site", Some("my web site pages counters"))))
      .in(operationOrigin)
      .out(jsonBody[CountersGroup].description("the created group"))

  val groupGet: PublicEndpoint[UUID, ApiError, CountersGroup, Any] =
    groupsEndpoint
      .name("groupGet")
      .summary("Get a group of counters information")
      .get
      .in(groupId)
      .out(jsonBody[CountersGroup].description("the group information"))
      .errorOut(notFound("group not found"))

  val groupUpdate: PublicEndpoint[(UUID, GroupUpdateRequest), ApiError, CountersGroup, Any] =
    groupsEndpoint
      .name("groupUpdate")
      .summary("Update a group of counters")
      .description("All editable fields are replaced, an optional field which is not provided is removed.")
      .put
      .in(groupId)
      .in(jsonBody[GroupUpdateRequest].example(GroupUpdateRequest("my web site", Some("my web site pages counters"))))
      .out(jsonBody[CountersGroup].description("the updated group"))
      .errorOut(notFound("group not found"))

  val groupDelete: PublicEndpoint[UUID, ApiError, Unit, Any] =
    groupsEndpoint
      .name("groupDelete")
      .summary("Delete a group of counters, with all its counters")
      .description("The group, all its counters and their states are definitively removed.")
      .delete
      .in(groupId)
      .out(statusCode(StatusCode.NoContent).description("the group has been deleted"))
      .errorOut(notFound("group not found"))

  // -------------------------------------------------------------------------------------------------------------------
  // counters

  val groupCounters: PublicEndpoint[UUID, ApiError, List[Counter], Any] =
    countersEndpoint
      .name("groupCounters")
      .summary("List the counters of a group, sorted by name")
      .get
      .in(groupId / "counter")
      .out(jsonBody[List[Counter]].description("the group counters"))
      .errorOut(notFound("group not found"))

  val counterCreate: PublicEndpoint[(UUID, CounterCreateRequest, OperationOrigin), ApiError, Counter, Any] =
    countersEndpoint
      .name("counterCreate")
      .summary("Create a new counter within the given group")
      .description("The counter starts at 0.")
      .post
      .in(groupId / "counter")
      .in(
        jsonBody[CounterCreateRequest]
          .example(CounterCreateRequest("home page", Some("home page visits"), Some(java.net.URI("https://example.com/").toURL)))
      )
      .in(operationOrigin)
      .out(jsonBody[Counter].description("the created counter"))
      .errorOut(notFound("group not found"))

  val counterGet: PublicEndpoint[(UUID, UUID), ApiError, Counter, Any] =
    countersEndpoint
      .name("counterGet")
      .summary("Get a counter information")
      .get
      .in(groupId / "counter" / counterId)
      .out(jsonBody[Counter].description("the counter information"))
      .errorOut(notFound("group or counter not found"))

  val counterUpdate: PublicEndpoint[(UUID, UUID, CounterUpdateRequest), ApiError, Counter, Any] =
    countersEndpoint
      .name("counterUpdate")
      .summary("Update a counter")
      .description("All editable fields are replaced, an optional field which is not provided is removed. The counter value is kept unchanged.")
      .put
      .in(groupId / "counter" / counterId)
      .in(jsonBody[CounterUpdateRequest].example(CounterUpdateRequest("home page", Some("home page visits"), None)))
      .out(jsonBody[Counter].description("the updated counter"))
      .errorOut(notFound("group or counter not found"))

  val counterDelete: PublicEndpoint[(UUID, UUID), ApiError, Unit, Any] =
    countersEndpoint
      .name("counterDelete")
      .summary("Delete a counter and its state")
      .delete
      .in(groupId / "counter" / counterId)
      .out(statusCode(StatusCode.NoContent).description("the counter has been deleted"))
      .errorOut(notFound("group or counter not found"))

  val counterState: PublicEndpoint[(UUID, UUID), ApiError, CounterState, Any] =
    countersEndpoint
      .name("counterState")
      .summary("Get a counter current state")
      .description("The state contains the counter value, its last update origin, the counter and its group.")
      .get
      .in(groupId / "counter" / counterId / "state")
      .out(jsonBody[CounterState].description("the counter current state"))
      .errorOut(notFound("group or counter not found"))

  val counterIncrement: PublicEndpoint[(UUID, UUID, OperationOrigin), ApiError, CounterValue, Any] =
    countersEndpoint
      .name("counterIncrement")
      .summary("Increment a counter")
      .description(
        "Increments the counter by one and returns its new value. The counter `redirect` URL is not used here, " +
          "browsers should use the count page `GET /{groupId}/count/{counterId}` instead."
      )
      .post
      .in(groupId / "counter" / counterId / "increment")
      .in(operationOrigin)
      .out(jsonBody[CounterValue].description("the counter value after the increment"))
      .errorOut(notFound("group or counter not found"))

  val counterIncrementLegacy: PublicEndpoint[(UUID, UUID, OperationOrigin), ApiError, CounterState, Any] =
    endpoint
      .tag(Tags.counters)
      .name("counterIncrementLegacy")
      .summary("Increment a counter, deprecated")
      .description("Use `POST /api/group/{groupId}/counter/{counterId}/increment` instead.")
      .deprecated()
      .get
      .in("api" / "increment" / groupId / counterId)
      .in(operationOrigin)
      .out(jsonBody[CounterState].description("the counter state after the increment"))
      .errorOut(notFound("group or counter not found"))

  // -------------------------------------------------------------------------------------------------------------------
  // pages

  val countPage: PublicEndpoint[(UUID, UUID, OperationOrigin), String, String, Any] =
    pagesEndpoint
      .name("countPage")
      .summary("Increment a counter and redirect the browser")
      .description(
        """Designed to be used as a link target. Increments the counter by one, then redirects the browser :
          |- to the counter `redirect` URL when defined, with the `count`, `groupId`, `counterId` and `stateId` query parameters appended,
          |- to the counter state page `GET /{groupId}/state/{counterId}` otherwise.""".stripMargin
      )
      .get
      .in(groupId / "count" / counterId)
      .in(operationOrigin)
      .out(
        statusCode(StatusCode.TemporaryRedirect)
          .description("the counter has been incremented")
          .and(header[String](HeaderNames.Location).description("the counter redirect URL, or the counter state page URL"))
      )
      .errorOut(statusCode(StatusCode.NotFound).description("group or counter not found").and(stringBody))

  val statePage: PublicEndpoint[(UUID, UUID), String, String, Any] =
    pagesEndpoint
      .name("statePage")
      .summary("Counter state page")
      .get
      .in(groupId / "state" / counterId)
      .out(htmlBodyUtf8.description("html page showing the counter current value"))
      .errorOut(statusCode(StatusCode.NotFound).description("group or counter not found").and(stringBody))

  // -------------------------------------------------------------------------------------------------------------------

  val all: List[AnyEndpoint] = List(
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
    counterIncrementLegacy,
    countPage,
    statePage,
    info,
    health
  )
}
