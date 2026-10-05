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

import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, StatusCodes}
import org.apache.pekko.http.scaladsl.model.headers.{OAuth2BearerToken, RawHeader}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import counters.dependencies.countersengine.{BasicCountersFileSystemStorage, StandardCountersEngine}
import counters.model.*
import counters.api.{ApiEndpoints, ApiError, CounterValue, Health, UserToken}
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import org.apache.commons.io.FileUtils
import org.apache.pekko.http.scaladsl.model.headers.Authorization
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.*
import org.scalatest.wordspec.*
import org.scalatest.OptionValues.*
import org.scalatest.LoneElement.*

import java.net.URI
import java.nio.file.Files
import java.util.UUID

class ServiceTest extends AsyncWordSpec with should.Matchers with ScalatestRouteTest with BeforeAndAfterAll {

  val directory    = Files.createTempDirectory("counters-service-test").toFile
  val config       = ServiceConfig()
  val fsConfig     = config.copy(counters = config.counters.copy(behavior = Behavior(FileSystemStorageConfig(directory.getPath))))
  val storage      = new BasicCountersFileSystemStorage(fsConfig)
  val engine       = new StandardCountersEngine(fsConfig, storage)
  val mailer       = new TestMailer()
  val dependencies = new ServiceDependencies(fsConfig, engine, mailer)
  val routes       = ServiceRoutes(dependencies).routes

  override def afterAll(): Unit = {
    super.afterAll()
    FileUtils.deleteDirectory(directory)
  }

  def json(content: String) = HttpEntity(ContentTypes.`application/json`, content)

  extension (request: HttpRequest) {
    def as(token: String): HttpRequest = request.addHeader(Authorization(OAuth2BearerToken(token)))
  }

  def uniqueEmail(name: String) = s"$name-${UUID.randomUUID()}@example.com"

  def registerOnly(name: String, email: String): UserRegistration =
    Post("/api/user", json(s"""{"name":"$name","email":"$email"}""")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      readFromString[UserRegistration](responseAs[String])
    }

