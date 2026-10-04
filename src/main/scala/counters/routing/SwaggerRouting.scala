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
import counters.api.ApiEndpoints
import io.circe.Printer
import io.circe.syntax.*
import sttp.apispec.Tag
import sttp.apispec.openapi.{Contact, Info, License, OpenAPI}
import sttp.apispec.openapi.circe.*
import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.swagger.{SwaggerUI, SwaggerUIOptions}

import scala.concurrent.Future

/** Exposes the OpenAPI specification generated from the API endpoints definitions, and the swagger user interface */
case class SwaggerRouting(dependencies: ServiceDependencies) extends Routing {
  private val config = dependencies.config.counters
  private val site   = config.site

  val openAPI: OpenAPI =
    OpenAPIDocsInterpreter()
      .toOpenAPI(
        ApiEndpoints.all,
        Info(
          title = s"${config.application.name} API",
          version = config.metaInfo.version,
          description = Some(ApiEndpoints.description),
          termsOfService = Some(s"${site.baseURL}/txt/TERMS-OF-SERVICE.txt"),
          contact = Some(Contact(email = Some(config.metaInfo.contact), url = Some(config.metaInfo.projectURL))),
          license = Some(License("Apache 2.0", Some(s"${site.baseURL}/txt/LICENSE-2.0.txt")))
        )
      )
      .addServer(site.baseURL)
      .tags(ApiEndpoints.Tags.descriptions.map((name, description) => Tag(name, Some(description))))

  private val swaggerUIEndpoints =
    SwaggerUI[Future](openAPI.toYaml, SwaggerUIOptions.default.pathPrefix(List("swagger")))

  // kept for backward compatibility, the swagger user interface uses the yaml specification
  private val swaggerJson = Printer.spaces2.print(openAPI.asJson)

  private val swaggerJsonEndpoint =
    endpoint.get
      .in("swagger" / "swagger.json")
      .out(stringBodyUtf8AnyFormat(Codec.string.format(CodecFormat.Json())))
      .out(header(Routing.noClientCacheHeader))
      .serverLogicSuccess[Future](_ => Future.successful(swaggerJson))

  override def endpoints = swaggerJsonEndpoint :: swaggerUIEndpoints
}
