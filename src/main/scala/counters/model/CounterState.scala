package counters.model

import java.time.Instant
import java.util.UUID

case class CounterState(
  id:UUID,
  counter:Counter,
  count:Long,
  lastUpdated: Instant,
  lastOrigin: Option[OperationOrigin]
)
