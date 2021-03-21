package counters.model

import java.time.Instant
import java.util.UUID

case class CounterState(
  id:UUID,
  group:CountersGroup,
  counter:Counter,
  count:Long,
  lastUpdated: Instant,
  lastOrigin: Option[OperationOrigin]
)
