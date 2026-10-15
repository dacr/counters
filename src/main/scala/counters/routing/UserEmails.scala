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
package counters.routing

import counters.CountersConfig
import counters.dependencies.mailer.Email
import counters.model.{User, UserApprovalRequest, UserRegistered}

import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter

/** Emails sent during the registration : email validation, approval request, approval notification */
class UserEmails(config: CountersConfig) {
  private val site      = config.site
  private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

  private def utc(instant: Instant): String = s"${formatter.format(instant)} UTC"

  private val signature =
    s"""--
       |Counters, ${site.baseURL}
       |""".stripMargin

  def validation(registered: UserRegistered): Email = {
    val user = registered.registration.user
    Email(
      to = user.email,
      subject = "Counters - confirm your email address",
      body = s"""Hello ${user.name},
                |
                |Thank you for registering to Counters, ${site.baseURL}
                |
                |To activate your account, open the link below and confirm your email address :
                |${site.baseURL}/user/validate?code=${registered.validationCode}
                |
                |This link expires on ${utc(registered.validationExpiresOn)}. Once your email address is confirmed,
                |your registration is sent to an administrator for approval, your API token can't be used until then.
                |If you didn't register, just ignore this email, the registration will be dropped.
                |
                |$signature""".stripMargin
    )
  }

  def approvalRequest(request: UserApprovalRequest): Email = {
    val user   = request.user
    val origin = user.origin
    Email(
      to = config.mail.approver,
      subject = s"Counters - registration to approve : ${user.name} <${user.email}>",
      body = s"""A new user has validated its email address and is waiting for your approval.
                |
                |Name              : ${user.name}
                |Email             : ${user.email}
                |User id           : ${user.id}
                |Default group id  : ${user.defaultGroupId}
                |Registered on     : ${origin.map(o => utc(o.timestamp)).getOrElse("unknown")}
                |Email validated on: ${utc(Instant.now())}
                |From IP address   : ${origin.flatMap(_.ipAddress).getOrElse("unknown")}
                |User agent        : ${origin.flatMap(_.userAgent).getOrElse("unknown")}
                |Referer           : ${origin.flatMap(_.referer).getOrElse("none")}
                |
                |To approve or reject this registration, open :
                |${site.baseURL}/user/approve?code=${request.approvalCode}
                |
                |A rejected registration is dropped with its groups, the user isn't notified.
                |
                |$signature""".stripMargin
    )
  }

  def approved(user: User): Email = {
    Email(
      to = user.email,
      subject = "Counters - your registration has been approved",
      body = s"""Hello ${user.name},
                |
                |Your registration to Counters has been approved, your API token can now be used.
                |The API documentation is available at ${site.swaggerUserInterfaceURL}
                |
                |$signature""".stripMargin
    )
  }
}
