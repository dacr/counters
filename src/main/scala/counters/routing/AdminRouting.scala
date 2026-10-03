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
import counters.api.{ApiEndpoints, Health}

import scala.concurrent.Future

case class AdminRouting(dependencies: ServiceDependencies) extends Routing {
  private val alive = Health()

  private val health = ApiEndpoints.health.serverLogicSuccess[Future](_ => Future.successful(alive))

  override def endpoints = List(health)
}
