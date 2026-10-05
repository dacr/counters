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

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import sttp.model.StatusCode
import sttp.tapir.Schema
import counters.tools.JsonCodecs.given
import sttp.tapir.Schema.annotations.description

import java.net.URL
import java.time.{Instant, OffsetDateTime}
import java.util.UUID

@description("Service health status")
case class Health(
  @description("Always true when the service is able to respond")
  alive: Boolean = true,
  @description("Health status description")
  description: String = "alive"
) derives Schema

object Health {
  given JsonValueCodec[Health] = JsonCodecMaker.make(CodecMakerConfig.withTransientDefault(false))
}

@description("General information about the service")
case class ServiceInfo(
  @description("This instance unique identifier, always updated on (re)start")
  instanceUUID: UUID,
  @description("Last date time when this instance has been (re)started")
  startedOn: OffsetDateTime,
  @description("Software version")
  version: String,
  @description("Software build date")
  buildDate: Option[String]
) derives Schema

object ServiceInfo {
  given JsonValueCodec[ServiceInfo] = JsonCodecMaker.make
}

@description("Error details")
case class ApiError(
  @description("Error message")
  message: String
) derives Schema

object ApiError {
  given JsonValueCodec[ApiError] = JsonCodecMaker.make
}

/** An error with its HTTP status, the status is not part of the response body */
case class ApiFailure(status: StatusCode, error: ApiError)

object ApiFailure {
  def notFound(message: String): ApiFailure     = ApiFailure(StatusCode.NotFound, ApiError(message))
  def unauthorized(message: String): ApiFailure = ApiFailure(StatusCode.Unauthorized, ApiError(message))
  def conflict(message: String): ApiFailure     = ApiFailure(StatusCode.Conflict, ApiError(message))
  def forbidden(message: String): ApiFailure    = ApiFailure(StatusCode.Forbidden, ApiError(message))
  def badRequest(message: String): ApiFailure   = ApiFailure(StatusCode.BadRequest, ApiError(message))
  def unavailable(message: String): ApiFailure  = ApiFailure(StatusCode.ServiceUnavailable, ApiError(message))
}

@description("User registration request")
case class UserRegisterRequest(
  @description("User name")
  name: String,
  @description("User email address, a validation link is sent to it, the API token can't be used until the link has been followed")
  email: String
) derives Schema

object UserRegisterRequest {
  given JsonValueCodec[UserRegisterRequest] = JsonCodecMaker.make
}

@description("A new user API token")
case class UserToken(
  @description("The user API token, to be sent as a bearer token : Authorization: Bearer <token>")
  token: String
) derives Schema

object UserToken {
  given JsonValueCodec[UserToken] = JsonCodecMaker.make
}

@description("Counters group creation request")
case class GroupCreateRequest(
  @description("Group name")
  name: String,
  @description("Group description")
  description: Option[String]
) derives Schema

object GroupCreateRequest {
  given JsonValueCodec[GroupCreateRequest] = JsonCodecMaker.make
}

@description("Counter creation request")
case class CounterCreateRequest(
  @description("Counter name")
  name: String,
  @description("Counter description")
  description: Option[String],
  @description(
    "URL where the count page GET /{groupId}/count/{counterId} redirects the browser after each increment, with the count, groupId, counterId and stateId query parameters appended. Without it the browser is redirected to the counter state page. Not used by the API increment endpoint."
  )
  redirect: Option[URL],
  @description("When true the API increment endpoint can be used without any API token, false by default")
  publicIncrement: Option[Boolean]
) derives Schema

object CounterCreateRequest {
  given JsonValueCodec[CounterCreateRequest] = JsonCodecMaker.make
}

@description("Counter update request, all counter editable fields are replaced")
case class CounterUpdateRequest(
  @description("Counter name")
  name: String,
  @description("Counter description, removed when not provided")
  description: Option[String],
  @description(
    "URL where the count page GET /{groupId}/count/{counterId} redirects the browser after each increment, with the count, groupId, counterId and stateId query parameters appended. Without it the browser is redirected to the counter state page. Not used by the API increment endpoint. Removed when not provided."
  )
  redirect: Option[URL],
  @description("When true the API increment endpoint can be used without any API token, false when not provided")
  publicIncrement: Option[Boolean]
) derives Schema

object CounterUpdateRequest {
  given JsonValueCodec[CounterUpdateRequest] = JsonCodecMaker.make
}

@description("A counter current value")
case class CounterValue(
  @description("Current counter value")
  count: Long,
  @description("When the counter has been last updated")
  lastUpdated: Instant
) derives Schema

object CounterValue {
  given JsonValueCodec[CounterValue] = JsonCodecMaker.make
}

@description("Counters group update request, all group editable fields are replaced")
case class GroupUpdateRequest(
  @description("Group name")
  name: String,
  @description("Group description, removed when not provided")
  description: Option[String]
) derives Schema

object GroupUpdateRequest {
  given JsonValueCodec[GroupUpdateRequest] = JsonCodecMaker.make
}
