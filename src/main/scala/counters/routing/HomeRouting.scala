package counters.routing

import org.apache.pekko.http.scaladsl.model.HttpCharsets._
import org.apache.pekko.http.scaladsl.model.{HttpEntity, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.model.MediaTypes.`text/html`
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import counters.{ServiceDependencies, SiteConfig}
import counters.model.{CounterState, OperationOrigin, ServiceStats}
import counters.templates.html.{HomeTemplate, StateTemplate}

import scala.concurrent.ExecutionContextExecutor

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

case class HomeRouting(dependencies: ServiceDependencies) extends Routing {
  override def routes: Route = concat(increment, home, state)

  val site: SiteConfig = dependencies.config.counters.site
  val pageContext: PageContext = PageContext(dependencies.config.counters)

  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global


  def increment: Route = {
    path(JavaUUID / "count" / JavaUUID) { (groupId, counterId) =>
      get {
        optionalHeaderValueByName("User-Agent") { agent =>
          extractClientIP { ip =>
            import counters.tools.JsonImplicits._
            import com.github.pjfanning.pekkohttpjson4s.Json4sSupport._
            val origin = OperationOrigin(ip.toOption.map(_.getHostAddress), agent)
            onSuccess(dependencies.engine.counterIncrement(groupId, counterId, Some(origin))) {
              case Some(state) if state.counter.redirect.isDefined =>
                val url = state.counter.redirect.get.toString
                val query = s"?count=${state.count}&groupId=$groupId&counterId=$counterId&stateId=${state.id}"
                redirect(s"$url?$query", StatusCodes.TemporaryRedirect)
              case Some(state) => // no redirect configured, so going back to the default counter state page
                val uri = s"${site.baseURL}/$groupId/state/$counterId"
                redirect(uri, StatusCodes.TemporaryRedirect)
              case None =>
                complete(StatusCodes.NotFound -> "group or counter not found")
            }
          }
        }
      }
    }
  }

  // Quick & dirty hack to avoid any kind of html/javascript injection
  def secureString(input:String):String = {
    input
      .replaceAll("""[^-0-9a-zA-Z_'.,;!:# ]""", "")
      .replaceAll("""\s{2,}""", " ")
  }

  def state: Route = {
    path(JavaUUID / "state" / JavaUUID) { (groupId, counterId) =>
      get {
        onSuccess(dependencies.engine.stateGet(groupId, counterId)) {
          case None => complete(StatusCodes.NotFound -> "group or counter not found")
          case Some(state) =>
            complete {
              val stateContext = StateContext(
                context = pageContext,
                groupName = secureString(state.group.name),
                groupDescription = secureString(state.group.description.getOrElse("")),
                counterName = secureString(state.counter.name),
                counterDescription = secureString(state.counter.description.getOrElse("")),
                count = state.count,
                lastUpdated = state.lastUpdated.toString
              )
              val content = StateTemplate.render(stateContext).toString
              val contentType = `text/html` withCharset `UTF-8`
              HttpResponse(entity = HttpEntity(contentType, content), headers = noClientCacheHeaders)
            }
        }
      }
    }
  }


  def home: Route = pathEndOrSingleSlash {
    get {
      onSuccess(dependencies.engine.serviceStatsGet()) { stats =>
        complete {
          val homeContext = HomeContext(
            context = pageContext,
            stats = stats
          )
          val content = HomeTemplate.render(homeContext).toString()
          val contentType = `text/html` withCharset `UTF-8`
          HttpResponse(entity = HttpEntity(contentType, content), headers = noClientCacheHeaders)
        }
      }
    }
  }


}
