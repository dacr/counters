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
import org.webjars.WebJarAssetLocator
import sttp.tapir.*
import sttp.tapir.files.*

import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

case class AssetsRouting(dependencies: ServiceDependencies) extends Routing {
  private val classLoader = getClass.getClassLoader

  private val staticResourcesSubDirectories = List("js", "css", "images", "fonts", "pdf", "txt")

  private val staticEndpoints = staticResourcesSubDirectories.map { resourceDirectory =>
    staticResourcesGetServerEndpoint[Future](resourceDirectory)(
      classLoader,
      s"counters/static-content/$resourceDirectory",
      extraHeaders = List(Routing.clientCacheHeader)
    )
  }

  // webjars are exposed without their version : /assets/<webjar>/<path>
  private val webjarsEndpoints = new WebJarAssetLocator().getWebJars.asScala.toList.sortBy(_._1).map { (webjar, version) =>
    staticResourcesGetServerEndpoint[Future]("assets" / webjar)(
      classLoader,
      s"META-INF/resources/webjars/$webjar/$version",
      extraHeaders = List(Routing.clientCacheHeader)
    )
  }

  override def endpoints = webjarsEndpoints ++ staticEndpoints
}
