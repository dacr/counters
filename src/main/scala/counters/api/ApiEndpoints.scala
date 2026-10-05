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
      |Register first with `POST /api/user`, the response contains your API token, given only once, and your default group.
      |A validation link is emailed to you, the API token can only be used once this link has been followed and confirmed,
      |unvalidated registrations are dropped after a while.
      |The token must be sent with every other API call as a bearer token : `Authorization: Bearer <token>`.
      |A lost token can be replaced, using the current one, with `POST /api/user/me/token`.
      |
      |Counters are organized within **groups**, groups belong to users, each user only sees and manages its own groups.
      |Groups and counters are identified by UUIDs.
      |
      |Counters can be incremented in two ways :
      |- through the API, `POST /api/group/{groupId}/counter/{counterId}/increment`, which returns the new counter value.
      |  The API token is required unless the counter has been created with `publicIncrement` enabled,
      |- through a browser, with the public count page `GET /{groupId}/count/{counterId}`, which increments the counter and then redirects
      |  the browser to the counter `redirect` URL, or to the counter state page when no redirect URL has been defined.
      |
      |Each operation records its origin (client IP address, user agent and referer), each counter keeps the history of its increments.""".stripMargin

  object Tags {
    val users    = "users"
    val groups   = "groups"
    val counters = "counters"
    val pages    = "pages"
    val service  = "service"

    val descriptions: List[(String, String)] = List(
      users    -> "Users registration and account management",
      groups   -> "Groups of counters management, a group always belongs to a user",
      counters -> "Counters management, increments, states and history",
      pages    -> "Public browser oriented pages, not returning JSON",
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
      .and(extractFromRequest(_.header(HeaderNames.Referer)))
      .map((ip, agent, referer) => OperationOrigin(ip, agent, referer))(origin => (origin.ipAddress, origin.userAgent, origin.referer))

  private val groupId   = path[UUID]("groupId").description("Counters group unique identifier")
  private val counterId = path[UUID]("counterId").description("Counter unique identifier")

  private val apiTokenSchemeName  = "apiToken"
  private val apiTokenDescription = "User API token, as given on registration"

  private val unauthorized    = StatusCode.Unauthorized -> "missing or invalid API token"
  private val notValidated    = StatusCode.Forbidden    -> "the user email address has not been validated yet"
  private val groupNotFound   = StatusCode.NotFound     -> "group not found"
  private val counterNotFound = StatusCode.NotFound     -> "group or counter not found"

  /** Failures share the same body, the possible status codes are documented per endpoint */
  private def failures(codes: (StatusCode, String)*): EndpointOutput[ApiFailure] =
    codes
      .foldLeft(statusCode)((output, code) => output.description(code._1, code._2))
      .and(jsonBody[ApiError])
      .mapTo[ApiFailure]

  private val securedEndpoint =
    endpoint.securityIn(auth.bearer[String]().securitySchemeName(apiTokenSchemeName).description(apiTokenDescription))

  private val serviceEndpoint  = endpoint.tag(Tags.service)
  private val usersEndpoint    = endpoint.tag(Tags.users).in("api" / "user")
  private val meEndpoint       = securedEndpoint.tag(Tags.users).in("api" / "user" / "me")
  private val groupsEndpoint   = securedEndpoint.tag(Tags.groups).in("api" / "group")
  private val countersEndpoint = securedEndpoint.tag(Tags.counters).in("api" / "group")
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
  // users

  val userRegister: PublicEndpoint[(UserRegisterRequest, OperationOrigin), ApiFailure, UserRegistration, Any] =
    usersEndpoint
      .name("userRegister")
      .summary("Register a new user")
      .description(
        "A default group, which can't be deleted, is created for the new user. The returned API token is never given again, " +
          "and can only be used once the email address has been validated, by following the link sent to it."
      )
      .post
      .in(jsonBody[UserRegisterRequest].example(UserRegisterRequest("john", "john@example.com")))
      .in(operationOrigin)
      .out(jsonBody[UserRegistration].description("the registered user, its default group and its API token"))
      .errorOut(
        failures(
          StatusCode.BadRequest         -> "invalid user name or email address",
          StatusCode.Conflict           -> "email address already used",
          StatusCode.ServiceUnavailable -> "the validation email couldn't be sent, the registration has been cancelled"
        )
      )

  val userGet: Endpoint[String, Unit, ApiFailure, User, Any] =
    meEndpoint
      .name("userGet")
      .summary("Get the current user information")
      .get
      .out(jsonBody[User].description("the user information"))
      .errorOut(failures(unauthorized, notValidated))

  val userTokenRenew: Endpoint[String, Unit, ApiFailure, UserToken, Any] =
    meEndpoint
      .name("userTokenRenew")
      .summary("Replace the current user API token")
      .description("The current token is immediately revoked.")
      .post
      .in("token")
      .out(jsonBody[UserToken].description("the new API token"))
      .errorOut(failures(unauthorized, notValidated))

  val userDelete: Endpoint[String, Unit, ApiFailure, Unit, Any] =
    meEndpoint
      .name("userDelete")
      .summary("Delete the current user, with all its groups and counters")
      .description("The user, all its groups, their counters, states and history are definitively removed.")
      .delete
      .out(statusCode(StatusCode.NoContent).description("the user has been deleted"))
      .errorOut(failures(unauthorized, notValidated))

  // -------------------------------------------------------------------------------------------------------------------
  // groups

  val groupList: Endpoint[String, Unit, ApiFailure, List[CountersGroup], Any] =
    groupsEndpoint
      .name("groupList")
      .summary("List the current user groups, sorted by name")
      .get
      .out(jsonBody[List[CountersGroup]].description("the user groups"))
      .errorOut(failures(unauthorized, notValidated))

  val groupCreate: Endpoint[String, (GroupCreateRequest, OperationOrigin), ApiFailure, CountersGroup, Any] =
    groupsEndpoint
      .name("groupCreate")
      .summary("Create a new group of counters")
      .post
      .in(jsonBody[GroupCreateRequest].example(GroupCreateRequest("my web site", Some("my web site pages counters"))))
      .in(operationOrigin)
      .out(jsonBody[CountersGroup].description("the created group"))
      .errorOut(failures(unauthorized, notValidated))

  val groupGet: Endpoint[String, UUID, ApiFailure, CountersGroup, Any] =
    groupsEndpoint
      .name("groupGet")
      .summary("Get a group of counters information")
      .get
      .in(groupId)
      .out(jsonBody[CountersGroup].description("the group information"))
      .errorOut(failures(unauthorized, notValidated, groupNotFound))

  val groupUpdate: Endpoint[String, (UUID, GroupUpdateRequest), ApiFailure, CountersGroup, Any] =
    groupsEndpoint
      .name("groupUpdate")
      .summary("Update a group of counters")
      .description("All editable fields are replaced, an optional field which is not provided is removed.")
      .put
      .in(groupId)
      .in(jsonBody[GroupUpdateRequest].example(GroupUpdateRequest("my web site", Some("my web site pages counters"))))
      .out(jsonBody[CountersGroup].description("the updated group"))
      .errorOut(failures(unauthorized, notValidated, groupNotFound))

  val groupDelete: Endpoint[String, UUID, ApiFailure, Unit, Any] =
    groupsEndpoint
      .name("groupDelete")
      .summary("Delete a group of counters, with all its counters")
      .description("The group, all its counters, their states and history are definitively removed. The user default group can't be deleted.")
      .delete
      .in(groupId)
      .out(statusCode(StatusCode.NoContent).description("the group has been deleted"))
      .errorOut(failures(unauthorized, notValidated, groupNotFound, StatusCode.Conflict -> "the user default group can't be deleted"))

  // -------------------------------------------------------------------------------------------------------------------
  // counters

  val groupCounters: Endpoint[String, UUID, ApiFailure, List[Counter], Any] =
    countersEndpoint
      .name("groupCounters")
      .summary("List the counters of a group, sorted by name")
      .get
      .in(groupId / "counter")
      .out(jsonBody[List[Counter]].description("the group counters"))
      .errorOut(failures(unauthorized, notValidated, groupNotFound))

  val counterCreate: Endpoint[String, (UUID, CounterCreateRequest, OperationOrigin), ApiFailure, Counter, Any] =
    countersEndpoint
      .name("counterCreate")
      .summary("Create a new counter within the given group")
      .description("The counter starts at 0.")
      .post
      .in(groupId / "counter")
      .in(
        jsonBody[CounterCreateRequest]
          .example(CounterCreateRequest("home page", Some("home page visits"), Some(java.net.URI("https://example.com/").toURL), Some(false)))
      )
      .in(operationOrigin)
      .out(jsonBody[Counter].description("the created counter"))
      .errorOut(failures(unauthorized, notValidated, groupNotFound))

  val counterGet: Endpoint[String, (UUID, UUID), ApiFailure, Counter, Any] =
    countersEndpoint
      .name("counterGet")
      .summary("Get a counter information")
      .get
      .in(groupId / "counter" / counterId)
      .out(jsonBody[Counter].description("the counter information"))
      .errorOut(failures(unauthorized, notValidated, counterNotFound))

  val counterUpdate: Endpoint[String, (UUID, UUID, CounterUpdateRequest), ApiFailure, Counter, Any] =
    countersEndpoint
      .name("counterUpdate")
      .summary("Update a counter")
      .description("All editable fields are replaced, an optional field which is not provided is removed. The counter value is kept unchanged.")
      .put
      .in(groupId / "counter" / counterId)
      .in(jsonBody[CounterUpdateRequest].example(CounterUpdateRequest("home page", Some("home page visits"), None, Some(true))))
      .out(jsonBody[Counter].description("the updated counter"))
      .errorOut(failures(unauthorized, notValidated, counterNotFound))

  val counterDelete: Endpoint[String, (UUID, UUID), ApiFailure, Unit, Any] =
    countersEndpoint
      .name("counterDelete")
      .summary("Delete a counter, its state and history")
      .delete
      .in(groupId / "counter" / counterId)
      .out(statusCode(StatusCode.NoContent).description("the counter has been deleted"))
      .errorOut(failures(unauthorized, notValidated, counterNotFound))

  val counterState: Endpoint[String, (UUID, UUID), ApiFailure, CounterState, Any] =
    countersEndpoint
      .name("counterState")
      .summary("Get a counter current state")
      .description("The state contains the counter value, its last update origin, the counter and its group.")
      .get
      .in(groupId / "counter" / counterId / "state")
      .out(jsonBody[CounterState].description("the counter current state"))
      .errorOut(failures(unauthorized, notValidated, counterNotFound))

  val historyMaxLimit = 1000

  val counterHistory: Endpoint[String, (UUID, UUID, Int), ApiFailure, List[CounterHistoryEntry], Any] =
    countersEndpoint
      .name("counterHistory")
      .summary("Get a counter increments history, most recent first")
      .description("Each increment, done through the API or the count page, is recorded with its origin.")
      .get
      .in(groupId / "counter" / counterId / "history")
      .in(
        query[Int]("limit")
          .description("Maximum number of returned increments")
          .default(100)
          .validate(Validator.inRange(1, historyMaxLimit))
      )
      .out(jsonBody[List[CounterHistoryEntry]].description("the most recent increments"))
      .errorOut(failures(unauthorized, notValidated, counterNotFound))

  val counterIncrement: Endpoint[Option[String], (UUID, UUID, OperationOrigin), ApiFailure, CounterValue, Any] =
    endpoint
      .tag(Tags.counters)
      .securityIn(
        auth
          .bearer[Option[String]]()
          .securitySchemeName(apiTokenSchemeName)
          .description(apiTokenDescription)
      )
      .name("counterIncrement")
      .summary("Increment a counter")
      .description(
        "Increments the counter by one and returns its new value. The API token of the counter owner is required, " +
          "unless the counter `publicIncrement` is enabled. The counter `redirect` URL is not used here, " +
          "browsers should use the count page `GET /{groupId}/count/{counterId}` instead."
      )
      .post
      .in("api" / "group" / groupId / "counter" / counterId / "increment")
      .in(operationOrigin)
      .out(jsonBody[CounterValue].description("the counter value after the increment"))
      .errorOut(failures(StatusCode.Unauthorized -> "invalid API token, or missing API token while the counter increment is not public", notValidated, counterNotFound))

  // -------------------------------------------------------------------------------------------------------------------
  // pages

  val countPage: PublicEndpoint[(UUID, UUID, OperationOrigin), String, String, Any] =
    pagesEndpoint
      .name("countPage")
      .summary("Increment a counter and redirect the browser")
      .description(
        """Designed to be used as a link target, it is always public. Increments the counter by one, then redirects the browser :
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
      .description("Always public.")
      .get
      .in(groupId / "state" / counterId)
      .out(htmlBodyUtf8.description("html page showing the counter current value"))
      .errorOut(statusCode(StatusCode.NotFound).description("group or counter not found").and(stringBody))

  private val validationCode = query[String]("code").description("Validation code, as given in the validation link sent by email")

  val emailValidationPage: PublicEndpoint[String, Unit, String, Any] =
    pagesEndpoint
      .name("emailValidationPage")
      .summary("Email validation page")
      .description(
        "The page the emailed validation link leads to, it only asks for a confirmation and validates nothing, so that a link " +
          "opened by a mail scanner can't validate an email address on its own."
      )
      .get
      .in("user" / "validate")
      .in(validationCode)
      .out(htmlBodyUtf8.description("html page asking to confirm the email address"))

  val emailValidation: PublicEndpoint[String, Unit, String, Any] =
    pagesEndpoint
      .name("emailValidation")
      .summary("Validate an email address")
      .description("Sent by the validation page confirmation button. Once validated, the user API token can be used.")
      .post
      .in("user" / "validate")
      .in(validationCode)
      .out(htmlBodyUtf8.description("html page telling whether the email address has been validated, or why not"))

  // -------------------------------------------------------------------------------------------------------------------

  val all: List[AnyEndpoint] = List(
    userRegister,
    userGet,
    userTokenRenew,
    userDelete,
    groupList,
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
    counterHistory,
    counterIncrement,
    countPage,
    statePage,
    emailValidationPage,
    emailValidation,
    info,
    health
  )
}
