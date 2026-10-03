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
package counters

import counters.dependencies.countersengine.{NopCounterStorage, StandardCountersEngine}
import counters.model.{CounterCreateInputs, CountersGroup, CountersGroupCreateInputs}
import org.scalatest._
import org.scalatest.matchers.should
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.OptionValues._

import java.util.UUID

class StandardCountersEngineTest extends AsyncWordSpec with should.Matchers{
  val config = ServiceConfig()
  val storage = new NopCounterStorage(config)
  "Counters" can {
    "groups" should {
      "be created" in {
        val engine = new StandardCountersEngine(config, storage)
        val groupInputs = CountersGroupCreateInputs("truc",None,None)
        engine
          .groupCreate(groupInputs)
          .map{group =>group.name shouldBe groupInputs.name}
          .andThen(_ => engine.shutdown())
      }
    }
    "Counters" should {
      "be created" in {
        val engine = new StandardCountersEngine(config, storage)
        val groupInputs = CountersGroupCreateInputs("truc",None,None)
        def counterInputs = CounterCreateInputs("counter", None, None, None)
        engine
          .groupCreate(groupInputs)
          .flatMap(group => engine.counterCreate(group.id, counterInputs) )
          .map(counter => counter.value.name shouldBe counterInputs.name )
          .andThen(_ => engine.shutdown())
      }

      "be incremented" in {
        val engine = new StandardCountersEngine(config, storage)
        val groupInputs = CountersGroupCreateInputs("truc",None,None)
        val counterInputs = CounterCreateInputs("counter", None, None, None)
        engine
          .groupCreate(groupInputs)
          .flatMap(group => engine.counterCreate(group.id, counterInputs) )
          .flatMap(counter => engine.counterIncrement(counter.value.groupId, counter.value.id,None) )
          .map(state => state.value.count shouldBe 1)
          .andThen(_ => engine.shutdown())
      }
    }
  }
}
