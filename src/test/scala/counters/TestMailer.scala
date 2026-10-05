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

import counters.dependencies.mailer.{Email, Mailer}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/** Keeps the sent emails in memory, or fails to send them */
class TestMailer(failing: Boolean = false) extends Mailer {
  private val sent = new ConcurrentLinkedQueue[Email]()

  override def send(email: Email): Future[Unit] =
    if (failing) Future.failed(new RuntimeException("mail server unreachable"))
    else Future.successful(sent.add(email))

  def sentTo(address: String): List[Email] = sent.asScala.filter(_.to == address).toList

  /** The validation code found in the last email sent to this address */
  def validationCode(address: String): String =
    sentTo(address).lastOption
      .flatMap(email => """code=([A-Za-z0-9_-]+)""".r.findFirstMatchIn(email.body))
      .map(_.group(1))
      .getOrElse(throw new IllegalStateException(s"no validation email sent to $address"))
}
