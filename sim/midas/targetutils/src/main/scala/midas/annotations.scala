// See LICENSE for license details.

package midas.targetutils

import chisel3._
import chisel3.experimental.{BaseModule, ChiselAnnotation, annotate}

import firrtl.{RenameMap}
import firrtl.annotations.{Annotation, NoTargetAnnotation, SingleTargetAnnotation, ComponentName} // Deprecated
import firrtl.annotations.{ReferenceTarget, InstanceTarget, ModuleTarget, AnnotationException}

// This is currently consumed by a transformation that runs after MIDAS's core
// transformations In FireSim, targeting an F1 host, these are consumed by the
// AutoILA infrastucture (ILATopWiring pass) to generate an ILA that plays nice
// with AWS's vivado flow
case class FpgaDebugAnnotation(target: chisel3.Data) extends ChiselAnnotation {
  def toFirrtl = FirrtlFpgaDebugAnnotation(target.toNamed)
}

case class FirrtlFpgaDebugAnnotation(target: ComponentName) extends
    SingleTargetAnnotation[ComponentName] {
  def duplicate(n: ComponentName) = this.copy(target = n)
}

object FpgaDebug {
  def apply(targets: chisel3.Data*): Unit = {
    targets.map({ t => chisel3.experimental.annotate(FpgaDebugAnnotation(t)) })
    targets.map(dontTouch(_))
  }
}

private[midas] class ReferenceTargetRenamer(renames: RenameMap) {
  // TODO: determine order for multiple renames, or just check of == 1 rename?
  def exactRename(rt: ReferenceTarget): ReferenceTarget = {
    val renameMatches = renames.get(rt).getOrElse(Seq(rt)).collect({ case rt: ReferenceTarget => rt })
    assert(renameMatches.length == 1)
    renameMatches.head
  }
  def apply(rt: ReferenceTarget): Seq[ReferenceTarget] = {
    renames.get(rt).getOrElse(Seq(rt)).collect({ case rt: ReferenceTarget => rt })
  }
}

private [midas] case class SynthPrintfAnnotation(
    args: Seq[Seq[ReferenceTarget]], // These aren't currently used; here for future proofing
    mod: ModuleTarget,
    format: String,
    name: Option[String]) extends firrtl.annotations.Annotation {

  def update(renames: RenameMap): Seq[firrtl.annotations.Annotation] = {
    val renamer = new ReferenceTargetRenamer(renames)
    val renamedArgs = args.map(_.flatMap(renamer(_)))
    val renamedMod = renames.get(mod).getOrElse(Seq(mod)).collect({ case mt: ModuleTarget => mt })
    assert(renamedMod.size == 1) // To implement: handle module duplication or deletion
    Seq(this.copy(args = renamedArgs, mod = renamedMod.head ))
  }
}

// HACK: We're going to reuse the format to find the printf, from which we can grab the printf's enable
private[midas] case class ChiselSynthPrintfAnnotation(
    format: String,
    args: Seq[Bits],
    mod: BaseModule,
    name: Option[String]) extends ChiselAnnotation {
  def getTargetsFromArg(arg: Bits): Seq[ReferenceTarget] = {
    // To named throughs an exception on literals right now, so dumbly catch everything
    try {
      Seq(arg.toNamed.toTarget)
    } catch {
      case AnnotationException(_) => Seq()
    }
  }

  def toFirrtl() = SynthPrintfAnnotation(args.map(getTargetsFromArg),
                                         mod.toNamed.toTarget, format, name)
}

// For now, this needs to be invoked on the arguments to printf, not on the printf itself
// Eg. printf(SynthesizePrintf("True.B or False.B: Printfs can be annotated: %b\n", false.B))
object SynthesizePrintf {
  private def generateAnnotations(format: String, args: Seq[Bits], name: Option[String]): Printable = {
    val thisModule = Module.currentModule.getOrElse(
      throw new RuntimeException("Cannot annotate a printf outside of a Module"))
    chisel3.experimental.annotate(ChiselSynthPrintfAnnotation(format, args, thisModule, name))
    Printable.pack(format, args:_*)
  }
  def apply(name: String, format: String, args: Bits*): Printable =
    generateAnnotations(format, args, Some(name))

  def apply(format: String, args: Bits*): Printable = generateAnnotations(format, args, None)

  // TODO: Accept a printable -> need to somehow get the format string from 
}


/**
  * A mixed-in ancestor trait for all FAME annotations, useful for type-casing.
  */
trait FAMEAnnotation {
  this: Annotation =>
}

/**
  * This labels an instance so that it is extracted as a separate FAME model.
  */
