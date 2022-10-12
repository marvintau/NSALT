
class BranchTarget(val entryNum:Int, val sets:Int) extends NutCoreModule {

  def entry() = new Bundle {
    val tag = UInt(entryAddr.tagBits.W)
    val _type = UInt(2.W)
    val target = UInt(32.W)

    // used by Dynamic
    val crosslineJump = Bool()
    val valid = Bool()

    // used by Sequential
    val brIdx = Output(UInt(3.W))
    val flush = Input(Bool())

  }

  val io = IO(new Bundle {

    val pc = Flipped(Valid((UInt(32.W))))

    val out = Output(Vec(sets, entry()))

    // Originally connected via BoringUtil
    val req = new BranchPredictUpdateRequestPort()
  })

  val flush = BoolStopWatch(io.flush, io.in.pc.valid, startHighPriority = true)

  val entryNum = 512
  val entryNumPerSet = entryNum / sets
  val entryAddr = new EntryAddr(log2Up(entryNumPerSet))
  
  // val branchTarget = Module(new SRAMTemplate(entry(), set = entryNum, shouldReset = true, holdRead = true, singlePort = true))
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

  (0 to sets).map(i => {
    branchTarget(i).reset := reset.asBool || (flushBTB || flushTLB)
    branchTarget(i).io.r.req.valid := io.pc.valid
    branchTarget(i).io.r.req.bits.setIdx := entryAddr.getIdx(io.pc.bits)
  })

  branchTarget.io.r.req.valid := io.in.pc.valid
  branchTarget.io.r.req.bits.setIdx := entryAddr.getIdx(io.in.pc.bits)

  (0 to sets).map( i => {
    io.out(i) := branchTarget(i).io.r.resp.data(0)
  })
  // since there is one cycle latency to read SyncReadMem,
  // we should latch the input pc for one cycle
  val pcLatch = RegEnable(io.in.pc.bits, io.in.pc.valid)
  val branchTargetHit = branchTargetRead.tag === entryAddr.getTag(pcLatch) && !flush && RegNext(branchTarget.io.r.req.ready, init = false.B)

}