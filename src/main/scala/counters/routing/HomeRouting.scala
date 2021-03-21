package counters.routing

import akka.http.scaladsl.model.HttpCharsets._
import akka.http.scaladsl.model.{HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import counters.ServiceDependencies
import counters.model.{CounterState, OperationOrigin, ServiceStats}
import counters.tools.Templating
import yamusca.imports._
import yamusca.implicits._

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

  val site = dependencies.config.counters.site
  val pageContext = PageContext(dependencies.config.counters)

  implicit val ec = scala.concurrent.ExecutionContext.global
  implicit val serviceStatsConverter = ValueConverter.deriveConverter[ServiceStats]
  implicit val pageContextConverter = ValueConverter.deriveConverter[PageContext]
  implicit val homeContextConverter = ValueConverter.deriveConverter[HomeContext]
  implicit val stateContextConverter = ValueConverter.deriveConverter[StateContext]

  val templating: Templating = Templating(dependencies.config)
  val homeLayout = (context: Context) => templating.makeTemplateLayout("counters/templates/home.html")(context)
  val stateLayout = (context: Context) => templating.makeTemplateLayout("counters/templates/state.html")(context)

  def increment: Route = {
    path(JavaUUID / "count" / JavaUUID) { (groupId, counterId) =>
      get {
        optionalHeaderValueByName("User-Agent") { agent =>
          extractClientIP { ip =>
            import counters.tools.JsonImplicits._
            import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
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
              val content = stateLayout(stateContext.asContext)
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
          val content = homeLayout(homeContext.asContext)
          val contentType = `text/html` withCharset `UTF-8`
          HttpResponse(entity = HttpEntity(contentType, content), headers = noClientCacheHeaders)
        }
      }
    }
  }


}
