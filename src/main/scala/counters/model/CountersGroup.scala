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

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import counters.tools.JsonCodecs.given
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description

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

case class GroupUpdateInputs(
  name: String,
  description: Option[String]
)

@description("A group of counters")
case class CountersGroup(
  @description("Group unique identifier")
  id: UUID,
  @description("Unique identifier of the user who owns this group")
  ownerId: UUID,
  @description("Group name")
  name: String,
  @description("Group description")
  description: Option[String],
  @description("Group creation origin")
  origin: Option[OperationOrigin]
) extends CountersGroupRequirements derives Schema

object CountersGroup {
  given JsonValueCodec[CountersGroup]       = JsonCodecMaker.make
  given JsonValueCodec[List[CountersGroup]] = JsonCodecMaker.make
}
