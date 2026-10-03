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

import counters.dependencies.countersengine.{BasicCountersFileSystemStorage, NopCounterStorage, StandardCountersEngine}
import counters.model.{CounterCreateInputs, CounterUpdateInputs, CountersGroup, CountersGroupCreateInputs, GroupUpdateInputs}
import org.apache.commons.io.FileUtils
import org.scalatest._
import org.scalatest.matchers.should
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.OptionValues._

import java.io.File
import java.net.URI
import java.nio.file.Files
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

      "be listed sorted by name" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          group    <- engine.groupCreate(CountersGroupCreateInputs("truc", None, None))
          _        <- engine.counterCreate(group.id, CounterCreateInputs("b", None, None, None))
          _        <- engine.counterCreate(group.id, CounterCreateInputs("a", None, None, None))
          counters <- engine.groupCounters(group.id)
          unknown  <- engine.groupCounters(UUID.randomUUID())
          _        <- engine.shutdown()
        } yield {
          counters.value.map(_.name) shouldBe List("a", "b")
          unknown shouldBe None
        }
      }

      "be updated without changing their count" in {
        val engine = new StandardCountersEngine(config, storage)
        val redirect = URI("http://example.com/x").toURL
        for {
          group   <- engine.groupCreate(CountersGroupCreateInputs("truc", None, None))
          counter <- engine.counterCreate(group.id, CounterCreateInputs("counter", Some("desc"), None, None)).map(_.value)
          _       <- engine.counterIncrement(group.id, counter.id, None)
          updated <- engine.counterUpdate(group.id, counter.id, CounterUpdateInputs("renamed", None, Some(redirect)))
          state   <- engine.stateGet(group.id, counter.id)
          listed  <- engine.groupCounters(group.id)
          unknown <- engine.counterUpdate(group.id, UUID.randomUUID(), CounterUpdateInputs("x", None, None))
          _       <- engine.shutdown()
        } yield {
          updated.value shouldBe counter.copy(name = "renamed", description = None, redirect = Some(redirect))
          state.value.counter shouldBe updated.value
          state.value.count shouldBe 1
          listed.value shouldBe List(updated.value)
          unknown shouldBe None
        }
      }

      "be deleted" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          group     <- engine.groupCreate(CountersGroupCreateInputs("truc", None, None))
          counter   <- engine.counterCreate(group.id, CounterCreateInputs("counter", None, None, None)).map(_.value)
          before    <- engine.serviceStatsGet()
          deleted   <- engine.counterDelete(group.id, counter.id)
          again     <- engine.counterDelete(group.id, counter.id)
          state     <- engine.stateGet(group.id, counter.id)
          increment <- engine.counterIncrement(group.id, counter.id, None)
          listed    <- engine.groupCounters(group.id)
          after     <- engine.serviceStatsGet()
          _         <- engine.shutdown()
        } yield {
          deleted shouldBe true
          again shouldBe false
          state shouldBe None
          increment shouldBe None
          listed.value shouldBe Nil
          after.countersCount shouldBe before.countersCount - 1
        }
      }
    }

    "groups information" should {
      "be retrieved" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          group   <- engine.groupCreate(CountersGroupCreateInputs("truc", Some("desc"), None))
          found   <- engine.groupGet(group.id)
          unknown <- engine.groupGet(UUID.randomUUID())
          _       <- engine.shutdown()
        } yield {
          found.value shouldBe group
          unknown shouldBe None
        }
      }
      "be updated, including within counters states" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          group   <- engine.groupCreate(CountersGroupCreateInputs("truc", Some("desc"), None))
          counter <- engine.counterCreate(group.id, CounterCreateInputs("counter", None, None, None)).map(_.value)
          updated <- engine.groupUpdate(group.id, GroupUpdateInputs("renamed", None))
          found   <- engine.groupGet(group.id)
          state   <- engine.stateGet(group.id, counter.id)
          incr    <- engine.counterIncrement(group.id, counter.id, None)
          unknown <- engine.groupUpdate(UUID.randomUUID(), GroupUpdateInputs("x", None))
          _       <- engine.shutdown()
        } yield {
          updated.value shouldBe group.copy(name = "renamed", description = None)
          found shouldBe updated
          state.value.group shouldBe updated.value
          incr.value.group shouldBe updated.value
          unknown shouldBe None
        }
      }
      "be deleted with all their counters" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          group     <- engine.groupCreate(CountersGroupCreateInputs("truc", None, None))
          counter   <- engine.counterCreate(group.id, CounterCreateInputs("a", None, None, None)).map(_.value)
          _         <- engine.counterCreate(group.id, CounterCreateInputs("b", None, None, None))
          before    <- engine.serviceStatsGet()
          deleted   <- engine.groupDelete(group.id)
          again     <- engine.groupDelete(group.id)
          found     <- engine.groupGet(group.id)
          state     <- engine.stateGet(group.id, counter.id)
          increment <- engine.counterIncrement(group.id, counter.id, None)
          after     <- engine.serviceStatsGet()
          _         <- engine.shutdown()
        } yield {
          deleted shouldBe true
          again shouldBe false
          found shouldBe None
          state shouldBe None
          increment shouldBe None
          after.groupCount shouldBe before.groupCount - 1
          after.countersCount shouldBe before.countersCount - 2
        }
      }
    }

    "changes" should {
      "survive a restart when stored on the file system" in {
        val directory = Files.createTempDirectory("counters-engine-test").toFile
        val fsConfig  = config.copy(counters = config.counters.copy(behavior = Behavior(FileSystemStorageConfig(directory.getPath))))
        def newEngine = new StandardCountersEngine(fsConfig, new BasicCountersFileSystemStorage(fsConfig))
        val engine    = newEngine
        val result = for {
          group <- engine.groupCreate(CountersGroupCreateInputs("truc", None, None))
          kept  <- engine.counterCreate(group.id, CounterCreateInputs("kept", None, None, None)).map(_.value)
          gone  <- engine.counterCreate(group.id, CounterCreateInputs("gone", None, None, None)).map(_.value)
          _     <- engine.counterIncrement(group.id, kept.id, None)
          _     <- engine.counterUpdate(group.id, kept.id, CounterUpdateInputs("renamed", None, None))
          _     <- engine.counterDelete(group.id, gone.id)
          _     <- engine.groupUpdate(group.id, GroupUpdateInputs("renamed group", None))
          other <- engine.groupCreate(CountersGroupCreateInputs("other", None, None))
          _     <- engine.counterCreate(other.id, CounterCreateInputs("other counter", None, None, None))
          _     <- engine.groupDelete(other.id)
          _     <- engine.shutdown()
          restarted = newEngine
          counters     <- restarted.groupCounters(group.id)
          state        <- restarted.stateGet(group.id, kept.id)
          otherFound   <- restarted.groupGet(other.id)
          stats        <- restarted.serviceStatsGet()
          _            <- restarted.shutdown()
        } yield {
          counters.value.map(_.name) shouldBe List("renamed")
          state.value.count shouldBe 1
          state.value.group.name shouldBe "renamed group"
          otherFound shouldBe None
          stats.groupCount shouldBe 1
          stats.countersCount shouldBe 1
          new File(directory, other.id.toString).exists() shouldBe false
        }
        result.andThen(_ => FileUtils.deleteDirectory(directory))
      }
    }
  }
}
