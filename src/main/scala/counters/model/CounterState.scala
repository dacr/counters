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

import java.time.Instant
import java.util.UUID

@description("A counter state, a new state is created on each counter increment")
case class CounterState(
  @description("State unique identifier")
  id: UUID,
  @description("The group the counter belongs to")
  group: CountersGroup,
  @description("The counter")
  counter: Counter,
  @description("Current counter value")
  count: Long,
  @description("When the counter has been last updated")
  lastUpdated: Instant,
  @description("Origin of the last counter update")
  lastOrigin: Option[OperationOrigin]
) derives Schema

object CounterState {
  given JsonValueCodec[CounterState] = JsonCodecMaker.make
}
