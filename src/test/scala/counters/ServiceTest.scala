/*
 * Copyright 2020-2022 David Crosson
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

import akka.http.scaladsl.model.headers.`Content-Location`
import akka.http.scaladsl.testkit.ScalatestRouteTest
import counters.dependencies.countersengine.{NopCounterStorage, StandardCountersEngine}
import counters.model.{CounterCreateInputs, CountersGroupCreateInputs}
import counters.routing.Health
import counters.tools.JsonImplicits
import org.scalatest.matchers._
import org.scalatest.wordspec._
import org.scalatest.OptionValues._

import java.net.URL


class ServiceTest extends AsyncWordSpec with should.Matchers with ScalatestRouteTest with JsonImplicits {

  val config = ServiceConfig()
  val storage = new NopCounterStorage(config)
  val engine = new StandardCountersEngine(config, storage)
  val dependencies = new ServiceDependencies(config, engine)
  val routes = ServiceRoutes(dependencies).routes

  "Counters Service" should {
    "Respond OK when pinged" in {
      Get("/health") ~> routes ~> check {
        import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
        responseAs[Health] shouldBe Health(true, "alive")
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
    "Increment a counter" in {
      val redirectTo = "http://mapland.fr/counters/dummy"
      val groupInputs = CountersGroupCreateInputs("truc",None,None)
      val counterInputs = CounterCreateInputs("counter", None, Some(new URL(redirectTo)), None)
      engine
        .groupCreate(groupInputs)
        .flatMap(group => engine.counterCreate(group.id, counterInputs) )
        .map{counter =>
          val counterId = counter.value.id
          val groupId = counter.value.groupId
          Get(s"/$groupId/count/$counterId") ~> routes ~> check {
            response.status.intValue() shouldBe 307
            val location = header("Location").value.value()
            val locationURL = new URL(location)
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