case class FAMEModelAnnotation(target: BaseModule) extends chisel3.experimental.ChiselAnnotation {
  def toFirrtl: FirrtlFAMEModelAnnotation = {
    val parent = ModuleTarget(target.toNamed.circuit.name, target.parentModName)
    FirrtlFAMEModelAnnotation(parent.instOf(target.instanceName, target.name))
  }
}

case class FirrtlFAMEModelAnnotation(
  target: InstanceTarget) extends SingleTargetAnnotation[InstanceTarget] with FAMEAnnotation {
  def targets = Seq(target)
  def duplicate(n: InstanceTarget) = this.copy(n)
}

/**
  * This specifies that the module should be automatically multi-threaded (Chisel annotator).
  */
case class EnableModelMultiThreadingAnnotation(target: BaseModule) extends chisel3.experimental.ChiselAnnotation {
  def toFirrtl: FirrtlEnableModelMultiThreadingAnnotation = {
    FirrtlEnableModelMultiThreadingAnnotation(target.toNamed.toTarget)
  }
}

/**
  * This specifies that the module should be automatically multi-threaded (FIRRTL annotation).
  */
case class FirrtlEnableModelMultiThreadingAnnotation(
  target: ModuleTarget) extends SingleTargetAnnotation[ModuleTarget] with FAMEAnnotation {
  def targets = Seq(target)
  def duplicate(n: ModuleTarget) = this.copy(n)
}

/**
  * This labels a target Mem so that it is extracted and replaced with a separate model.
  */
case class MemModelAnnotation[T <: chisel3.Data](target: chisel3.MemBase[T])
    extends chisel3.experimental.ChiselAnnotation {
  def toFirrtl = FirrtlMemModelAnnotation(target.toNamed.toTarget)
}

case class FirrtlMemModelAnnotation(target: ReferenceTarget) extends
    SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(rt: ReferenceTarget) = this.copy(target = rt)
}

case class ExcludeInstanceAssertsAnnotation(target: (String, String)) extends
    firrtl.annotations.NoTargetAnnotation {
  def duplicate(n: (String, String)) = this.copy(target = n)
}
// TODO: Actually use a real target and not strings.
object ExcludeInstanceAsserts {
  def apply(target: (String, String)): ChiselAnnotation =
    new ChiselAnnotation {
      def toFirrtl = ExcludeInstanceAssertsAnnotation(target)
    }
}


//AutoCounter annotations
case class AutoCounterFirrtlAnnotation(
  target: ReferenceTarget,
  clock: ReferenceTarget,
  reset: ReferenceTarget,
  label: String,
  message: String,
  coverGenerated: Boolean = false)
    extends firrtl.annotations.Annotation {
  def update(renames: RenameMap): Seq[firrtl.annotations.Annotation] = {
    val renamer = new ReferenceTargetRenamer(renames)
    val renamedTarget = renamer.exactRename(target)
    val renamedClock  = renamer.exactRename(clock)
    val renamedReset  = renamer.exactRename(reset)
    Seq(this.copy(target = renamedTarget, clock = renamedClock, reset = renamedReset))
  }
  // The AutoCounter tranform will reject this annotation if it's not enclosed
  def shouldBeIncluded(modList: Seq[String]): Boolean = !coverGenerated || modList.contains(target.module)
  def enclosingModule(): String = target.module
  def enclosingModuleTarget(): ModuleTarget = ModuleTarget(target.circuit, enclosingModule)
}

case class AutoCounterCoverModuleFirrtlAnnotation(target: ModuleTarget) extends
    SingleTargetAnnotation[ModuleTarget] {
  def duplicate(n: ModuleTarget) = this.copy(target = n)
}

case class AutoCounterCoverModuleAnnotation(target: String) extends ChiselAnnotation {
  //TODO: fix the CircuitName arguemnt of ModuleTarget after chisel implements Target
  //It currently doesn't matter since the transform throws away the circuit name
  def toFirrtl =  AutoCounterCoverModuleFirrtlAnnotation(ModuleTarget("",target))
}

object PerfCounter {
  // Biancolin - WIP: Previously target was chisel3.data. Figure out why.
  def apply(target: chisel3.Bool,
            clock: chisel3.Clock,
            reset: Reset,
            label: String,
            message: String): Unit = {
    //Biancolin: WIP. Move the reset-driven gate into FIRRTL
    val predicate = !reset.toBool && target
    predicate.suggestName(label)
    dontTouch(predicate)
    annotate(new ChiselAnnotation {
      def toFirrtl = AutoCounterFirrtlAnnotation(predicate.toNamed.toTarget, clock.toNamed.toTarget,
        reset.toNamed.toTarget, label, message)
    })
  }

  def apply(target: chisel3.Bool, label: String, message: String): Unit =
    apply(target, Module.clock, Module.reset, label, message)
}
