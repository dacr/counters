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

import counters.dependencies.countersengine.{BasicCountersFileSystemStorage, GroupDeleteOutcome, NopCounterStorage, StandardCountersEngine}
import counters.model.*
import org.apache.commons.io.FileUtils
import org.scalatest._
import org.scalatest.matchers.should
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.OptionValues._

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration.*

class StandardCountersEngineTest extends AsyncWordSpec with should.Matchers {
  val config  = ServiceConfig()
  val storage = new NopCounterStorage(config)

  def counterInputs(name: String, description: Option[String] = None, redirect: Option[java.net.URL] = None) =
    CounterCreateInputs(name, description, redirect, false, None)

  def groupInputs(name: String, description: Option[String] = None) =
    CountersGroupCreateInputs(name, description, None)

  def uniqueEmail(name: String) = s"$name-${UUID.randomUUID()}@example.com"

  /** Registers a user and validates its email */
  def register(engine: StandardCountersEngine, name: String): Future[UserRegistration] =
    for {
      registered <- engine.userRegister(UserCreateInputs(name, uniqueEmail(name), None)).map(_.value)
      validated  <- engine.userEmailValidate(registered.validationCode).map(_.value)
    } yield registered.registration.copy(user = validated)

  def withFileSystemStorage[T](test: (() => StandardCountersEngine, File) => Future[Assertion]): Future[Assertion] = {
    val directory = Files.createTempDirectory("counters-engine-test").toFile
    val fsConfig  = config.copy(counters = config.counters.copy(behavior = Behavior(FileSystemStorageConfig(directory.getPath))))
    def newEngine = new StandardCountersEngine(fsConfig, new BasicCountersFileSystemStorage(fsConfig))
    test(() => newEngine, directory).andThen(_ => FileUtils.deleteDirectory(directory))
  }

