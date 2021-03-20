package counters

import counters.dependencies.countersengine.{NopCounterStorage, StandardCountersEngine}
import counters.model.{CounterCreateInputs, CountersGroup, CountersGroupCreateInputs}
import org.scalatest._
import org.scalatest.matchers.should
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.OptionValues._

import java.util.UUID

class StandardCountersEngineTest extends AsyncWordSpec with should.Matchers{
  val config = ServiceConfig()
  val storage = new NopCounterStorage(config)
  "Counters" can {
    "groups" should {
      "be created" in {
        val engine = new StandardCountersEngine(config, storage)
        val groupInputs = CountersGroupCreateInputs("truc",None,None)
        engine
          .groupCreate(groupInputs)
          .map{group =>group.name shouldBe groupInputs.name}
          .andThen(_ => engine.shutdown())
      }
    }
    "Counters" should {
      "be created" in {
        val engine = new StandardCountersEngine(config, storage)
        val groupInputs = CountersGroupCreateInputs("truc",None,None)
        def counterInputs = CounterCreateInputs("counter", None, None, None)
        engine
          .groupCreate(groupInputs)
          .flatMap(group => engine.counterCreate(group.id, counterInputs) )
          .map(counter => counter.value.name shouldBe counterInputs.name )
          .andThen(_ => engine.shutdown())
      }

      "be incremented" in {
        val engine = new StandardCountersEngine(config, storage)
        val groupInputs = CountersGroupCreateInputs("truc",None,None)
        val counterInputs = CounterCreateInputs("counter", None, None, None)
        engine
          .groupCreate(groupInputs)
          .flatMap(group => engine.counterCreate(group.id, counterInputs) )
          .flatMap(counter => engine.counterIncrement(counter.value.groupId, counter.value.id,None) )
          .map(state => state.value.count shouldBe 1)
          .andThen(_ => engine.shutdown())
      }
    }
  }
}
