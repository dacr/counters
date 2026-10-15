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
package counters.model

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import counters.tools.JsonCodecs.given
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description

import java.time.Instant
import java.util.UUID

case class UserCreateInputs(
  name: String,
  email: String,
  origin: Option[OperationOrigin]
)

@description("A user, owner of groups of counters")
case class User(
  @description("User unique identifier")
  id: UUID,
  @description("User name")
  name: String,
  @description("User email address")
  email: String,
  @description("False until the email address has been validated, the API token can't be used before")
  emailValidated: Boolean,
  @description("False until an administrator has approved the registration, the API token can't be used before")
  approved: Boolean = true, // users registered before approvals existed are approved
  @description("The group created on registration, it can't be deleted")
  defaultGroupId: UUID,
  @description("User registration origin")
  origin: Option[OperationOrigin]
) derives Schema

object User {
  given JsonValueCodec[User] = JsonCodecMaker.make(CodecMakerConfig.withTransientDefault(false))
}

/** A user as stored, the API token, the email validation and approval codes are never stored, only their hashes */
case class UserAccount(
  user: User,
  tokenHash: String,
  validationCodeHash: Option[String],
  validationExpiresOn: Option[Instant],
  approvalCodeHash: Option[String] = None
)

object UserAccount {
  given JsonValueCodec[UserAccount] = JsonCodecMaker.make(CodecMakerConfig.withTransientDefault(false))
}

@description("A newly registered user")
case class UserRegistration(
  @description("The registered user")
  user: User,
  @description("The user default group")
  defaultGroup: CountersGroup,
  @description("The user API token, given only once, to be sent as a bearer token : Authorization: Bearer <token>. It can only be used once the email address has been validated.")
  token: String
) derives Schema

object UserRegistration {
  given JsonValueCodec[UserRegistration] = JsonCodecMaker.make
}

/** A registration waiting for its email validation, the validation code is only known here */
case class UserRegistered(
  registration: UserRegistration,
  validationCode: String,
  validationExpiresOn: Instant
)

/** An email validation waiting for the approval request to be sent, the approval code is only known here */
case class UserApprovalRequest(
  user: User,
  approvalCode: String
)
