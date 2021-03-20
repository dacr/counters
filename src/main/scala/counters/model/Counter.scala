package counters.model

import java.net.URL
import java.util.UUID

trait CounterRequirements {
  def name: String
  def description: Option[String]
  def redirect: Option[URL]
  def origin: Option[OperationOrigin]
}

case class CounterCreateInputs(
  name: String,
  description: Option[String],
  redirect: Option[URL],
  origin: Option[OperationOrigin]
) extends CounterRequirements

case class Counter(
  id: UUID,
  groupId:UUID,
  name: String,
  description: Option[String],
  redirect: Option[URL],
  origin: Option[OperationOrigin]
) extends CounterRequirements
