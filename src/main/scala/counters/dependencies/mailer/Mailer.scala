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
package counters.dependencies.mailer

import counters.MailConfig
import jakarta.mail.{Authenticator, Message, PasswordAuthentication, Session, Transport}
import jakarta.mail.internet.{InternetAddress, MimeMessage}
import org.slf4j.LoggerFactory

import java.util.{Date, Properties}
import scala.concurrent.{ExecutionContext, Future, blocking}

case class Email(
  to: String,
  subject: String,
  body: String
)

trait Mailer {
  /** Fails when the email couldn't be handed over to the mail server */
  def send(email: Email): Future[Unit]
}

object Mailer {
  def apply(config: MailConfig): Mailer = {
    config.smtp.host match {
      case Some(host) => SmtpMailer(config, host)
      case None       => LogMailer()
    }
  }
}

/** Development only, emails are not sent but logged */
class LogMailer extends Mailer {
  private val logger = LoggerFactory.getLogger(getClass)
  logger.warn("No SMTP host configured, emails are NOT sent but only logged")

  override def send(email: Email): Future[Unit] = {
    logger.info(s"Email to ${email.to} : ${email.subject}\n${email.body}")
    Future.unit
  }
}

class SmtpMailer(config: MailConfig, host: String) extends Mailer {
  private val logger = LoggerFactory.getLogger(getClass)
  private val smtp   = config.smtp
  // sending is blocking, so kept away from the main execution contexts
  private given ExecutionContext = ExecutionContext.fromExecutor(java.util.concurrent.Executors.newFixedThreadPool(2))

  private val session = {
    val properties = new Properties()
    val timeout    = "10000"
    properties.put("mail.smtp.host", host)
    properties.put("mail.smtp.port", smtp.port.toString)
    properties.put("mail.smtp.connectiontimeout", timeout)
    properties.put("mail.smtp.timeout", timeout)
    properties.put("mail.smtp.writetimeout", timeout)
    smtp.tls match {
      case "implicit" =>
        properties.put("mail.smtp.ssl.enable", "true")
        properties.put("mail.smtp.ssl.checkserveridentity", "true")
      case "starttls" =>
        properties.put("mail.smtp.starttls.enable", "true")
        properties.put("mail.smtp.starttls.required", "true")
        properties.put("mail.smtp.ssl.checkserveridentity", "true")
      case "none"     =>
      case other      => throw new IllegalArgumentException(s"Unsupported SMTP TLS mode '$other', use implicit, starttls or none")
    }
    val authenticator = smtp.username.map { username =>
      properties.put("mail.smtp.auth", "true")
      new Authenticator {
        override def getPasswordAuthentication: PasswordAuthentication =
          new PasswordAuthentication(username, smtp.password.getOrElse(""))
      }
    }
    logger.info(s"Emails sent through $host:${smtp.port} (tls ${smtp.tls}) as ${config.from}")
    Session.getInstance(properties, authenticator.orNull)
  }

  override def send(email: Email): Future[Unit] = Future {
    blocking {
      val message = new MimeMessage(session)
      message.setFrom(new InternetAddress(config.from))
      config.replyTo.foreach(replyTo => message.setReplyTo(Array(new InternetAddress(replyTo))))
      message.setRecipients(Message.RecipientType.TO, Array[jakarta.mail.Address](new InternetAddress(email.to, true)))
      message.setSubject(email.subject, "UTF-8")
      message.setText(email.body, "UTF-8")
      message.setSentDate(new Date())
      Transport.send(message)
    }
  }
}
