
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

  // btbWrite.tag := entryAddr.getTag(req.pc)
  // btbWrite.target := req.actualTarget
  // btbWrite._type := req.btbType
  // NOTE: We only update BTB at a miss prediction.
  // If a miss prediction is found, the pipeline will be flushed
  // in the next cycle. Therefore it is safe to use single-port
  // SRAM to implement BTB, since write requests have higher priority
  // than read request. Again, since the pipeline will be flushed
  // in the next cycle, the read request will be useless.
  // btb.io.w.req.valid := req.isMissPredict && req.valid
  // btb.io.w.req.bits.setIdx := entryAddr.getIdx(req.pc)
  // btb.io.w.req.bits.data := btbWrite

  // val cnt = RegNext(pht.read(entryAddr.getIdx(req.pc)))
  // val reqLatch = RegNext(req)
  // when (reqLatch.valid && ALUOpType.isBranch(reqLatch.fuOpType)) {
  //   val taken = reqLatch.actualTaken
  //   val newCnt = Mux(taken, cnt + 1.U, cnt - 1.U)
  //   val wen = (taken && (cnt =/= "b11".U)) || (!taken && (cnt =/= "b00".U))
  //   when (wen) {
  //     pht.write(entryAddr.getIdx(reqLatch.pc), newCnt)
  //   }
  // }
  // when (req.valid) {
  //   when (req.fuOpType === ALUOpType.call) {
  //     ras.write(sp.value + 1.U, req.pc + 4.U)
  //     sp.value := sp.value + 1.U
  //   }
  //   .elsewhen (req.fuOpType === ALUOpType.ret) {
  //     sp.value := sp.value - 1.U
  //   }
  // }

  // val flushBTB = WireInit(false.B)
  // val flushTLB = WireInit(false.B)
  // BoringUtils.addSink(flushBTB, "MOUFlushICache")
  // BoringUtils.addSink(flushTLB, "MOUFlushTLB")

  io.out.target := Mux(btbRead._type === BranchType.R, rasTarget, btbRead.target)
  io.out.valid := btbHit && Mux(btbRead._type === BranchType.B, phtTaken, true.B)
  io.out.rtype := 0.U
}

