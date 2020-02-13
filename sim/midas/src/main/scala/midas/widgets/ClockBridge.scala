// See LICENSE for license details.

package midas.widgets

import midas.core.{SimWrapperChannels, SimUtils}
import midas.core.SimUtils.{RVChTuple}
import midas.passes.fame.{FAMEChannelConnectionAnnotation, TargetClockChannel}

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.DensePrefixSum

import chisel3._
import chisel3.util._
import chisel3.experimental.{BaseModule, Direction, ChiselAnnotation, annotate}
import firrtl.annotations.{ModuleTarget, ReferenceTarget}

/* Bridge
 *
 * Bridges are widgets that operate directly on token streams moving to and
 * from the transformed-RTL model.
 *
 */

sealed trait ClockBridgeConsts {
  val clockChannelName = "clocks"
}

case class ClockBridgeAnnotation(val target: ModuleTarget, referencePeriod: Int, numerators: Seq[Int], denominators: Seq[Int])
    extends BridgeAnnotation with ClockBridgeConsts {
  val channelNames = Seq(clockChannelName)
  def duplicate(n: ModuleTarget) = this.copy(target)
  def toIOAnnotation(port: String): BridgeIOAnnotation = {
    val channelMapping = channelNames.map(oldName => oldName -> s"${port}_$oldName")
    BridgeIOAnnotation(
      target.copy(module = target.circuit).ref(port),
      channelMapping.toMap,
      Some((p: Parameters) => new ClockBridgeModule(referencePeriod, numerators.zip(denominators))(p))
    )
  }
}

class RationalClockBridge(referencePeriod: Int, phaseRelationships: (Int, Int)*) extends BlackBox with ClockBridgeConsts {
  outer =>
  val io = IO(new Bundle {
    val clocks = Output(Vec(phaseRelationships.size + 1, Clock()))
  })

  // Generate the bridge annotation
  annotate(new ChiselAnnotation { def toFirrtl = {
      ClockBridgeAnnotation(
        outer.toNamed.toTarget,
        referencePeriod,
        // Unzip them to make them serializable
        phaseRelationships.unzip._1,
        phaseRelationships.unzip._2)
    }
  })

  annotate(new ChiselAnnotation { def toFirrtl =
      FAMEChannelConnectionAnnotation(
        clockChannelName,
        channelInfo = TargetClockChannel,
        clock = None, // Clock channels do not have a reference clock
        sinks = Some(io.clocks.map(_.toNamed.toTarget)),
        sources = None
      )
  })
}

class ClockTokenVector(numClocks: Int) extends TokenizedRecord with ClockBridgeConsts {
  def targetPortProto(): Vec[Bool] = Vec(numClocks, Bool())
  val clocks = new DecoupledIO(targetPortProto)

  def outputWireChannels = Seq(clocks -> clockChannelName)
  def inputWireChannels = Seq()
  def outputRVChannels = Seq()
  def inputRVChannels = Seq()

  def connectChannels2Port(bridgeAnno: BridgeIOAnnotation, simIo: SimWrapperChannels): Unit = {
    val local2globalName = bridgeAnno.channelMapping.toMap
    for (localName <- outputChannelNames) {
      simIo.clockElement._2 <> elements(localName)
    }
  }

  val elements = collection.immutable.ListMap(clockChannelName -> clocks)
  override def cloneType(): this.type = new ClockTokenVector(numClocks).asInstanceOf[this.type]
  def generateAnnotations(): Unit = {}
}

class ClockBridgeModule(referencePeriod: Int, phaseRelationships: Seq[(Int, Int)])(implicit p: Parameters)
    extends BridgeModule[ClockTokenVector] {
  val io = IO(new WidgetIO())
  val hPort = IO(new ClockTokenVector(phaseRelationships.size + 1))
  val clockTokenGen = Module(new RationalClockTokenGenerator(phaseRelationships))
  hPort.clocks <> clockTokenGen.io

  val hCycleName = "hCycle"
  val hCycle = genWideRORegInit(0.U(64.W), hCycleName)
  hCycle := hCycle + 1.U

  // Count the number of clock tokens for which the fastest clock is scheduled to fire
  //  --> Use to calculate FMR
  val tCycleFastest = genWideRORegInit(0.U(64.W), "tCycle")
  val fastestClockIdx = ((1,1) +: phaseRelationships).map({ case (n, d) => n.toDouble / d })
                                                     .zipWithIndex
                                                     .sortBy(_._1)
                                                     .last._2

  when (hPort.clocks.fire && hPort.clocks.bits(fastestClockIdx)) {
    tCycleFastest := tCycleFastest + 1.U
  }
  genCRFile()
}

/**
  * Finds a clock whose period is the GCD of the periods of all requested
  * clocks, and returns the period of each requested clock as a multiple of that
  * high-frequency base clock
  *
  * Note: This prepends a reference (1,1) clock to the start of the list
  */

object FindScaledPeriodGCD {
  def apply(phaseRelationships: Seq[(Int, Int)]): Seq[BigInt] = {
    val allClocks = (1,1) +: phaseRelationships
    val periodDivisors = allClocks.unzip._1
    val productOfDivisors  = periodDivisors.foldLeft(BigInt(1))(_ * _)
    val scaledMultipliers  = allClocks.map({ case (divisor, multiplier) =>  multiplier * productOfDivisors / divisor })
    val gcdOfScaledPeriods = scaledMultipliers.reduce((a, b) => a.gcd(b))
    val reducedPeriods     = scaledMultipliers.map(_ / gcdOfScaledPeriods)
    reducedPeriods
  }
}

class RationalClockTokenGenerator(phaseRelationships: Seq[(Int, Int)]) extends Module {
  val numClocks = phaseRelationships.size + 1
  val io = IO(new DecoupledIO(Vec(numClocks, Bool())))
  io.valid := true.B

  val clockPeriodicity = FindScaledPeriodGCD(phaseRelationships)
  val counterWidth     = clockPeriodicity.map(p => log2Ceil(p + 1)).reduce((a, b) => math.max(a, b))

  // This is an arbitrarily selected number; feel free to increase it
  val maxCounterWidth = 16
  require(counterWidth <= maxCounterWidth, "Ensure this circuit doesn't blow up")

  val timeToNextEdge   = RegInit(VecInit(Seq.fill(numClocks)(0.U(counterWidth.W))))
  val minStepsToEdge   = DensePrefixSum(timeToNextEdge)({ case (a, b) => Mux(a < b, a, b) }).last

  io.bits := VecInit(for ((reg, period) <- timeToNextEdge.zip(clockPeriodicity)) yield {
    val clockFiring = reg === minStepsToEdge
    when (io.ready) {
      reg := Mux(clockFiring, period.U, reg - minStepsToEdge)
    }
    clockFiring
  })
}
