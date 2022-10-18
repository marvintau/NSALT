
package nutcore.frontend.fetch.branch_predict

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import nutcore._

import utils._
import top.Settings

class Embedded extends NutCoreModule {
  val io = IO(new Bundle {
    val in = new Bundle { val pc = Flipped(Valid((UInt(32.W)))) }
    val out = new RedirectIO
    val flush = Input(Bool())
  })

  val req = WireInit(0.U.asTypeOf(new BranchPredictUpdateRequestPort))
  // BoringUtils.addSink(req, "bpuUpdateReq")

  val flush = BoolStopWatch(io.flush, io.in.pc.valid, startHighPriority = true)

  // BTB
  val NRbtb = 512
  val entryAddr = new EntryAddr(log2Up(NRbtb))

  val (btbReadVec, btbHitVec) = BranchTarget(NRbtb, 1, entryAddr, io.in.pc, flush, req)
  val btbRead = btbReadVec(0)
  val btbHit = btbHitVec(0)

  // PHT
  val phtTaken = PatternHistory( 4, NRbtb,  entryAddr, io.in.pc, req )(0)

  // RAS
  val NRras = 16
  val rasTarget = ReturnAddressStack( NRras, io.in.pc, req )

  io.out.target := Mux(btbRead._type === BranchType.R, rasTarget, btbRead.target)
  io.out.valid := btbHit && Mux(btbRead._type === BranchType.B, phtTaken, true.B)
  io.out.rtype := 0.U
}

