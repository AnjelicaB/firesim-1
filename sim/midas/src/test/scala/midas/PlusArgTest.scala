
package goldengate.tests

import chisel3._
import chisel3.util._
import chisel3.experimental.{IO, annotate, DataMirror}
import chisel3.stage.{ChiselCircuitAnnotation, ChiselGeneratorAnnotation, ChiselStage, CircuitSerializationAnnotation}

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.TestSuite

import firrtl._
import firrtl.annotations.{DeletedAnnotation, JsonProtocol}
import firrtl.options.TargetDirAnnotation
import firrtl.stage.{RunFirrtlTransformAnnotation, FirrtlCircuitAnnotation}
import firrtl.transforms.DontTouchAnnotation


import midas.stage._

import freechips.rocketchip.prci._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.{Config, Field, Parameters}

import java.io.{File, PrintWriter}
import freechips.rocketchip.util.DecoupledHelper




class TL(w: Int) extends Bundle {
  val a = Decoupled(UInt(w.W))
  val d = Flipped(Decoupled(UInt(w.W)))
}

class Foo(w: Int) extends Module {
  val io = IO(new Bundle {
    val x = new TL(w)
  })
  dontTouch(io)

  val data = RegInit(0.U(64.W))

  val a_val = RegInit(false.B)
  a_val := !a_val

  io.x.a.valid := a_val && io.x.a.ready
  io.x.a.bits  := data

  val d_rdy = RegInit(false.B)
  d_rdy := !d_rdy
  io.x.d.ready := d_rdy

  when (io.x.d.fire) {
    data := io.x.d.bits
  }
}

class Bar(w: Int) extends Module {
  val io = IO(new Bundle {
    val y = Flipped(new TL(w))
    val success = Output(Bool())
  })
  dontTouch(io)

  val can_fire = RegInit(false.B)
  can_fire := !can_fire

  val resp_fire = DecoupledHelper(
    io.y.a.valid,
    io.y.d.ready,
    can_fire)

  io.y.a.ready := resp_fire.fire(io.y.a.valid)
  io.y.d.valid := resp_fire.fire(io.y.d.ready)
  io.y.d.bits  := RegNext(io.y.a.bits)

  io.success := resp_fire.fire
}


class Top(w: Int) extends Module {
  val io = IO(new Bundle {
    val success = Output(Bool())
  })
  val foo = Module(new Foo(w))
  val bar = Module(new Bar(w))

  bar.io.y.a <> foo.io.x.a
  foo.io.x.d <> bar.io.y.d

  io.success := bar.io.success
}




trait GoldenGateCompilerTest { this: TestSuite =>
  protected def annos: AnnotationSeq = Seq()

  protected def compile[M <: RawModule](gen: => M, target: String, a: AnnotationSeq = List(), ll: String = "warn"): (String, AnnotationSeq) = {
    val stage = new ChiselStage

    val testName = this.suiteName
    val testRunDir = TargetDirAnnotation("test_run_dir" + File.separator + testName)

    val r = stage.execute(Array("-X", target, "-ll", ll), ChiselGeneratorAnnotation(() => gen) +: testRunDir +: a ++: annos)
    val src = r.collect {
      case EmittedFirrtlCircuitAnnotation(a) => a
      case EmittedFirrtlModuleAnnotation(a) => a
      case EmittedVerilogCircuitAnnotation(a) => a
      case EmittedVerilogModuleAnnotation(a) => a
    }.map(_.value).mkString("")
    (src, r)
  }
}

class CheckCombFirrtlGenerator extends AnyFreeSpec with GoldenGateCompilerTest {
  def generateFirrtl() = {
    val (firrtl, _) = compile(new Top(2), "low", a=Seq())

    val firrtlWriter = new PrintWriter(new File("midas/test-inputs/simple.fir"))
    firrtlWriter.write(firrtl)
    firrtlWriter.close()
  }
}

class GenerateFirrtlForCheckCombLogic extends CheckCombFirrtlGenerator {
  generateFirrtl()
}
