
package nutcore.frontend.fetch.branch_predict

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import nutcore._

import utils._
import top.Settings 

class BranchTarget(val entryNum:Int, val sets:Int, entryAddr: EntryAddr) extends NutCoreModule {

  def entry() = new Bundle {
    val tag = UInt(entryAddr.tagBits.W)
    val _type = UInt(2.W)
    val target = UInt(32.W)

    // used by Dynamic
    val crosslineJump = Bool()
    val valid = Bool()

    // used by Sequential
    val brIdx = UInt(3.W)

  }

  val io = IO(new Bundle {

    val flush = Input(Bool())
    
    val pc = Flipped(Valid((UInt(32.W))))

    val out = Output(Vec(sets, entry()))

    val hit = Output(Vec(sets, Bool()))

    // Originally connected via BoringUtil
    val req = Flipped(new BranchPredictUpdateRequestPort())
  })

  val entryNumPerSet = entryNum / sets
  
  // Event 
  val branchTarget = List.fill(sets)(
    Module(new SRAMTemplate(
      entry(), 
      set = entryNumPerSet, 
      shouldReset = true, 
      holdRead = true, 
      singlePort = true
    ))
  )

  // flush BTB when executing fence.i
  val flushBTB = WireInit(false.B)
  val flushTLB = WireInit(false.B)
  BoringUtils.addSink(flushBTB, "MOUFlushICache")
  BoringUtils.addSink(flushTLB, "MOUFlushTLB")
  Debug(reset.asBool || (flushBTB || flushTLB), "[BPU-RESET] bpu-reset flushBTB:%d flushTLB:%d\n", flushBTB, flushTLB)

  // wiring for each set
  (0 to sets - 1).map(i => {
    branchTarget(i).reset := reset.asBool || (flushBTB || flushTLB)
    branchTarget(i).io.r.req.valid := io.pc.valid
    branchTarget(i).io.r.req.bits.setIdx := entryAddr.getIdx(io.pc.bits)
  })

  // entries read out
  val branchTargetRead = Wire(Vec(sets, entry()))
  (0 to sets - 1).map( i => {
    branchTargetRead(i) := branchTarget(i).io.r.resp.data(0)
  })

  // since there is one cycle latency to read SyncReadMem,
  // we should latch the input pc for one cycle

  val branchTargetHit = Wire(Vec(sets, Bool()))
  val pcDelayed = RegEnable(io.pc.bits, io.pc.valid)
  (0 to sets - 1).map(i => {
    val readValid = branchTargetRead(i).valid
    val tagMatched = branchTargetRead(i).tag === entryAddr.getTag(pcDelayed)
    val afterReq = RegNext(branchTarget(i).io.r.req.fire(), init = false.B)
    branchTargetHit(i) := !io.flush && readValid && tagMatched && afterReq
  })

  // write port
  // incoming update request that wired into each set, but the only entry with matched PC will
  // written
  val branchTargetWrite = WireInit(0.U.asTypeOf(entry()))

  branchTargetWrite.tag := entryAddr.getTag(io.req.pc)
  branchTargetWrite.target := io.req.actualTarget
  branchTargetWrite._type := io.req.btbType
  branchTargetWrite.valid := true.B 

  // updated only in dynamic
  branchTargetWrite.crosslineJump := io.req.pc(2,1)==="h3".U && !io.req.isRVC // ((pc_offset % 8) == 6) && inst is 32bit in length
  
  // updated only in sequential, further used for calculating crossline-jump
  // externally
  branchTargetWrite.brIdx := Cat(io.req.pc(2,0)==="h6".U && !io.req.isRVC, io.req.pc(1), ~io.req.pc(1))


  // For sequential or embedded, there is no distinguishing on different sets.
  (0 to sets - 1).map(i => {
    branchTarget(i).io.w.req.valid := io.req.isMissPredict && io.req.valid && i.U === io.req.pc(2,1)
    branchTarget(i).io.w.req.bits.setIdx := entryAddr.getIdx(io.req.pc)
    branchTarget(i).io.w.req.bits.data := branchTargetWrite    
  })

  io.out := branchTargetRead
  io.hit := branchTargetHit
}

object BranchTarget {
  def apply (entryNum: Int, sets: Int, entryAddr:EntryAddr, pc: Valid[UInt], flush: Bool, req: BranchPredictUpdateRequestPort) = {
    val btb = Module(new BranchTarget(entryNum, sets, entryAddr))
    btb.io.flush := flush
    btb.io.pc := pc
    btb.io.req := req
    (btb.io.out, btb.io.hit)
  }
}