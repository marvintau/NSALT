
package nutcore.frontend.fetch.branch_predict

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import nutcore._

import utils._
import top.Settings 
 
class ReturnAddressStack (val stackSize: Int) extends NutCoreModule {

  val io = IO(new Bundle {

    val pc = Flipped(Valid((UInt(32.W))))

    // val out = Output(Vec(sets, Bool()))
    val out = Output(UInt(VAddrBits.W))

    // Originally connected via BoringUtil
    val req = Flipped(new BranchPredictUpdateRequestPort())
  })

  val stack = Mem(stackSize, UInt(VAddrBits.W))
  val sp = Counter(stackSize)

  when (io.req.valid) {
    when (io.req.fuOpType === ALUOpType.call)  {
      stack.write(sp.value + 1.U, Mux(io.req.isRVC, io.req.pc + 2.U, io.req.pc + 4.U))
      sp.value := sp.value + 1.U
    }
    .elsewhen (io.req.fuOpType === ALUOpType.ret) {
      when(sp.value === 0.U) {
        // RAS empty, do nothing
      }
      sp.value := Mux(sp.value===0.U, 0.U, sp.value - 1.U)
    }
  }

  io.out := RegEnable(stack.read(sp.value), io.pc.valid)

}

object ReturnAddressStack {
  def apply (stackSize: Int, pc: Valid[UInt], req: BranchPredictUpdateRequestPort) = {
    val ras = Module(new ReturnAddressStack(stackSize))
    ras.io.pc := pc
    ras.io.req := req
    ras.io.out
  }
}