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
package counters.model

import java.util.UUID

trait CountersGroupRequirements {
  def name: String
  def description: Option[String]
  def origin: Option[OperationOrigin]
}

case class CountersGroupCreateInputs(
  name: String,
  description: Option[String],
  origin: Option[OperationOrigin]
) extends CountersGroupRequirements

case class CountersGroup(
  id: UUID,
  name: String,
  description: Option[String],
  origin: Option[OperationOrigin]
) extends CountersGroupRequirements
