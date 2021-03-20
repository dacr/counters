package counters

import counters.dependencies.countersengine.{CountersEngine, StandardCountersEngine}

class ServiceDependencies(
  val config: ServiceConfig,
  val engine: CountersEngine
)

object ServiceDependencies {
  def defaults: ServiceDependencies = {
    val chosenConfig = ServiceConfig()
    val chosenCountersEngine = StandardCountersEngine(chosenConfig)
    new ServiceDependencies(chosenConfig, chosenCountersEngine)
  }
}