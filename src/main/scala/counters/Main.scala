/*
 * Copyright 2020-2022 David Crosson
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
package counters

import ch.qos.logback.classic.util.ContextInitializer
import org.slf4j.Logger

object Main {
  System.setProperty(ContextInitializer.CONFIG_FILE_PROPERTY, "counters/logback.xml")

  def start(): Service = {
    val logger: Logger = org.slf4j.LoggerFactory.getLogger("CountersMain")
    logger.info(s"Counters application is starting")
    val dependencies = ServiceDependencies.defaults
    val serviceRoutes = ServiceRoutes(dependencies)
    Service(dependencies, serviceRoutes)
  }

  def main(args: Array[String]): Unit = {
    start()
  }
}
