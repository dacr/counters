package counters.model

import java.util.UUID

trait CountersGroupRequirements {
  def name: String
  def description: Option[String]
  def origin: Option[OperationOrigin]
}

case class CountersGroupCreateInputs(
  name: String,
  description: Option[String],
  origin: Option[OperationOrigin]
) extends CountersGroupRequirements

case class CountersGroup(
  id: UUID,
  name: String,
  description: Option[String],
  origin: Option[OperationOrigin]
) extends CountersGroupRequirements
