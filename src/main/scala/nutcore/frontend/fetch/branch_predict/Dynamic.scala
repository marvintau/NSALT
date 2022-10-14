
package nutcore.frontend.fetch.branch_predict

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import nutcore._

import utils._
import top.Settings

// nextline predictor generates NPC from current NPC in 1 cycle
class Dynamic extends NutCoreModule {

  val io = IO(new Bundle {
    val in = new Bundle { val pc = Flipped(Valid((UInt(VAddrBits.W)))) }
    val out = new RedirectIO 
    val flush = Input(Bool())
    val brIdx = Output(Vec(4, Bool()))
    // val target = Output(Vec(4, UInt(VAddrBits.W)))
    // val instValid = Output(UInt(4.W)) // now instValid is generated in IFU
    val crosslineJump = Output(Bool())
  })

  
  val flush = BoolStopWatch(io.flush, io.in.pc.valid, startHighPriority = true)

  val req = WireInit(0.U.asTypeOf(new BranchPredictUpdateRequestPort))
  // BoringUtils.addSink(req, "bpuUpdateReq")

  // BTB
  val NRbtb = 512
  val entryAddr = new EntryAddr(log2Up(NRbtb >> 2))

  val (btbRead, btbHit) = BranchTarget(NRbtb, 4, entryAddr, io.in.pc, flush, req)

  val crosslineJump = btbRead(3).crosslineJump && btbHit(3) && !io.brIdx(0) && !io.brIdx(1) && !io.brIdx(2)
  io.crosslineJump := crosslineJump
  // val crosslineJumpLatch = RegNext(crosslineJump)
  // val crosslineJumpTarget = RegEnable(btbRead.target, crosslineJump)

  // Pattern History Table
  val phtTaken = PatternHistory( 4, NRbtb,  entryAddr, io.in.pc, req )

  // RAS
  val NRras = 16
  val rasTarget = ReturnAddressStack( NRras, io.in.pc, req )

  def genInstValid(pc: UInt) = LookupTree(pc(2,1), List(
    "b00".U -> "b1111".U,
    "b01".U -> "b1110".U,
    "b10".U -> "b1100".U,
    "b11".U -> "b1000".U
  ))

  val pcLatchValid = genInstValid(RegNext(io.in.pc.bits))

  val target = Wire(Vec(4, UInt(VAddrBits.W)))
  (0 to 3).map(i => target(i) := Mux(btbRead(i)._type === BranchType.R, rasTarget, btbRead(i).target))
  (0 to 3).map(i => io.brIdx(i) := btbHit(i) && pcLatchValid(i).asBool && Mux(btbRead(i)._type === BranchType.B, phtTaken(i), true.B) && btbRead(i).valid)
  io.out.target := PriorityMux(io.brIdx, target)
  io.out.valid := io.brIdx.asUInt.orR
  io.out.rtype := 0.U
  // Debug(io.out.valid, "[BPU] pc %x io.brIdx.asUInt %b phtTaken %x %x %x %x valid %x %x %x %x\n", pcLatch, io.brIdx.asUInt, phtTaken(0), phtTaken(1), phtTaken(2), phtTaken(3), btbRead(0).valid, btbRead(1).valid, btbRead(2).valid, btbRead(3).valid)

  // io.out.valid := btbHit && Mux(btbRead._type === BranchType.B, phtTaken, true.B) && !crosslineJump || crosslineJumpLatch && !flush && !crosslineJump
  // Note: 
  // btbHit && Mux(btbRead._type === BranchType.B, phtTaken, true.B) && !crosslineJump : normal branch predict
  // crosslineJumpLatch && !flush && !crosslineJump : cross line branch predict, bpu will require imem to fetch the next 16bit of current inst in next instline
  // `&& !crosslineJump` is used to make sure this logic will run correctly when imem stalls (pcUpdate === false)
  // by using `instline`, we mean a 64 bit instfetch result from imem
  // ROCKET uses a 32 bit instline, and its IDU logic is more simple than this implentation.
}