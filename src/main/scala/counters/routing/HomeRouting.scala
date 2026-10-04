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
import counters.model.ServiceStats
import counters.templates.html.{HomeTemplate, StateTemplate}
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

/** Html pages, the count and state pages are documented within the API specification */
case class HomeRouting(dependencies: ServiceDependencies) extends Routing {
  private given ExecutionContext = ExecutionContext.global

  val site: SiteConfig         = dependencies.config.counters.site
  val pageContext: PageContext = PageContext(dependencies.config.counters)

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

  override def endpoints = List(increment, state, home)
}
