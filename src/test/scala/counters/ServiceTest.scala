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

import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import counters.dependencies.countersengine.{NopCounterStorage, StandardCountersEngine}
import counters.model.{Counter, CounterCreateInputs, CounterState, CountersGroup, CountersGroupCreateInputs}
import counters.api.{ApiEndpoints, ApiError, CounterValue, Health}
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import org.scalatest.matchers.*
import org.scalatest.wordspec.*
import org.scalatest.OptionValues.*

import java.net.URI
import java.util.UUID


class ServiceTest extends AsyncWordSpec with should.Matchers with ScalatestRouteTest {

  val config = ServiceConfig()
  val storage = new NopCounterStorage(config)
  val engine = new StandardCountersEngine(config, storage)
  val dependencies = new ServiceDependencies(config, engine)
  val routes = ServiceRoutes(dependencies).routes

  "Counters Service" should {
    "Respond OK when pinged" in {
      Get("/health") ~> routes ~> check {
        readFromString[Health](responseAs[String]) shouldBe Health(true, "alive")
      }
    }
    "Be able to return a static asset" in {
      Get("/txt/LICENSE-2.0.txt") ~> routes ~> check {
        responseAs[String] should include regex "Apache License"
      }
      Get("/txt/TERMS-OF-SERVICE.txt") ~> routes ~> check {
        responseAs[String] should include regex "WARRANTY"
      }
    }
    "Be able to return embedded webjar assets" in {
      Get("/assets/jquery/jquery.min.js") ~> routes ~> check {
        responseAs[String] should include regex "jQuery v"
      }
      Get("/assets/font-awesome/css/fontawesome.min.css") ~> routes ~> check {
        responseAs[String] should include regex "Font Awesome Free"
      }
    }
    "Respond a counters related home page content" in {
      info("The first content page can be slow because of templates runtime compilation")
      Get() ~> routes ~> check {
        responseAs[String] should include regex "Counters"
      }
    }
    "Expose the API to create, read and increment counters" in {
      def postJson(uri: String, json: String) =
        Post(uri, HttpEntity(ContentTypes.`application/json`, json)).withHeaders(RawHeader("X-Forwarded-For", "10.1.2.3, 10.0.0.1"), RawHeader("User-Agent", "test-agent"))
      val group = postJson("/api/group", """{"name":"api group","description":"desc"}""") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        readFromString[CountersGroup](responseAs[String])
      }
      group.name shouldBe "api group"
      group.origin.value.createdByIpAddress.value shouldBe "10.1.2.3"
      group.origin.value.createdByUserAgent.value shouldBe "test-agent"
      val counter = postJson(s"/api/group/${group.id}/counter", """{"name":"api counter","redirect":"http://example.com/x"}""") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        readFromString[Counter](responseAs[String])
      }
      counter.redirect.value.toString shouldBe "http://example.com/x"
      Post(s"/api/group/${group.id}/counter/${counter.id}/increment") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val value = readFromString[CounterValue](responseAs[String])
        value.count shouldBe 1
        responseAs[String] shouldBe s"""{"count":1,"lastUpdated":${value.lastUpdated.toEpochMilli}}"""
      }
      Get(s"/api/group/${group.id}/counter/${counter.id}") ~> routes ~> check {
        readFromString[CounterState](responseAs[String]).count shouldBe 1
      }
      Get(s"/api/group/${group.id}/counter/${counter.id}/increment") ~> routes ~> check {
        status shouldBe StatusCodes.MethodNotAllowed // increments are only possible through POST requests
      }
    }
    "Expose the API to update and delete groups" in {
      def json(content: String) = HttpEntity(ContentTypes.`application/json`, content)
      val group = Post("/api/group", json("""{"name":"group","description":"desc"}""")) ~> routes ~> check {
        readFromString[CountersGroup](responseAs[String])
      }
      val counter = Post(s"/api/group/${group.id}/counter", json("""{"name":"counter"}""")) ~> routes ~> check {
        readFromString[Counter](responseAs[String])
      }
      Put(s"/api/group/${group.id}", json("""{"name":"renamed"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        readFromString[CountersGroup](responseAs[String]) shouldBe group.copy(name = "renamed", description = None)
      }
      Get(s"/api/group/${group.id}/counter/${counter.id}") ~> routes ~> check {
        readFromString[CounterState](responseAs[String]).group.name shouldBe "renamed"
      }
      Delete(s"/api/group/${group.id}") ~> routes ~> check {
        status shouldBe StatusCodes.NoContent
      }
      Get(s"/api/group/${group.id}") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
      Get(s"/api/group/${group.id}/counter/${counter.id}") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
      Delete(s"/api/group/${group.id}") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
        readFromString[ApiError](responseAs[String]) shouldBe ApiError("group not found")
      }
      Put(s"/api/group/${group.id}", json("""{"name":"x"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
    "Keep the deprecated increment endpoint working" in {
      def json(content: String) = HttpEntity(ContentTypes.`application/json`, content)
      val group = Post("/api/group", json("""{"name":"legacy group"}""")) ~> routes ~> check {
        readFromString[CountersGroup](responseAs[String])
      }
      val counter = Post(s"/api/group/${group.id}/counter", json("""{"name":"legacy counter"}""")) ~> routes ~> check {
        readFromString[Counter](responseAs[String])
      }
      Get(s"/api/increment/${group.id}/${counter.id}") ~> routes ~> check {
        readFromString[CounterState](responseAs[String]).count shouldBe 1
      }
      Get("/swagger/swagger.json") ~> routes ~> check {
        responseAs[String] should include regex """(?s)"/api/increment/\{groupId\}/\{counterId\}".*?"deprecated" *: *true"""
      }
    }
    "Expose the API to get, list, update and delete counters" in {
      def json(content: String) = HttpEntity(ContentTypes.`application/json`, content)
      val group = Post("/api/group", json("""{"name":"crud group"}""")) ~> routes ~> check {
        readFromString[CountersGroup](responseAs[String])
      }
      Get(s"/api/group/${group.id}") ~> routes ~> check {
        readFromString[CountersGroup](responseAs[String]) shouldBe group
      }
      val counter = Post(s"/api/group/${group.id}/counter", json("""{"name":"crud counter"}""")) ~> routes ~> check {
        readFromString[Counter](responseAs[String])
      }
      Get(s"/api/group/${group.id}/counter/${counter.id}") ~> routes ~> check {
        readFromString[CounterState](responseAs[String]).counter shouldBe counter
      }
      Put(s"/api/group/${group.id}/counter/${counter.id}", json("""{"name":"renamed","description":"updated"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val updated = readFromString[Counter](responseAs[String])
        updated shouldBe counter.copy(name = "renamed", description = Some("updated"))
      }
      Get(s"/api/group/${group.id}/counter") ~> routes ~> check {
        readFromString[List[Counter]](responseAs[String]).map(_.name) shouldBe List("renamed")
      }
      Delete(s"/api/group/${group.id}/counter/${counter.id}") ~> routes ~> check {
        status shouldBe StatusCodes.NoContent
      }
      Delete(s"/api/group/${group.id}/counter/${counter.id}") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
      Get(s"/api/group/${group.id}/counter") ~> routes ~> check {
        readFromString[List[Counter]](responseAs[String]) shouldBe Nil
      }
    }
    "Respond with a json error when a group or a counter is not found" in {
      val unknown = UUID.randomUUID()
      Get(s"/api/group/$unknown/counter/$unknown") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
        readFromString[ApiError](responseAs[String]) shouldBe ApiError("group or counter not found")
      }
      Post(s"/api/group/$unknown/counter", HttpEntity(ContentTypes.`application/json`, """{"name":"x"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
        readFromString[ApiError](responseAs[String]) shouldBe ApiError("group not found")
      }
      Get(s"/api/group/$unknown") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
        readFromString[ApiError](responseAs[String]) shouldBe ApiError("group not found")
      }
      Get(s"/api/group/$unknown/counter") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
      Put(s"/api/group/$unknown/counter/$unknown", HttpEntity(ContentTypes.`application/json`, """{"name":"x"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
    "Expose the API documentation generated from the endpoints definitions" in {
      Get("/swagger/docs.yaml") ~> routes ~> check {
        val spec = responseAs[String]
        ApiEndpoints.all.flatMap(_.info.name).foreach(name => spec should include(s"operationId: $name"))
      }
      Get("/swagger/swagger.json") ~> routes ~> check {
        responseAs[String] should include("\"/api/group/{groupId}/counter/{counterId}/increment\"")
      }
      Get("/swagger/") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include regex "(?i)swagger"
      }
    }
    "Increment a counter" in {
      val redirectTo = "http://mapland.fr/counters/dummy"
      val groupInputs = CountersGroupCreateInputs("truc",None,None)
      val counterInputs = CounterCreateInputs("counter", None, Some(URI(redirectTo).toURL), None)
      engine
        .groupCreate(groupInputs)
        .flatMap(group => engine.counterCreate(group.id, counterInputs) )
        .map{counter =>
          val counterId = counter.value.id
          val groupId = counter.value.groupId
          Get(s"/$groupId/count/$counterId") ~> routes ~> check {
            response.status.intValue() shouldBe 307
            val location = header("Location").value.value()
            val locationURL = URI(location).toURL
            val params =
              locationURL
                .getQuery
                .replaceAll("^[?]", "")
                .split("&")
                .map(_.split("=",2))
                .map(_ match {case Array(a,b)=> a->b case Array(a) => a->""})
                .toMap
            location should startWith regex s"^$redirectTo"
            println(params)
            params.get("count").value shouldBe "1"
            params.get("groupId").value shouldBe groupId.toString
            params.get("counterId").value shouldBe counterId.toString
          }
        }

    }
  }
}

