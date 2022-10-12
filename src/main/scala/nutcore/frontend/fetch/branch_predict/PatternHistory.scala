
package nutcore.fetch.branch_predict

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import nutcore._

import utils._
import top.Settings 
 
class PatternHistory(val sets: Int, val entryNum: Int, val addr: EntryAddr) extends Module {

  val io = IO(new Bundle {

    val pc = Flipped(Valid((UInt(32.W))))

    val out = Output(Vec(sets, Bool()))

    // Originally connected via BoringUtil
    val req = new BranchPredictUpdateRequestPort()
  })

  // 2 for first two bits of branch target buffer count
  val table = List.fill(sets)(Mem(entryNum >> 2, UInt(2.W)))
  val taken = Wire(Vec(sets, Bool()))
  (0 to sets - 1).map(i => {
    val firstBit = table(i)
      .read(addr.getIdx(io.pc.bits))(1)
    val outReg = RegEnable(firstBit, io.pc.valid)
    taken(i) := outReg
  })

  val countList = List
    .tabulate(sets)(i => (i.U -> table(i).read(addr.getIdx(io.req.pc))))

  val count = LookupTree(io.req.pc(2,1), countList)
  val countPrev = RegNext(count)
  val reqPrev  = RegNext(io.req)
  
  when (reqPrev.valid && ALUOpType.isBranch(reqPrev.fuOpType)) {
    val taken = reqPrev.actualTaken
    val countNext = Mux(taken, countPrev + 1.U, countPrev - 1.U)
    val ready = (taken && (countPrev =/= "b11".U)) || (!taken && (countPrev =/= "b00".U))
    when (ready) {
      (0 to sets - 1)
        .map(i => when(i.U === reqPrev.pc(2,1)){
          table(i).write(addr.getIdx(reqPrev.pc), countNext)
        })
    }
  }

  io.out := taken

}

object PatternHistory {
  def apply(sets: Int, entryNum: Int, addr: EntryAddr, pc: Valid[UInt], req: BranchPredictUpdateRequestPort) = {
    val pht = Module(new PatternHistory(sets, entryNum, addr))
    pht.io.pc := pc
    pht.io.req := req
    pht.io.out 
  }
}