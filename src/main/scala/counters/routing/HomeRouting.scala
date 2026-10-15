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

import counters.{ServiceDependencies, SiteConfig}
import counters.api.ApiEndpoints
import counters.model.{ServiceStats, User}
import org.slf4j.LoggerFactory
import counters.templates.html.{HomeTemplate, StateTemplate, ValidationTemplate}
import sttp.model.StatusCode
import sttp.tapir.*

import scala.concurrent.{ExecutionContext, Future}

case class HomeContext(
  context: PageContext,
  stats: ServiceStats
)

case class StateContext(
  context: PageContext,
  groupName: String,
  groupDescription: String,
  counterName: String,
  counterDescription: String,
  lastUpdated: String,
  count: Long
)

/** A button posting to the given path, relative to the site base */
case class PageAction(label: String, path: String, style: String)

case class ValidationContext(
  context: PageContext,
  title: String,
  message: String,
  details: List[(String, String)],
  actions: List[PageAction]
)

/** Html pages, the count and state pages are documented within the API specification */
case class HomeRouting(dependencies: ServiceDependencies) extends Routing {
  private given ExecutionContext = ExecutionContext.global

  val site: SiteConfig         = dependencies.config.counters.site
  val pageContext: PageContext = PageContext(dependencies.config.counters)

  private val logger = LoggerFactory.getLogger(getClass)
  private val engine = dependencies.engine
  private val mailer = dependencies.mailer
  private val emails = UserEmails(dependencies.config.counters)

  private val notFoundMessage = "group or counter not found"

  private val pageEndpoint =
    endpoint.get
      .out(htmlBodyUtf8)
      .out(header(Routing.noClientCacheHeader))
      .errorOut(statusCode(StatusCode.NotFound).and(stringBody))

  private val increment =
    ApiEndpoints.countPage
      .serverLogic[Future] { (groupId, counterId, origin) =>
        dependencies.engine.counterIncrement(groupId, counterId, Some(origin)).map {
          case Some(state) =>
            state.counter.redirect match {
              case Some(redirect) =>
                val url       = redirect.toString
                val separator = if (url.contains("?")) "&" else "?"
                val query     = s"count=${state.count}&groupId=$groupId&counterId=$counterId&stateId=${state.id}"
                Right(s"$url$separator$query")
              case None           => // no redirect configured, so going back to the default counter state page
                Right(s"${site.baseURL}/$groupId/state/$counterId")
            }
          case None        =>
            Left(notFoundMessage)
        }
      }

  // Quick & dirty hack to avoid any kind of html/javascript injection
  def secureString(input: String): String = {
    input
      .replaceAll("""[^-0-9a-zA-Z_'.,;!:# ]""", "")
      .replaceAll("""\s{2,}""", " ")
  }

  private val state =
    ApiEndpoints.statePage
      .out(header(Routing.noClientCacheHeader))
      .serverLogic[Future] { (groupId, counterId) =>
        dependencies.engine.stateGet(groupId, counterId).map {
          case None        => Left(notFoundMessage)
          case Some(state) =>
            val stateContext = StateContext(
              context = pageContext,
              groupName = secureString(state.group.name),
              groupDescription = secureString(state.group.description.getOrElse("")),
              counterName = secureString(state.counter.name),
              counterDescription = secureString(state.counter.description.getOrElse("")),
              count = state.count,
              lastUpdated = state.lastUpdated.toString
            )
            Right(StateTemplate.render(stateContext).toString)
        }
      }

  private val home =
    pageEndpoint
      .in("") // root path only, an endpoint without any path input matches all paths
      .serverLogic[Future] { _ =>
        dependencies.engine.serviceStatsGet().map { stats =>
          val homeContext = HomeContext(
            context = pageContext,
            stats = stats
          )
          Right(HomeTemplate.render(homeContext).toString)
        }
      }

  private def page(title: String, message: String, details: List[(String, String)] = Nil, actions: List[PageAction] = Nil): String =
    ValidationTemplate.render(ValidationContext(pageContext, title, message, details, actions)).toString

  private def encoded(code: String): String = java.net.URLEncoder.encode(code, "UTF-8")

  private val emailValidationPage =
    ApiEndpoints.emailValidationPage
      .out(header(Routing.noClientCacheHeader))
      .serverLogicSuccess[Future] { code =>
        val confirm = PageAction("Confirm my email address", s"/user/validate?code=${encoded(code)}", "primary")
        Future.successful(page("Email validation", "Please confirm your email address to activate your account.", actions = List(confirm)))
      }

  private val validationFailed = page("Email validation failed", "This validation link is invalid, has expired, or has already been used.")

  /** The email is only validated once the approval request has been sent, otherwise the user can try again with the same link */
  private val emailValidation =
    ApiEndpoints.emailValidation
      .out(header(Routing.noClientCacheHeader))
      .serverLogicSuccess[Future] { code =>
        engine.userEmailValidationPrepare(code).flatMap {
          case None          => Future.successful(validationFailed)
          case Some(request) =>
            mailer
              .send(emails.approvalRequest(request))
              .flatMap { _ =>
                engine.userEmailValidate(code).map {
                  case None       => validationFailed
                  case Some(user) =>
                    page(
                      "Email validated",
                      s"Thank you ${user.name}, your email address has been validated. Your registration is now waiting for an " +
                        "administrator approval, you'll receive an email once approved, your API token can be used from then on."
                    )
                }
              }
              .recover { error =>
                logger.error(s"Unable to send the approval request of user ${request.user.id} : ${error.getMessage}")
                page("Email validation failed", "Your email address couldn't be validated right now, please try again later with the same link.")
              }
        }
      }

  private def userDetails(user: User): List[(String, String)] = {
    val origin = user.origin
    List(
      "Name"          -> user.name,
      "Email"         -> user.email,
      "Email status"  -> (if (user.emailValidated) "validated" else "not validated yet"),
      "User id"       -> user.id.toString,
      "Registered on" -> origin.map(_.timestamp.toString).getOrElse("unknown"),
      "IP address"    -> origin.flatMap(_.ipAddress).getOrElse("unknown"),
      "User agent"    -> origin.flatMap(_.userAgent).getOrElse("unknown"),
      "Referer"       -> origin.flatMap(_.referer).getOrElse("none")
    )
  }

  private val approvalUnknown = page("Registration approval", "This approval link is invalid, or the registration has already been approved or rejected.")

  private val userApprovalPage =
    ApiEndpoints.userApprovalPage
      .out(header(Routing.noClientCacheHeader))
      .serverLogicSuccess[Future] { code =>
        engine.userApprovalGet(code).map {
          case None       => approvalUnknown
          case Some(user) =>
            val path = s"/user/approve?code=${encoded(code)}"
            page(
              "Registration approval",
              "Approve this registration to let the user use its API token, or reject it to drop it with its groups.",
              userDetails(user),
              List(PageAction("Approve", s"$path&decision=approve", "success"), PageAction("Reject", s"$path&decision=reject", "danger"))
            )
        }
      }

  private val userApproval =
    ApiEndpoints.userApproval
      .out(header(Routing.noClientCacheHeader))
      .serverLogicSuccess[Future] {
        case (code, "approve") =>
          engine.userApprove(code).flatMap {
            case None       => Future.successful(approvalUnknown)
            case Some(user) =>
              mailer
                .send(emails.approved(user))
                .map(_ => page("Registration approved", s"${user.name} <${user.email}> has been approved and notified by email."))
                .recover { error =>
                  logger.error(s"Unable to notify user ${user.id} of its approval : ${error.getMessage}")
                  page("Registration approved", s"${user.name} <${user.email}> has been approved, but couldn't be notified by email.")
                }
          }
        case (code, _)         =>
          engine.userReject(code).map {
            case None       => approvalUnknown
            case Some(user) => page("Registration rejected", s"${user.name} <${user.email}> registration has been rejected and dropped.")
          }
      }

  override def endpoints = List(increment, state, emailValidationPage, emailValidation, userApprovalPage, userApproval, home)
}