  def validateEmail(email: String): Unit =
    Post(s"/user/validate?code=${mailer.validationCode(email)}") ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] should include("Email validated")
    }

  /** Registers a user and validates its email */
  def register(name: String): UserRegistration = {
    val registration = registerOnly(name, uniqueEmail(name))
    validateEmail(registration.user.email)
    registration.copy(user = registration.user.copy(emailValidated = true))
  }

  def groupCreate(token: String, name: String): CountersGroup =
    Post("/api/group", json(s"""{"name":"$name"}""")).as(token) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      readFromString[CountersGroup](responseAs[String])
    }

  def counterCreate(token: String, groupId: UUID, body: String): Counter =
    Post(s"/api/group/$groupId/counter", json(body)).as(token) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      readFromString[Counter](responseAs[String])
    }

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
    "Register users, with a default group and an API token usable once the email validated" in {
      val email        = uniqueEmail("john")
      val registration = Post("/api/user", json(s"""{"name":"john","email":"$email"}""")).withHeaders(RawHeader("X-Forwarded-For", "10.1.2.3, 10.0.0.1"), RawHeader("User-Agent", "test-agent")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        readFromString[UserRegistration](responseAs[String])
      }
      registration.user.origin.value.ipAddress.value shouldBe "10.1.2.3"
      registration.user.origin.value.userAgent.value shouldBe "test-agent"
      registration.user.emailValidated shouldBe false
      Get("/api/user/me").as(registration.token) ~> routes ~> check {
        status shouldBe StatusCodes.Forbidden
        readFromString[ApiError](responseAs[String]).message should include("not been validated")
      }
      val validationEmail = mailer.sentTo(email).loneElement
      validationEmail.body should include(s"${fsConfig.counters.site.baseURL}/user/validate?code=")
      val code = mailer.validationCode(email)
      // the link only leads to a confirmation page, so a mail scanner following it validates nothing
      Get(s"/user/validate?code=$code") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("Confirm my email address")
      }
      Get("/api/user/me").as(registration.token) ~> routes ~> check {
        status shouldBe StatusCodes.Forbidden
      }
      validateEmail(email)
      Post(s"/user/validate?code=$code") ~> routes ~> check {
        responseAs[String] should include("Email validation failed")
      }
      Get("/api/user/me").as(registration.token) ~> routes ~> check {
        readFromString[User](responseAs[String]) shouldBe registration.user.copy(emailValidated = true)
      }
      Get("/api/group").as(registration.token) ~> routes ~> check {
        readFromString[List[CountersGroup]](responseAs[String]) shouldBe List(registration.defaultGroup)
      }
      Delete(s"/api/group/${registration.defaultGroup.id}").as(registration.token) ~> routes ~> check {
        status shouldBe StatusCodes.Conflict
        readFromString[ApiError](responseAs[String]) shouldBe ApiError("the user default group can't be deleted")
      }
      val renewed      = Post("/api/user/me/token").as(registration.token) ~> routes ~> check {
        readFromString[UserToken](responseAs[String]).token
      }
      Get("/api/user/me").as(registration.token) ~> routes ~> check {
        status shouldBe StatusCodes.Unauthorized
      }
      Delete("/api/user/me").as(renewed) ~> routes ~> check {
        status shouldBe StatusCodes.NoContent
      }
      Get("/api/user/me").as(renewed) ~> routes ~> check {
        status shouldBe StatusCodes.Unauthorized
      }
    }
    "Reject invalid or already used registration emails" in {
      Post("/api/user", json("""{"name":"john","email":"not an email"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
      Post("/api/user", json("""{"name":" ","email":"john@example.com"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
      val email = uniqueEmail("john")
      registerOnly("john", email)
      Post("/api/user", json(s"""{"name":"other","email":"${email.toUpperCase}"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.Conflict
        readFromString[ApiError](responseAs[String]) shouldBe ApiError("email address already used")
      }
    }
    "Cancel the registration when the validation email can't be sent" in {
      val failingRoutes = ServiceRoutes(new ServiceDependencies(fsConfig, engine, new TestMailer(failing = true))).routes
      val email         = uniqueEmail("john")
      Post("/api/user", json(s"""{"name":"john","email":"$email"}""")) ~> failingRoutes ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
      }
      registerOnly("john", email).user.email shouldBe email // the email address has been released
    }
    "Require an API token for groups and counters management" in {
      Get("/api/group") ~> routes ~> check {
        status shouldBe StatusCodes.Unauthorized
      }
      Post("/api/group", json("""{"name":"x"}""")).as("invalid") ~> routes ~> check {
        status shouldBe StatusCodes.Unauthorized
        readFromString[ApiError](responseAs[String]) shouldBe ApiError("missing or invalid API token")
      }
      val john = register("john")
      Get(s"/api/group/${john.defaultGroup.id}/counter") ~> routes ~> check {
        status shouldBe StatusCodes.Unauthorized
      }
    }
    "Isolate users groups and counters" in {
      val john         = register("john")
      val jane         = register("jane")
      val group        = john.defaultGroup
      val counter      = counterCreate(john.token, group.id, """{"name":"john counter"}""")
      val janeRequests = List(
        Get(s"/api/group/${group.id}"),
        Put(s"/api/group/${group.id}", json("""{"name":"x"}""")),
        Delete(s"/api/group/${group.id}"),
        Get(s"/api/group/${group.id}/counter"),
        Post(s"/api/group/${group.id}/counter", json("""{"name":"x"}""")),
        Get(s"/api/group/${group.id}/counter/${counter.id}"),
        Put(s"/api/group/${group.id}/counter/${counter.id}", json("""{"name":"x"}""")),
        Delete(s"/api/group/${group.id}/counter/${counter.id}"),
        Get(s"/api/group/${group.id}/counter/${counter.id}/state"),
        Get(s"/api/group/${group.id}/counter/${counter.id}/history"),
        Post(s"/api/group/${group.id}/counter/${counter.id}/increment")
      )
      janeRequests.foreach { request =>
        request.as(jane.token) ~> routes ~> check {
          withClue(s"${request.method.value} ${request.uri} :") {
            status shouldBe StatusCodes.NotFound
          }
        }
      }
      Get("/api/group").as(jane.token) ~> routes ~> check {
        readFromString[List[CountersGroup]](responseAs[String]) shouldBe List(jane.defaultGroup)
      }
      Get(s"/api/group/${group.id}/counter/${counter.id}/state").as(john.token) ~> routes ~> check {
        readFromString[CounterState](responseAs[String]).count shouldBe 0
      }
      // a counter is only reachable through its own group
      Get(s"/api/group/${jane.defaultGroup.id}/counter/${counter.id}").as(jane.token) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
    "Expose the API to create, read and increment counters" in {
      val john    = register("john")
      val group   = john.defaultGroup
      val counter = Post(s"/api/group/${group.id}/counter", json("""{"name":"api counter","redirect":"http://example.com/x"}"""))
        .as(john.token)
        .withHeaders(RawHeader("X-Forwarded-For", "10.1.2.3, 10.0.0.1"), RawHeader("User-Agent", "test-agent"), Authorization(OAuth2BearerToken(john.token))) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        readFromString[Counter](responseAs[String])
      }
      counter.redirect.value.toString shouldBe "http://example.com/x"
      counter.publicIncrement shouldBe false
      counter.origin.value.ipAddress.value shouldBe "10.1.2.3"
      Post(s"/api/group/${group.id}/counter/${counter.id}/increment").as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val value = readFromString[CounterValue](responseAs[String])
        value.count shouldBe 1
        responseAs[String] shouldBe s"""{"count":1,"lastUpdated":${value.lastUpdated.toEpochMilli}}"""
      }
      Get(s"/api/group/${group.id}/counter/${counter.id}/state").as(john.token) ~> routes ~> check {
        readFromString[CounterState](responseAs[String]).count shouldBe 1
      }
      Get(s"/api/group/${group.id}/counter/${counter.id}/increment").as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.MethodNotAllowed // increments are only possible through POST requests
      }
    }
    "Allow anonymous API increments only for public counters" in {
      val john           = register("john")
      val groupId        = john.defaultGroup.id
      val counter        = counterCreate(john.token, groupId, """{"name":"private"}""")
      Post(s"/api/group/$groupId/counter/${counter.id}/increment") ~> routes ~> check {
        status shouldBe StatusCodes.Unauthorized
        readFromString[ApiError](responseAs[String]) shouldBe ApiError("an API token is required to increment this counter")
      }
      Post(s"/api/group/$groupId/counter/${counter.id}/increment").as("invalid") ~> routes ~> check {
        status shouldBe StatusCodes.Unauthorized
      }
      Put(s"/api/group/$groupId/counter/${counter.id}", json("""{"name":"public","publicIncrement":true}""")).as(john.token) ~> routes ~> check {
        readFromString[Counter](responseAs[String]).publicIncrement shouldBe true
      }
      Post(s"/api/group/$groupId/counter/${counter.id}/increment") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        readFromString[CounterValue](responseAs[String]).count shouldBe 1
      }
      Post(s"/api/group/$groupId/counter/${UUID.randomUUID()}/increment") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
      // the count page is always public
      val privateCounter = counterCreate(john.token, groupId, """{"name":"private"}""")
      Get(s"/$groupId/count/${privateCounter.id}") ~> routes ~> check {
        status shouldBe StatusCodes.TemporaryRedirect
      }
    }
    "Keep the increments history with their origin" in {
      val john    = register("john")
      val groupId = john.defaultGroup.id
      val counter = counterCreate(john.token, groupId, """{"name":"history"}""")
      Get(s"/$groupId/count/${counter.id}")
        .withHeaders(RawHeader("X-Forwarded-For", "10.9.9.9"), RawHeader("User-Agent", "browser"), RawHeader("Referer", "http://example.com/page")) ~> routes ~> check {
        status shouldBe StatusCodes.TemporaryRedirect
      }
      Post(s"/api/group/$groupId/counter/${counter.id}/increment").as(john.token).withHeaders(RawHeader("User-Agent", "script"), Authorization(OAuth2BearerToken(john.token))) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Get(s"/api/group/$groupId/counter/${counter.id}/history").as(john.token) ~> routes ~> check {
        val history = readFromString[List[CounterHistoryEntry]](responseAs[String])
        history.map(_.count) shouldBe List(2, 1)
        history.flatMap(_.origin.flatMap(_.userAgent)) shouldBe List("script", "browser")
        history(1).origin.value.ipAddress.value shouldBe "10.9.9.9"
        history(1).origin.value.referer.value shouldBe "http://example.com/page"
      }
      Get(s"/api/group/$groupId/counter/${counter.id}/history?limit=1").as(john.token) ~> routes ~> check {
        readFromString[List[CounterHistoryEntry]](responseAs[String]).map(_.count) shouldBe List(2)
      }
      Get(s"/api/group/$groupId/counter/${counter.id}/history?limit=0").as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
    "Expose the API to update and delete groups" in {
      val john    = register("john")
      val group   = groupCreate(john.token, "group")
      val counter = counterCreate(john.token, group.id, """{"name":"counter"}""")
      Put(s"/api/group/${group.id}", json("""{"name":"renamed"}""")).as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        readFromString[CountersGroup](responseAs[String]) shouldBe group.copy(name = "renamed", description = None)
      }
      Get(s"/api/group/${group.id}/counter/${counter.id}/state").as(john.token) ~> routes ~> check {
        readFromString[CounterState](responseAs[String]).group.name shouldBe "renamed"
      }
      Delete(s"/api/group/${group.id}").as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.NoContent
      }
      Get(s"/api/group/${group.id}").as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
      Get(s"/api/group/${group.id}/counter/${counter.id}/state").as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
      Delete(s"/api/group/${group.id}").as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
        readFromString[ApiError](responseAs[String]) shouldBe ApiError("group not found")
      }
      Put(s"/api/group/${group.id}", json("""{"name":"x"}""")).as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
    "Expose the API to get, list, update and delete counters" in {
      val john    = register("john")
      val group   = groupCreate(john.token, "crud group")
      Get(s"/api/group/${group.id}").as(john.token) ~> routes ~> check {
        readFromString[CountersGroup](responseAs[String]) shouldBe group
      }
      val counter = counterCreate(john.token, group.id, """{"name":"crud counter"}""")
      Get(s"/api/group/${group.id}/counter/${counter.id}").as(john.token) ~> routes ~> check {
        readFromString[Counter](responseAs[String]) shouldBe counter
      }
      Get(s"/api/group/${group.id}/counter/${counter.id}/state").as(john.token) ~> routes ~> check {
        readFromString[CounterState](responseAs[String]).counter shouldBe counter
      }
      Put(s"/api/group/${group.id}/counter/${counter.id}", json("""{"name":"renamed","description":"updated"}""")).as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        readFromString[Counter](responseAs[String]) shouldBe counter.copy(name = "renamed", description = Some("updated"))
      }
      Get(s"/api/group/${group.id}/counter").as(john.token) ~> routes ~> check {
        readFromString[List[Counter]](responseAs[String]).map(_.name) shouldBe List("renamed")
      }
      Delete(s"/api/group/${group.id}/counter/${counter.id}").as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.NoContent
      }
      Delete(s"/api/group/${group.id}/counter/${counter.id}").as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
      Get(s"/api/group/${group.id}/counter").as(john.token) ~> routes ~> check {
        readFromString[List[Counter]](responseAs[String]) shouldBe Nil
      }
    }
    "Respond with a json error when a group or a counter is not found" in {
      val john    = register("john")
      val unknown = UUID.randomUUID()
      Get(s"/api/group/$unknown/counter/$unknown/state").as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
        readFromString[ApiError](responseAs[String]) shouldBe ApiError("group or counter not found")
      }
      Get(s"/api/group/$unknown/counter/$unknown").as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
        readFromString[ApiError](responseAs[String]) shouldBe ApiError("group or counter not found")
      }
      Post(s"/api/group/$unknown/counter", json("""{"name":"x"}""")).as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
        readFromString[ApiError](responseAs[String]) shouldBe ApiError("group not found")
      }
      Get(s"/api/group/$unknown").as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
        readFromString[ApiError](responseAs[String]) shouldBe ApiError("group not found")
      }
      Get(s"/api/group/$unknown/counter").as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
      Put(s"/api/group/$unknown/counter/$unknown", json("""{"name":"x"}""")).as(john.token) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
    "Expose the API documentation generated from the endpoints definitions" in {
      Get("/swagger/docs.yaml") ~> routes ~> check {
        val spec = responseAs[String]
        ApiEndpoints.all.flatMap(_.info.name).foreach(name => spec should include(s"operationId: $name"))
        spec should include("apiToken:")
        spec should include("scheme: bearer")
      }
      Get("/swagger/swagger.json") ~> routes ~> check {
        responseAs[String] should include("\"/api/group/{groupId}/counter/{counterId}/increment\"")
      }
      Get("/swagger/") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include regex "(?i)swagger"
      }
    }
    "Increment a counter through the count page" in {
      val redirectTo = "http://mapland.fr/counters/dummy"
      engine
        .userRegister(UserCreateInputs("john", uniqueEmail("john"), None))
        .flatMap { registered =>
          val registration = registered.value.registration
          val inputs       = CounterCreateInputs("counter", None, Some(URI(redirectTo).toURL), false, None)
          engine.counterCreate(registration.user.id, registration.defaultGroup.id, inputs)
        }
        .map { counter =>
          val counterId = counter.value.id
          val groupId   = counter.value.groupId
          Get(s"/$groupId/count/$counterId") ~> routes ~> check {
            response.status.intValue() shouldBe 307
            val location    = header("Location").value.value()
            val locationURL = URI(location).toURL
            val params      =
              locationURL.getQuery
                .split("&")
                .map(_.split("=", 2))
                .map(_ match { case Array(a, b) => a -> b; case Array(a) => a -> "" })
                .toMap
            location should startWith regex s"^$redirectTo"
            params.get("count").value shouldBe "1"
            params.get("groupId").value shouldBe groupId.toString
            params.get("counterId").value shouldBe counterId.toString
          }
        }
    }
  }
}