  "Users" can {
    "registrations" should {
      "wait for the email validation" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          registered <- engine.userRegister(UserCreateInputs("john", "John@Example.com ", None)).map(_.value)
          before     <- engine.userAuthenticate(registered.registration.token)
          duplicate  <- engine.userRegister(UserCreateInputs("other", "john@example.com", None))
          wrongCode  <- engine.userEmailValidate("bad code")
          validated  <- engine.userEmailValidate(registered.validationCode)
          again      <- engine.userEmailValidate(registered.validationCode)
          after      <- engine.userAuthenticate(registered.registration.token)
          _          <- engine.shutdown()
        } yield {
          registered.registration.user.email shouldBe "john@example.com"
          registered.registration.user.emailValidated shouldBe false
          before.value.emailValidated shouldBe false
          duplicate shouldBe None
          wrongCode shouldBe None
          validated.value.emailValidated shouldBe true
          again shouldBe None
          after.value shouldBe validated.value
        }
      }
      "be dropped when the email has not been validated in time" in {
        val expiringConfig = config.copy(counters = config.counters.copy(behavior = config.counters.behavior.copy(emailValidationDelay = 0.seconds)))
        val engine         = new StandardCountersEngine(expiringConfig, storage)
        for {
          first     <- engine.userRegister(UserCreateInputs("john", "john@example.com", None)).map(_.value)
          validated <- engine.userEmailValidate(first.validationCode)
          second    <- engine.userRegister(UserCreateInputs("john", "john@example.com", None)) // the email address has been released
          token     <- engine.userAuthenticate(first.registration.token)
          stats     <- engine.serviceStatsGet()
          _         <- engine.shutdown()
        } yield {
          validated shouldBe None
          second shouldBe defined
          token shouldBe None
          stats shouldBe ServiceStats(usersCount = 1, groupCount = 1, countersCount = 0)
        }
      }
    }
    "register" should {
      "get a default group and a working API token" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          registration  <- register(engine, "john")
          authenticated <- engine.userAuthenticate(registration.token)
          rejected      <- engine.userAuthenticate("bad token")
          groups        <- engine.userGroups(registration.user.id)
          stats         <- engine.serviceStatsGet()
          _             <- engine.shutdown()
        } yield {
          registration.user.name shouldBe "john"
          registration.user.defaultGroupId shouldBe registration.defaultGroup.id
          registration.defaultGroup.ownerId shouldBe registration.user.id
          authenticated.value shouldBe registration.user
          rejected shouldBe None
          groups.value shouldBe List(registration.defaultGroup)
          stats shouldBe ServiceStats(usersCount = 1, groupCount = 1, countersCount = 0)
        }
      }
      "renew their API token, the previous one being revoked" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          registration <- register(engine, "john")
          renewed      <- engine.userTokenRenew(registration.user.id).map(_.value)
          previous     <- engine.userAuthenticate(registration.token)
          current      <- engine.userAuthenticate(renewed)
          _            <- engine.shutdown()
        } yield {
          renewed should not be registration.token
          previous shouldBe None
          current.value shouldBe registration.user
        }
      }
      "be deleted with all their groups and counters" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          registration <- register(engine, "john")
          userId        = registration.user.id
          group        <- engine.groupCreate(userId, groupInputs("other")).map(_.value)
          counter      <- engine.counterCreate(userId, group.id, counterInputs("counter")).map(_.value)
          deleted      <- engine.userDelete(userId)
          again        <- engine.userDelete(userId)
          token        <- engine.userAuthenticate(registration.token)
          increment    <- engine.counterIncrement(group.id, counter.id, None)
          stats        <- engine.serviceStatsGet()
          _            <- engine.shutdown()
        } yield {
          deleted shouldBe true
          again shouldBe false
          token shouldBe None
          increment shouldBe None
          stats shouldBe ServiceStats(0, 0, 0)
        }
      }
    }
    "groups" should {
      "be isolated from other users" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          john       <- register(engine, "john").map(_.user)
          jane       <- register(engine, "jane").map(_.user)
          group      <- engine.groupCreate(john.id, groupInputs("john group")).map(_.value)
          counter    <- engine.counterCreate(john.id, group.id, counterInputs("counter")).map(_.value)
          groupGet   <- engine.groupGet(jane.id, group.id)
          update     <- engine.groupUpdate(jane.id, group.id, GroupUpdateInputs("hacked", None))
          delete     <- engine.groupDelete(jane.id, group.id)
          counters   <- engine.groupCounters(jane.id, group.id)
          created    <- engine.counterCreate(jane.id, group.id, counterInputs("intruder"))
          counterGet <- engine.counterGet(jane.id, group.id, counter.id)
          state      <- engine.counterState(jane.id, group.id, counter.id)
          history    <- engine.counterHistory(jane.id, group.id, counter.id, 10)
          cDelete    <- engine.counterDelete(jane.id, group.id, counter.id)
          janeGroups <- engine.userGroups(jane.id).map(_.value)
          johnGroup  <- engine.groupGet(john.id, group.id)
          _          <- engine.shutdown()
        } yield {
          List(groupGet, update, counters, created, counterGet, state, history) shouldBe List.fill(7)(None)
          delete shouldBe GroupDeleteOutcome.NotFound
          cDelete shouldBe false
          janeGroups.map(_.id) shouldBe List(jane.defaultGroupId)
          johnGroup.value shouldBe group
        }
      }
      "not mix counters of different groups" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          john      <- register(engine, "john").map(_.user)
          group     <- engine.groupCreate(john.id, groupInputs("group")).map(_.value)
          counter   <- engine.counterCreate(john.id, group.id, counterInputs("counter")).map(_.value)
          wrongGet  <- engine.counterGet(john.id, john.defaultGroupId, counter.id)
          wrongIncr <- engine.counterIncrement(john.defaultGroupId, counter.id, None)
          wrongDel  <- engine.counterDelete(john.id, john.defaultGroupId, counter.id)
          _         <- engine.shutdown()
        } yield {
          wrongGet shouldBe None
          wrongIncr shouldBe None
          wrongDel shouldBe false
        }
      }
      "be created, retrieved and listed by name" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          john    <- register(engine, "john").map(_.user)
          group   <- engine.groupCreate(john.id, groupInputs("a group", Some("desc"))).map(_.value)
          found   <- engine.groupGet(john.id, group.id)
          unknown <- engine.groupGet(john.id, UUID.randomUUID())
          listed  <- engine.userGroups(john.id).map(_.value)
          nobody  <- engine.groupCreate(UUID.randomUUID(), groupInputs("x"))
          _       <- engine.shutdown()
        } yield {
          group.ownerId shouldBe john.id
          found.value shouldBe group
          unknown shouldBe None
          listed.map(_.name) shouldBe List("a group", "default")
          nobody shouldBe None
        }
      }
      "be updated, including within counters states" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          john    <- register(engine, "john").map(_.user)
          group   <- engine.groupCreate(john.id, groupInputs("truc", Some("desc"))).map(_.value)
          counter <- engine.counterCreate(john.id, group.id, counterInputs("counter")).map(_.value)
          updated <- engine.groupUpdate(john.id, group.id, GroupUpdateInputs("renamed", None))
          found   <- engine.groupGet(john.id, group.id)
          state   <- engine.stateGet(group.id, counter.id)
          incr    <- engine.counterIncrement(group.id, counter.id, None)
          unknown <- engine.groupUpdate(john.id, UUID.randomUUID(), GroupUpdateInputs("x", None))
          _       <- engine.shutdown()
        } yield {
          updated.value shouldBe group.copy(name = "renamed", description = None)
          found shouldBe updated
          state.value.group shouldBe updated.value
          incr.value.group shouldBe updated.value
          unknown shouldBe None
        }
      }
      "be deleted with all their counters, except the default group" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          john      <- register(engine, "john").map(_.user)
          group     <- engine.groupCreate(john.id, groupInputs("truc")).map(_.value)
          counter   <- engine.counterCreate(john.id, group.id, counterInputs("a")).map(_.value)
          _         <- engine.counterCreate(john.id, group.id, counterInputs("b"))
          before    <- engine.serviceStatsGet()
          deleted   <- engine.groupDelete(john.id, group.id)
          again     <- engine.groupDelete(john.id, group.id)
          default   <- engine.groupDelete(john.id, john.defaultGroupId)
          found     <- engine.groupGet(john.id, group.id)
          state     <- engine.stateGet(group.id, counter.id)
          increment <- engine.counterIncrement(group.id, counter.id, None)
          after     <- engine.serviceStatsGet()
          _         <- engine.shutdown()
        } yield {
          deleted shouldBe GroupDeleteOutcome.Deleted
          again shouldBe GroupDeleteOutcome.NotFound
          default shouldBe GroupDeleteOutcome.DefaultGroup
          found shouldBe None
          state shouldBe None
          increment shouldBe None
          after.groupCount shouldBe before.groupCount - 1
          after.countersCount shouldBe before.countersCount - 2
        }
      }
    }
    "counters" should {
      "be created and incremented" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          john    <- register(engine, "john").map(_.user)
          counter <- engine.counterCreate(john.id, john.defaultGroupId, counterInputs("counter")).map(_.value)
          state   <- engine.counterIncrement(counter.groupId, counter.id, None)
          _       <- engine.shutdown()
        } yield {
          counter.name shouldBe "counter"
          counter.publicIncrement shouldBe false
          state.value.count shouldBe 1
        }
      }
      "be listed sorted by name" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          john     <- register(engine, "john").map(_.user)
          groupId   = john.defaultGroupId
          _        <- engine.counterCreate(john.id, groupId, counterInputs("b"))
          _        <- engine.counterCreate(john.id, groupId, counterInputs("a"))
          counters <- engine.groupCounters(john.id, groupId)
          unknown  <- engine.groupCounters(john.id, UUID.randomUUID())
          _        <- engine.shutdown()
        } yield {
          counters.value.map(_.name) shouldBe List("a", "b")
          unknown shouldBe None
        }
      }
      "be updated without changing their count" in {
        val engine   = new StandardCountersEngine(config, storage)
        val redirect = URI("http://example.com/x").toURL
        for {
          john    <- register(engine, "john").map(_.user)
          groupId  = john.defaultGroupId
          counter <- engine.counterCreate(john.id, groupId, counterInputs("counter", Some("desc"))).map(_.value)
          _       <- engine.counterIncrement(groupId, counter.id, None)
          updated <- engine.counterUpdate(john.id, groupId, counter.id, CounterUpdateInputs("renamed", None, Some(redirect), true))
          state   <- engine.counterState(john.id, groupId, counter.id)
          listed  <- engine.groupCounters(john.id, groupId)
          unknown <- engine.counterUpdate(john.id, groupId, UUID.randomUUID(), CounterUpdateInputs("x", None, None, false))
          _       <- engine.shutdown()
        } yield {
          updated.value shouldBe counter.copy(name = "renamed", description = None, redirect = Some(redirect), publicIncrement = true)
          state.value.counter shouldBe updated.value
          state.value.count shouldBe 1
          listed.value shouldBe List(updated.value)
          unknown shouldBe None
        }
      }
      "be deleted" in {
        val engine = new StandardCountersEngine(config, storage)
        for {
          john      <- register(engine, "john").map(_.user)
          groupId    = john.defaultGroupId
          counter   <- engine.counterCreate(john.id, groupId, counterInputs("counter")).map(_.value)
          before    <- engine.serviceStatsGet()
          deleted   <- engine.counterDelete(john.id, groupId, counter.id)
          again     <- engine.counterDelete(john.id, groupId, counter.id)
          state     <- engine.stateGet(groupId, counter.id)
          increment <- engine.counterIncrement(groupId, counter.id, None)
          listed    <- engine.groupCounters(john.id, groupId)
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
      "keep their increments history, most recent first" in withFileSystemStorage { (newEngine, _) =>
        val engine             = newEngine()
        def origin(ip: String) = Some(OperationOrigin(Some(ip), Some("agent"), Some("http://example.com/page")))
        for {
          john    <- register(engine, "john").map(_.user)
          groupId  = john.defaultGroupId
          counter <- engine.counterCreate(john.id, groupId, counterInputs("counter")).map(_.value)
          _       <- engine.counterIncrement(groupId, counter.id, origin("10.0.0.1"))
          _       <- engine.counterIncrement(groupId, counter.id, origin("10.0.0.2"))
          _       <- engine.counterIncrement(groupId, counter.id, origin("10.0.0.3"))
          all     <- engine.counterHistory(john.id, groupId, counter.id, 10).map(_.value)
          last    <- engine.counterHistory(john.id, groupId, counter.id, 2).map(_.value)
          _       <- engine.shutdown()
        } yield {
          all.map(_.count) shouldBe List(3, 2, 1)
          all.flatMap(_.origin.flatMap(_.ipAddress)) shouldBe List("10.0.0.3", "10.0.0.2", "10.0.0.1")
          all.head.origin.value.referer.value shouldBe "http://example.com/page"
          last.map(_.count) shouldBe List(3, 2)
        }
      }
    }

    "changes" should {
      "survive a restart when stored on the file system" in withFileSystemStorage { (newEngine, directory) =>
        val engine = newEngine()
        for {
          registration  <- register(engine, "john")
          john           = registration.user
          group         <- engine.groupCreate(john.id, groupInputs("truc")).map(_.value)
          kept          <- engine.counterCreate(john.id, group.id, counterInputs("kept")).map(_.value)
          gone          <- engine.counterCreate(john.id, group.id, counterInputs("gone")).map(_.value)
          _             <- engine.counterIncrement(group.id, kept.id, None)
          _             <- engine.counterUpdate(john.id, group.id, kept.id, CounterUpdateInputs("renamed", None, None, true))
          _             <- engine.counterDelete(john.id, group.id, gone.id)
          _             <- engine.groupUpdate(john.id, group.id, GroupUpdateInputs("renamed group", None))
          other         <- engine.groupCreate(john.id, groupInputs("other")).map(_.value)
          _             <- engine.counterCreate(john.id, other.id, counterInputs("other counter"))
          _             <- engine.groupDelete(john.id, other.id)
          jane          <- register(engine, "jane").map(_.user)
          _             <- engine.counterCreate(jane.id, jane.defaultGroupId, counterInputs("jane counter"))
          _             <- engine.userDelete(jane.id)
          _             <- engine.shutdown()
          restarted      = newEngine()
          authenticated <- restarted.userAuthenticate(registration.token)
          groups        <- restarted.userGroups(john.id).map(_.value)
          counters      <- restarted.groupCounters(john.id, group.id)
          state         <- restarted.counterState(john.id, group.id, kept.id)
          history       <- restarted.counterHistory(john.id, group.id, kept.id, 10)
          janeFound     <- restarted.userGet(jane.id)
          stats         <- restarted.serviceStatsGet()
          _             <- restarted.shutdown()
        } yield {
          authenticated.value shouldBe john
          groups.map(_.name) shouldBe List("default", "renamed group")
          counters.value.map(_.name) shouldBe List("renamed")
          counters.value.map(_.publicIncrement) shouldBe List(true)
          state.value.count shouldBe 1
          state.value.group.name shouldBe "renamed group"
          history.value.map(_.count) shouldBe List(1)
          janeFound shouldBe None
          stats shouldBe ServiceStats(usersCount = 1, groupCount = 2, countersCount = 1)
          new File(directory, s"groups/${other.id}").exists() shouldBe false
          new File(directory, s"groups/${jane.defaultGroupId}").exists() shouldBe false
          new File(directory, s"users/${jane.id}").exists() shouldBe false
        }
      }
    }
  }
}
