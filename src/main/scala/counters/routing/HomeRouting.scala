package counters.routing

import akka.http.scaladsl.model.HttpCharsets._
import akka.http.scaladsl.model.{HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import counters.ServiceDependencies
import counters.model.OperationOrigin
import counters.tools.Templating
import yamusca.imports._
import yamusca.implicits._

case class HomeContext(
  context: PageContext
)

case class HomeRouting(dependencies: ServiceDependencies) extends Routing {
  override def routes: Route = concat(increment, home)

  val site = dependencies.config.counters.site
  val pageContext = PageContext(dependencies.config.counters)

  implicit val ec = scala.concurrent.ExecutionContext.global
  implicit val pageContextConverter = ValueConverter.deriveConverter[PageContext]
  implicit val homeContextConverter = ValueConverter.deriveConverter[HomeContext]

  val templating: Templating = Templating(dependencies.config)
  val homeLayout = (context: Context) => templating.makeTemplateLayout("counters/templates/home.html")(context)

  def increment:Route = {
    path(JavaUUID / "count" / JavaUUID) { (groupId, counterId) =>
      get {
        optionalHeaderValueByName("User-Agent") { agent =>
          extractClientIP { ip =>
            import counters.tools.JsonImplicits._
            import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
            val origin = OperationOrigin(ip.toOption.map(_.getHostAddress),agent)
            onSuccess(dependencies.engine.counterIncrement(groupId, counterId, Some(origin))) {
              case Some(state) if state.counter.redirect.isDefined =>
                val url = state.counter.redirect.get.toString
                val query=s"?count=${state.count}&groupId=$groupId&counterId=$counterId&stateId=${state.id}"
                redirect(s"$url?$query", StatusCodes.TemporaryRedirect)
              case Some(state) => // no redirect configured, so going back to homepage
                redirect(site.baseURL, StatusCodes.TemporaryRedirect)
              case None =>
                complete(StatusCodes.NotFound -> "group or counter not found")
            }
          }
        }
      }
    }
  }

  def home: Route = pathEndOrSingleSlash {
    get {
      complete {
        val homeContext = HomeContext(
          context = pageContext
        )
        val content = homeLayout(homeContext.asContext)
        val contentType = `text/html` withCharset `UTF-8`
        HttpResponse(entity = HttpEntity(contentType, content), headers = noClientCacheHeaders)
      }
    }
  }
}
