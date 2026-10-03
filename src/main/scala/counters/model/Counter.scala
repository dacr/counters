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

import java.net.URL
import java.util.UUID

trait CounterRequirements {
  def name: String
  def description: Option[String]
  def redirect: Option[URL]
  def origin: Option[OperationOrigin]
}

case class CounterCreateInputs(
  name: String,
  description: Option[String],
  redirect: Option[URL],
  origin: Option[OperationOrigin]
) extends CounterRequirements

@description("A counter, always part of a group of counters")
case class Counter(
  @description("Counter unique identifier")
  id: UUID,
  @description("Unique identifier of the group this counter belongs to")
  groupId: UUID,
  @description("Counter name")
  name: String,
  @description("Counter description")
  description: Option[String],
  @description("Where to redirect the browser once the counter has been incremented through the count page")
  redirect: Option[URL],
  @description("Counter creation origin")
  origin: Option[OperationOrigin]
) extends CounterRequirements
    derives Schema

object Counter {
  given JsonValueCodec[Counter] = JsonCodecMaker.make
}
