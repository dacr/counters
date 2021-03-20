package counters.model

import java.time.Instant

case class CounterState(
  counter:Counter,
  count:Long,
  lastUpdated: Instant,
  lastOrigin: Option[OperationOrigin]
)
