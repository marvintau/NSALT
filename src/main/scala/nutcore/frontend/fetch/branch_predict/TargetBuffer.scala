
// package nutcore.fetch.branch_predict

// import chisel3._
// import chisel3.util._
// import chisel3.util.experimental.BoringUtils
// import nutcore._

// import utils._
// import top.Settings

// // NOTE: BranchTargetBuffer
// // ========================
// // BranchTargetBuffer is excerpted from the BPU part of the origianl NutShell. We made several
// // modifications:
// //
// // 1. It was not a module. Now we make it one.
// //
// // 2. The update request was not transferred through standard IO port (by "boring in"). Now we
// //    make it is.
// // 
// // Currently we are working on the Embedded version, since:
// // 1. We are not dealing with Dynamic Execution yet
// // 2. The main difference between BPU_embedded and BPU_inorder is RVC, the compressed instrs.
// //    We are not dealing with RVC either.
// //  
// // Will be coping with that soon.

// // Found at:
// // https://github.com/OSCPU/NutShell/blob/fd86beadfc47f52973270ce6109edebd2a30363b/src/main/scala/nutcore/frontend/BPU.scala#L74
// // 
// // Docs:
// // https://oscpu.gitbook.io/nutshell/gong-neng-bu-jian-she-ji-xi-jie/bpu#yu-ce-ji-zhi


// object BoolStopWatch {
//   def apply(start: Bool, close: Bool, startHighPriority: Boolean = false) = {

//     val state = RegInit(false.B)
    
//     if (startHighPriority) {
//       when (close) { state := false.B }
//       when (start) { state := true.B }
//     }
//     else {
//       when (start) { state := true.B }
//       when (close) { state := false.B }
//     }

//     state
//   }
// }

// class TargetBuffer(sets: Int, addr: EntryAddr) extends Module with Formal{

//   val entryType = new Bundle {
//     val tag        = UInt(addr.TAG_LEN.W)
//     val branchType = UInt(2.W)
//     val target     = UInt(32.W)
//   }

//   val io = IO(new Bundle {

//     val in = new BranchPredictUpdateRequestPort()

//     val out = Output(entryType)
//     val hit = Output(Bool())
//   })

//   // Define the flush signal. mem will be set flush when io.flush fired, until
//   // next valid pc comes in.

//   val flush = BoolStopWatch(io.in.flush, io.in.pc.valid, startHighPriority = true)

//   // ==========================================================================
//   // FORMAL VERIFICATION
//   //
//   // 1. When the incoming flush signal is true, the flush will be always true
//   //    IN THE NEXT CYCLE.
//   //
//   // 2. When incoming flush is false, the outcome flush depends on the incoming
//   //    valid signal and previous flush stored in the register.

//   // **Assertions that NOT working, wondering WHY?**
  
//   // val prevFlush = ShiftRegister(io.in.flush, 1)
//   // val prevValid = ShiftRegister(io.in.pc.valid, 1)
//   // when (prevFlush) {
//   //   assert(flush === true.B)
//   // }.elsewhen(prevValid) {
//   //   assert(flush === false.B)
//   // }.otherwise{
//   //   assert(flush === RegNext(flush))
//   // }

//   past(io.in.flush, 1) {
//     prevFlush => when(prevFlush) {
//       assert(flush === true.B)
//     }
//     .otherwise {
//       past(io.in.pc.valid, 1){
//         prevValid => when(prevValid) {
//           assert( flush === false.B) 
//           }.otherwise {
//             assert(flush === RegNext(flush))
//           }
//       }
//     }
//   }

//   // ENDFORMAL

//   // branch target buffer is just a SRAM. When a valid PC comes in, make a read
//   // request from the buffer, trying to fetch a record with given address from
//   // the incoming PC.

//   val mem = Module(new SyncMem(entryType, sets = sets))

//   // After sending the result to io.out.entry, it would take a few cycles to get
//   // the result of execution, telling if the branch prediction is correct. If not,
//   // the corresponding entry in the buffer will be updated.
//   //
//   // The detail of fb found in Feedback.scala.

//   val fb = io.in.feed
//   val written = WireInit(0.U.asTypeOf(entryType))

//   written.tag := addr.getTag(fb.pc)
//   written.target := fb.pc
//   written.branchType := fb.branchType

//   mem.io.w.req.valid := fb.isMispred && fb.valid
//   mem.io.w.req.bits.index := addr.getIdx(fb.pc)
//   mem.io.w.req.bits.data := written

//   // Read out the buffer entry from the SRAM.

//   mem.io.r.req.valid := io.in.pc.valid
//   mem.io.r.req.bits.index := addr.getIdx(io.in.pc.bits)

//   // setup output place. The response data from SRAM will be stored here.

//   io.out := mem.io.r.res.data(0)

//   // ==========================================================================
//   // FORMAL VERIFICATION

//   val anyAddr = anyconst(log2Up(sets))
//   val reqW = mem.io.w.req
//   val reqR = mem.io.r.req

//   // when incoming feedback is valid and missed, begin to update the buffer.
//   when(fb.isMispred && fb.valid) {
//     assert(reqW.valid)
//   }
//   // condition for writing
//   val Seq(prevOnReset, prev2OnReset) = ShiftRegisters(mem.io.onReset, 2)

//   val Seq(prevValidReqW, prev2ValidReqW) = ShiftRegisters(reqW.valid, 2) 
//   val prev2AddrReqW = ShiftRegister(reqW.bits.index, 2)

//   val Seq(prevReadyReqR, prev2ReadyReqR) = ShiftRegisters(reqR.ready, 2)
//   val prevAddrReqR = RegNext(reqR.bits.index)

//   // This is the condition when valid to write to memory:
//   // 1. memory is not onReset
//   // 2. incoming data is valid
//   when(!prev2OnReset && prev2ValidReqW && prev2AddrReqW === anyAddr) {

//     when(!mem.io.onReset && reqW.valid) {
//       assert(reqR.ready)
//     }

//     // reading condition is specified within this when clause. No that both
//     // reading and writing cause 1-cycle delay. Thus when we proceed to current
//     // cycle, we may get the data that written 2 cycles ago.

//     when(reqR.ready && prevReadyReqR && prevAddrReqR === anyAddr) {
//       assert(written.asUInt() === io.out.asUInt())
//     }

//   }

//   // when [not onReset] AND [read enabled] for BOTH last-of-last and last cycle. 
//   when(prev2ReadyReqR && prevReadyReqR && reqR.ready) {

//     // the read result of current cycle (requested at last cycle) should be identical
//     // to it in last cycle (requested at last-of-last cycle)
//     assert(io.out.asUInt() === RegNext(io.out).asUInt())
//   }

//   // store a copy of incoming PC. If the tag of incoming PC is equal to the read
//   // one, then hit. It also need to meet the condition that we are not in flushing,
//   // AND we are reading from the buffer. Otherwise it could be an occassional hit,
//   // since the entry could be the previous read.
  
//   val prevPc = RegEnable(io.in.pc.bits, io.in.pc.valid)
  
//   io.hit := !flush && 
//     io.out.tag === addr.getTag(prevPc) && 
//     RegNext(mem.io.r.req.ready, init = false.B)


// } 


