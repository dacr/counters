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
import counters.api.{ApiEndpoints, ApiFailure, CounterValue, ServiceInfo, UserToken}
import counters.dependencies.countersengine.GroupDeleteOutcome
import counters.dependencies.mailer.Email
import counters.model.*
import counters.tools.DateTimeTools
import org.slf4j.LoggerFactory

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

case class CountersRouting(dependencies: ServiceDependencies) extends Routing with DateTimeTools {
  private given ExecutionContext = ExecutionContext.global

  private val logger = LoggerFactory.getLogger(getClass)
  private val engine = dependencies.engine
  private val mailer = dependencies.mailer
  private val meta   = dependencies.config.counters.metaInfo
  private val site   = dependencies.config.counters.site

  private val serviceInfo = ServiceInfo(
    instanceUUID = UUID.randomUUID(),
    startedOn = epochToUTCDateTime(now()),
    version = meta.version,
    buildDate = meta.buildDateTime
  )

  private val unauthorized         = ApiFailure.unauthorized("missing or invalid API token")
  private val notValidated         = ApiFailure.forbidden("the user email address has not been validated yet, follow the link sent to it")
  private val notPublicIncrement   = ApiFailure.unauthorized("an API token is required to increment this counter")
  private val notFound             = ApiFailure.notFound("group or counter not found")
  private val groupNotFound        = ApiFailure.notFound("group not found")
  private val defaultGroupDeletion = ApiFailure.conflict("the user default group can't be deleted")

  private def authenticate(token: String): Future[Either[ApiFailure, User]] =
    engine.userAuthenticate(token).map {
      case None                              => Left(unauthorized)
      case Some(user) if !user.emailValidated => Left(notValidated)
      case Some(user)                        => Right(user)
    }

  private def found(deleted: Boolean, failure: ApiFailure): Either[ApiFailure, Unit] =
    if (deleted) Right(()) else Left(failure)

  private val info = ApiEndpoints.info.serverLogicSuccess[Future](_ => Future.successful(serviceInfo))

  // -------------------------------------------------------------------------------------------------------------------
  // users

  private val emailPattern = """^[^@\s]+@[^@\s]+\.[^@\s]+$""".r

  private def registerInputsCheck(name: String, email: String): Option[ApiFailure] = {
    if (name.trim.isEmpty || name.length > 100) Some(ApiFailure.badRequest("the user name must be given, 100 characters maximum"))
    else if (email.length > 254 || emailPattern.findFirstIn(email.trim).isEmpty) Some(ApiFailure.badRequest("invalid email address"))
    else None
  }

  private def validationEmail(registered: UserRegistered): Email = {
    val user      = registered.registration.user
    val link      = s"${site.baseURL}/user/validate?code=${registered.validationCode}"
    val expiresOn = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC).format(registered.validationExpiresOn)
    Email(
      to = user.email,
      subject = "Counters - confirm your email address",
      body = s"""Hello ${user.name},
                |
                |Thank you for registering to Counters, ${site.baseURL}
                |
                |To activate your account, open the link below and confirm your email address :
                |$link
                |
                |This link expires on $expiresOn UTC, your API token can't be used until then.
                |If you didn't register, just ignore this email, the registration will be dropped.
                |
                |--
                |Counters
                |""".stripMargin
    )
  }

  /** The registration is cancelled when the validation email can't be sent, otherwise the email address would stay blocked */
  private val userRegister = ApiEndpoints.userRegister.serverLogic[Future] { (request, origin) =>
    registerInputsCheck(request.name, request.email) match {
      case Some(failure) => Future.successful(Left(failure))
      case None          =>
        engine.userRegister(UserCreateInputs(request.name.trim, request.email, Some(origin))).flatMap {
          case None             => Future.successful(Left(ApiFailure.conflict("email address already used")))
          case Some(registered) =>
            val userId = registered.registration.user.id
            mailer
              .send(validationEmail(registered))
              .map(_ => Right(registered.registration))
              .recoverWith { error =>
                logger.error(s"Unable to send the validation email of user $userId, registration cancelled : ${error.getMessage}")
                engine.userDelete(userId).map(_ => Left(ApiFailure.unavailable("the validation email couldn't be sent, try again later")))
              }
        }
    }
  }

  private val userGet = ApiEndpoints.userGet
    .serverSecurityLogic[User, Future](authenticate)
    .serverLogicSuccess(user => _ => Future.successful(user))

  private val userTokenRenew = ApiEndpoints.userTokenRenew
    .serverSecurityLogic[User, Future](authenticate)
    .serverLogic(user => _ => engine.userTokenRenew(user.id).map(_.map(UserToken(_)).toRight(unauthorized)))

  private val userDelete = ApiEndpoints.userDelete
    .serverSecurityLogic[User, Future](authenticate)
    .serverLogic(user => _ => engine.userDelete(user.id).map(found(_, unauthorized)))

  // -------------------------------------------------------------------------------------------------------------------
  // groups

  private val groupList = ApiEndpoints.groupList
    .serverSecurityLogic[User, Future](authenticate)
    .serverLogic(user => _ => engine.userGroups(user.id).map(_.toRight(unauthorized)))

  private val groupCreate = ApiEndpoints.groupCreate
    .serverSecurityLogic[User, Future](authenticate)
    .serverLogic { user => (request, origin) =>
      engine
        .groupCreate(user.id, CountersGroupCreateInputs(request.name, request.description, Some(origin)))
        .map(_.toRight(unauthorized))
    }

  private val groupGet = ApiEndpoints.groupGet
    .serverSecurityLogic[User, Future](authenticate)
    .serverLogic(user => groupId => engine.groupGet(user.id, groupId).map(_.toRight(groupNotFound)))

  private val groupUpdate = ApiEndpoints.groupUpdate
    .serverSecurityLogic[User, Future](authenticate)
    .serverLogic { user => (groupId, request) =>
      engine
        .groupUpdate(user.id, groupId, GroupUpdateInputs(request.name, request.description))
        .map(_.toRight(groupNotFound))
    }

  private val groupDelete = ApiEndpoints.groupDelete
    .serverSecurityLogic[User, Future](authenticate)
    .serverLogic { user => groupId =>
      engine.groupDelete(user.id, groupId).map {
        case GroupDeleteOutcome.Deleted      => Right(())
        case GroupDeleteOutcome.NotFound     => Left(groupNotFound)
        case GroupDeleteOutcome.DefaultGroup => Left(defaultGroupDeletion)
      }
    }

  // -------------------------------------------------------------------------------------------------------------------
  // counters

  private val groupCounters = ApiEndpoints.groupCounters
    .serverSecurityLogic[User, Future](authenticate)
    .serverLogic(user => groupId => engine.groupCounters(user.id, groupId).map(_.toRight(groupNotFound)))

  private val counterCreate = ApiEndpoints.counterCreate
    .serverSecurityLogic[User, Future](authenticate)
    .serverLogic { user => (groupId, request, origin) =>
      val inputs = CounterCreateInputs(request.name, request.description, request.redirect, request.publicIncrement.getOrElse(false), Some(origin))
      engine.counterCreate(user.id, groupId, inputs).map(_.toRight(groupNotFound))
    }

  private val counterGet = ApiEndpoints.counterGet
    .serverSecurityLogic[User, Future](authenticate)
    .serverLogic(user => (groupId, counterId) => engine.counterGet(user.id, groupId, counterId).map(_.toRight(notFound)))

  private val counterUpdate = ApiEndpoints.counterUpdate
    .serverSecurityLogic[User, Future](authenticate)
    .serverLogic { user => (groupId, counterId, request) =>
      val inputs = CounterUpdateInputs(request.name, request.description, request.redirect, request.publicIncrement.getOrElse(false))
      engine.counterUpdate(user.id, groupId, counterId, inputs).map(_.toRight(notFound))
    }

  private val counterDelete = ApiEndpoints.counterDelete
    .serverSecurityLogic[User, Future](authenticate)
    .serverLogic(user => (groupId, counterId) => engine.counterDelete(user.id, groupId, counterId).map(found(_, notFound)))

  private val counterState = ApiEndpoints.counterState
    .serverSecurityLogic[User, Future](authenticate)
    .serverLogic(user => (groupId, counterId) => engine.counterState(user.id, groupId, counterId).map(_.toRight(notFound)))

  private val counterHistory = ApiEndpoints.counterHistory
    .serverSecurityLogic[User, Future](authenticate)
    .serverLogic { user => (groupId, counterId, limit) =>
      engine.counterHistory(user.id, groupId, counterId, limit).map(_.toRight(notFound))
    }

  /** Without any token the counter must be public, with a token the counter must belong to the authenticated user */
  private val counterIncrement = ApiEndpoints.counterIncrement
    .serverSecurityLogic[Option[User], Future] {
      case None        => Future.successful(Right(None))
      case Some(token) => authenticate(token).map(_.map(Some(_)))
    }
    .serverLogic { user => (groupId, counterId, origin) =>
      engine.stateGet(groupId, counterId).flatMap {
        case None                                                          => Future.successful(Left(notFound))
        case Some(state) if user.exists(_.id != state.group.ownerId)       => Future.successful(Left(notFound))
        case Some(state) if user.isEmpty && !state.counter.publicIncrement => Future.successful(Left(notPublicIncrement))
        case Some(_)                                                       =>
          engine
            .counterIncrement(groupId, counterId, Some(origin))
            .map(_.map(state => CounterValue(state.count, state.lastUpdated)).toRight(notFound))
      }
    }

  override def endpoints =
    List(
      info,
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
      counterIncrement
    )
}
