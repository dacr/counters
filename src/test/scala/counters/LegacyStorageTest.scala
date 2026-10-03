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

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import counters.dependencies.countersengine.BasicCountersFileSystemStorage
import org.apache.commons.io.FileUtils
import org.scalatest.OptionValues.*
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.io.File
import java.net.URI
import java.util.UUID

/** Data stored by the previous json4s based releases must remain readable and be written back unchanged */
class LegacyStorageTest extends AnyWordSpec with should.Matchers {
  val legacyStore = new File(getClass.getResource("/legacy-store").toURI)

  val config  = ServiceConfig()
  val storage = new BasicCountersFileSystemStorage(
    config.copy(counters = config.counters.copy(behavior = Behavior(FileSystemStorageConfig(legacyStore.getPath))))
  )

  val groupId   = UUID.fromString("83ad6ccf-7195-45be-b4a1-f259d8456746")
  val counterId = UUID.fromString("2b3278a8-871e-4f09-aa07-d50fc91cc2d3")

  "File system storage" should {
    "read groups, counters and states written by previous releases" in {
      storage.groupsList().map(_.name) shouldBe List("test group")
      storage.groupCounters(groupId).map(_.name).toSet shouldBe Set("counter#1", "plain")
      val state = storage.stateGet(groupId, counterId).value
      state.count shouldBe 1
      state.lastUpdated.toEpochMilli shouldBe 1791020278614L
      state.counter.redirect.value shouldBe URI("http://example.com/x").toURL
      state.lastOrigin.value.createdByUserAgent.value shouldBe "curl-test"
    }
    "write data using the same json representation" in {
      def legacy(file: File) = FileUtils.readFileToString(file, "UTF-8")
      writeToString(storage.groupGet(groupId).value) shouldBe legacy(storage.groupFile(groupId))
      writeToString(storage.counterGet(groupId, counterId).value) shouldBe legacy(storage.counterFile(groupId, counterId))
      writeToString(storage.stateGet(groupId, counterId).value) shouldBe legacy(storage.stateFile(groupId, counterId))
    }
    "return nothing for unknown data" in {
      storage.stateGet(groupId, UUID.randomUUID()) shouldBe None
    }
  }
}
