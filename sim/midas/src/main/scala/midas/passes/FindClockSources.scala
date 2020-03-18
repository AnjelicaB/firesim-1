// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.ir._
import firrtl.annotations._
import firrtl.annotations.TargetToken.{OfModule, Instance}
import firrtl.graph.{DiGraph}
import firrtl.analyses.InstanceGraph

import midas.passes.fame.RTRenamer

case class FindClockSourceAnnotation(
    target: ReferenceTarget,
    originalTarget: Option[ReferenceTarget] = None) extends Annotation {
  require(target.module == target.circuit, s"Queried leaf clock ${target} must provide an absolute instance path")
  def update(renames: RenameMap): Seq[FindClockSourceAnnotation] =
    Seq(this.copy(RTRenamer.exact(renames)(target), originalTarget.orElse(Some(target))))
}

case class ClockSourceAnnotation(queryTarget: ReferenceTarget, source: Option[ReferenceTarget]) extends Annotation {
  def update(renames: RenameMap): Seq[ClockSourceAnnotation] =
    Seq(this.copy(queryTarget, source.map(s => RTRenamer.exact(renames)(s))))
}

object FindClockSources extends firrtl.Transform {
  def inputForm = LowForm
  def outputForm = LowForm
  private def getSourceClock(moduleGraphs: Map[String,DiGraph[LogicNode]])
                            (rT: ReferenceTarget): Option[ReferenceTarget] = {
    val modulePath   = (OfModule(rT.module) +: rT.path.map(_._2)).reverse
    val instancePath = (None +: rT.path.map(tuple => Some(tuple._1))).reverse

    def walkModule(currentNode: LogicNode, path: Seq[(Option[Instance], OfModule)]): Option[LogicNode] = {
      require(path.nonEmpty)
      val (instOpt, module) :: restOfPath = path
      val mGraph = moduleGraphs(module.value)
      // Check if the current node is itself a source. This to handle references to
      // clocks in the top-level module. All other references must traverse the
      // graph. Note: The graph is directed from clock-leaves to clock-sources
      val source = if (mGraph.findSinks(currentNode)) currentNode else {
        val potentialSources =  mGraph.reachableFrom(currentNode)
        // FIXME: There should only be a single source here.
        assert(potentialSources.size == 1)
        potentialSources.head
      }
      (restOfPath, source) match {
        // Source is a port on the top level module -> we're done
        case (Nil, LogicNode(_, None, _)) => Some(source)
        // Source and sink nodes are the same node -> unconnected clock port
        case (_, LogicNode(_, Some(_),_)) if source == currentNode => None
        // Source is a port but we are not yet at the top; recurse into parent module
        case (nonEmptyPath, _) => walkModule(LogicNode(source.name, instOpt.map(_.value)), nonEmptyPath)
      }
    }
    val sourceClock = walkModule(LogicNode(rT.ref), instancePath.zip(modulePath))
    sourceClock.map(sC => rT.moduleTarget.ref(sC.name))
  }

  def analyze(state: CircuitState, queryTargets: Iterable[ReferenceTarget]): Map[ReferenceTarget, Option[ReferenceTarget]] = {
    queryTargets.foreach(t => {
      require(t.component == Nil)
      require(t.module == t.circuit, s"Queried leaf clock ${t} must provide an absolute instance path")
    })
    val moduleMap = state.circuit.modules.map({m => (m.name,m) }).toMap
    val connectivity = new CheckCombLoops().analyzeFull(state)
    val qTsByModule = queryTargets.groupBy(_.encapsulatingModule)

    val clockConnectivity = connectivity map { case (module, subgraph) =>
        val clockPorts = moduleMap(module).ports.collect { case Port(_, name, _, ClockType) => LogicNode(name) }
        val queriedNodes = qTsByModule.getOrElse(module, Seq()).map(rT => LogicNode(rT.ref))
        val simplifiedSubgraph = subgraph.simplify((clockPorts ++ queriedNodes).toSet)
        module -> simplifiedSubgraph
    }
    (queryTargets.map(qT => qT -> getSourceClock(clockConnectivity.toMap)(qT))).toMap
  }

  def execute(state: CircuitState): CircuitState = {
    val queryAnnotations = state.annotations.collect({ case anno: FindClockSourceAnnotation => anno })
    val sourceMappings = analyze(state, queryAnnotations.map(_.target))
    val clockSourceAnnotations = queryAnnotations.map(qAnno =>
      ClockSourceAnnotation(qAnno.originalTarget.getOrElse(qAnno.target), sourceMappings(qAnno.target)))
    val prunedAnnos = state.annotations.flatMap({
      case _: FindClockSourceAnnotation => None
      case o => Some(o)
    })
    state.copy(annotations = clockSourceAnnotations ++ prunedAnnos)
  }
}
