
package nutcore.frontend.fetch.branch_predict

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import nutcore._

import utils._
import top.Settings

class Sequential extends NutCoreModule {
  val io = IO(new Bundle {
    val in = new Bundle { val pc = Flipped(Valid((UInt(VAddrBits.W)))) }
    val out = new RedirectIO
    val flush = Input(Bool())
    val brIdx = Output(UInt(3.W))
    val crosslineJump = Output(Bool())
  })


  val flush = BoolStopWatch(io.flush, io.in.pc.valid, startHighPriority = true)

  val req = WireInit(0.U.asTypeOf(new BranchPredictUpdateRequestPort))
  // BoringUtils.addSink(req, "bpuUpdateReq")

  // BTB
  val NRbtb = 512
  val entryAddr = new EntryAddr(log2Up(NRbtb))

  val (btbReadVec, btbHitVec) = BranchTarget(NRbtb, 1, entryAddr, io.in.pc, flush, req)
  val btbRead = btbReadVec(0)
  val btbHit = btbHitVec(0)

  // val btbRead = Wire(btbEntry())
  // btbRead := btb.io.r.resp.data(0)
  // since there is one cycle latency to read SyncReadMem,
  // we should latch the input pc for one cycle
  // val pcLatch = RegEnable(io.in.pc.bits, io.in.pc.valid)
  // val btbHit = btbRead.valid && btbRead.tag === entryAddr.getTag(pcLatch) && !flush && RegNext(btb.io.r.req.fire(), init = false.B) && !(pcLatch(1) && btbRead.brIdx(0))
  // btbHit will ignore pc(1,0). pc(1,0) is used to build brIdx
  // !(pcLatch(1) && btbRead.brIdx(0)) is used to deal with the following case:
  // -------------------------------------------------
  // 0 jump rvc         // marked as "take branch" in BTB
  // 2 xxx  rvc <-- pc  // misrecognize this instr as "btb hit" with target of previous jump instr
  // -------------------------------------------------
  val crosslineJump = btbRead.brIdx(2) && btbHit
  io.crosslineJump := crosslineJump
  // val crosslineJumpLatch = RegNext(crosslineJump)
  // val crosslineJumpTarget = RegEnable(btbRead.target, crosslineJump)
  // Debug(btbHit, "[BTBHT1] %d pc=%x tag=%x,%x index=%x bridx=%x tgt=%x,%x flush %x type:%x\n", GTimer(), pcLatch, btbRead.tag, entryAddr.getTag(pcLatch), entryAddr.getIdx(pcLatch), btbRead.brIdx, btbRead.target, io.out.target, flush,btbRead._type)
  // Debug(btbHit, "[BTBHT2] btbRead.brIdx %x mask %x\n", btbRead.brIdx, Cat(crosslineJump, Fill(2, io.out.valid)))
  // Debug(btbHit, "[BTBHT5] btbReqValid:%d btbReqSetIdx:%x\n",btb.io.r.req.valid, btb.io.r.req.bits.setId)
  
  // PHT
  val phtTaken = PatternHistory( 4, NRbtb,  entryAddr, io.in.pc, req )(0)

  // RAS
  val NRras = 16
  val rasTarget = ReturnAddressStack( NRras, io.in.pc, req)

  Debug(req.valid, "[BTBUP] pc=%x tag=%x index=%x bridx=%x tgt=%x type=%x\n", req.pc, entryAddr.getTag(req.pc), entryAddr.getIdx(req.pc), Cat(req.pc(1), ~req.pc(1)), req.actualTarget, req.btbType)

  io.out.target := Mux(btbRead._type === BranchType.R, rasTarget, btbRead.target)
  // io.out.target := Mux(crosslineJumpLatch && !flush, crosslineJumpTarget, Mux(btbRead._type === BranchType.R, rasTarget, btbRead.target))
  // io.out.brIdx  := btbRead.brIdx & Fill(3, io.out.valid)
  io.brIdx  := btbRead.brIdx & Cat(true.B, crosslineJump, Fill(2, io.out.valid))
  io.out.valid := btbHit && Mux(btbRead._type === BranchType.B, phtTaken, true.B && rasTarget=/=0.U) //TODO: add rasTarget=/=0.U, need fix
  io.out.rtype := 0.U
  // io.out.valid := btbHit && Mux(btbRead._type === BranchType.B, phtTaken, true.B) && !crosslineJump || crosslineJumpLatch && !flush && !crosslineJump
  // Note: 
  // btbHit && Mux(btbRead._type === BranchType.B, phtTaken, true.B) && !crosslineJump : normal branch predict
  // crosslineJumpLatch && !flush && !crosslineJump : cross line branch predict, bpu will require imem to fetch the next 16bit of current inst in next instline
  // `&& !crosslineJump` is used to make sure this logic will run correctly when imem stalls (pcUpdate === false)
  // by using `instline`, we mean a 64 bit instfetch result from imem
  // ROCKET uses a 32 bit instline, and its IDU logic is more simple than this implentation.
}
