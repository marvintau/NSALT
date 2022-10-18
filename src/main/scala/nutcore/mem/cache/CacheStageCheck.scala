
package nutcore.mem.cache

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

import nutcore._

import bus.simplebus._
import bus.axi4._
import chisel3.experimental.IO
import utils._
import top.Settings

class StageCheckIO(implicit val cacheConfig: CacheConfig) extends CacheBundle {
  val req = new SimpleBusReqBundle(userBits = userBits, idBits = idBits)
  val metas = Vec(Ways, new MetaBundle)
  val datas = Vec(Ways, new DataBundle)
  val hit = Output(Bool())
  val waymask = Output(UInt(Ways.W))
  val mmio = Output(Bool())
  val isForwardData = Output(Bool())
  val forwardData = Output(CacheDataArrayWriteBus().req.bits)
}

// Stale-Update-Revalidate
// ========================
// a common pattern that:
// - if there is incoming signal with valid (write enable), then forward
//   incoming signal as output, MEANWHILE store in a RegEnable, and
// - if incoming signal is not valid, then return the previously stored
//   result.
// 
// similar to BoolStopWatch

object SUR{
  def apply[T <:Data](validate: Bool, invalidate: Bool, incoming: T) = {
    
    val switch = RegInit(false.B)
    when (validate) {
      switch := true.B
    }
    when (invalidate) {
      switch := false.B
    }
    val stored = RegEnable(incoming, validate)

    (switch || validate, Mux(switch, incoming, stored))
  }

} 
// check
class CacheStageCheck(implicit val cacheConfig: CacheConfig) extends CacheModule {

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new StageMetaReadIO))
    val out = Decoupled(new StageCheckIO)
    val metaReadResp = Flipped(Vec(Ways, new MetaBundle))
    val dataReadResp = Flipped(Vec(Ways, new DataBundle))
    val metaWriteBus = Input(CacheMetaArrayWriteBus())
    val dataWriteBus = Input(CacheDataArrayWriteBus())
  })

  val req = io.in.bits.req
  val addr = req.addr.asTypeOf(addrBundle)

  // Forwarded Meta Information of a Cache Line
  // ------------------------------------------
  // All the following code in the segment is to get a `metaForwarded`
  // which uses either "fresh" incoming meta info or stored previously

  // val isMetaForwarding = io.in.valid && 
  //   io.metaWriteBus.req.valid && 
  //   io.metaWriteBus.req.bits.setIdx === getMetaIdx(req.addr)

  // val isMetaForwarded = RegInit(false.B)

  // when (isMetaForwarding) {
  //   isMetaForwarded := true.B
  // }
  // when (io.in.fire() || !io.in.valid) {
  //   isMetaForwarded := false.B 
  // }

  // val metaForwarded = RegEnable(io.metaWriteBus.req.bits, isMetaForwarding)
  // val metaForwarded = Mux(isMetaForwarding, io.metaWriteBus.req.bits, metaForwarded)

  val (isMetaForwarding, metaForwarded) = SUR(
    io.in.valid && 
    io.metaWriteBus.req.valid && 
    io.metaWriteBus.req.bits.setIdx === getMetaIdx(req.addr),
    io.in.fire() || !io.in.valid,
    io.metaWriteBus.req.bits 
  )

  // ------------------------------------------

  val metaWay = Wire(Vec(Ways, chiselTypeOf(metaForwarded.data)))


  val forwardWaymask = metaForwarded.waymask.getOrElse("1".U).asBools
  forwardWaymask.zipWithIndex.map { case (w, i) =>
    metaWay(i) := Mux(isMetaForwarding && w, metaForwarded.data, io.metaReadResp(i))
  }

  val hitVec = VecInit(metaWay.map(m => m.valid && (m.tag === addr.tag) && io.in.valid)).asUInt
  val victimWaymask = if (Ways > 1) (1.U << LFSR64()(log2Up(Ways)-1,0)) else "b1".U
   
  val invalidVec = VecInit(metaWay.map(m => !m.valid)).asUInt
  val hasInvalidWay = invalidVec.orR
  val refillInvalidWaymask = Mux(invalidVec >= 8.U, "b1000".U,
    Mux(invalidVec >= 4.U, "b0100".U,
    Mux(invalidVec >= 2.U, "b0010".U, "b0001".U)))
  
  // val waymask = Mux(io.out.bits.hit, hitVec, victimWaymask)
  val waymask = Mux(io.out.bits.hit, hitVec, Mux(hasInvalidWay, refillInvalidWaymask, victimWaymask))
  // when(PopCount(waymask) > 1.U){
  //   metaWay.map(m => Debug("[ERROR] metaWay %x metat %x reqt %x\n", m.valid, m.tag, addr.tag))
  //   io.metaReadResp.map(m => Debug("[ERROR] metaReadResp %x metat %x reqt %x\n", m.valid, m.tag, addr.tag))
  //   Debug("[ERROR] metaForwarded isMetaForwarding %x %x metat %x wm %b\n", isMetaForwarding, metaForwarded.data.valid, metaForwarded.data.tag, metaForwarded.waymask.get)
  //   Debug("[ERROR] metaForwarded isMetaForwarding %x %x metat %x wm %b\n", isMetaForwarding, io.metaWriteBus.req.bits.data.valid, io.metaWriteBus.req.bits.data.tag, io.metaWriteBus.req.bits.waymask.get)
  // }
  when(PopCount(waymask) > 1.U){
    Debug("[ERROR] hit %b wmask %b hitvec %b\n", io.out.bits.hit, metaForwarded.waymask.getOrElse("1".U), hitVec)
  }
  assert(!(io.in.valid && PopCount(waymask) > 1.U))

  io.out.bits.metas := metaWay
  io.out.bits.hit := io.in.valid && hitVec.orR
  io.out.bits.waymask := waymask
  io.out.bits.datas := io.dataReadResp
  io.out.bits.mmio := AddressSpace.isMMIO(req.addr)

  // val isDataForwarding = io.in.valid && (io.dataWriteBus.req match { case r =>
  //     r.valid && r.bits.setIdx === getDataIdx(req.addr)
  //   })
  // val isForwardDataReg = RegInit(false.B)
  // when (isDataForwarding) { isForwardDataReg := true.B }
  // when (io.in.fire() || !io.in.valid) { isForwardDataReg := false.B }
  // val forwardDataReg = RegEnable(io.dataWriteBus.req.bits, isDataForwarding)
  
  val (isDataForwarding, dataForwarded) = SUR(
    io.in.valid && (io.dataWriteBus.req match { case r =>
      r.valid && r.bits.setIdx === getDataIdx(req.addr)
    }),
    io.in.fire() | !io.in.valid,
    io.dataWriteBus.req.bits
  )
  io.out.bits.isForwardData := isDataForwarding
  io.out.bits.forwardData := dataForwarded

  io.out.bits.req <> req
  io.out.valid := io.in.valid
  io.in.ready := !io.in.valid || io.out.fire()

  // Debug("[isFD:%d isFDreg:%d inFire:%d invalid:%d \n", dataForwarded, isDataForwarding, io.in.fire(), io.in.valid)
  // Debug("[isFM:%d isFMreg:%d metawreq:%x widx:%x ridx:%x \n", isMetaForwarding, isMetaForwarding, io.metaWriteBus.req.valid, io.metaWriteBus.req.bits.setIdx, getMetaIdx(req.addr))
}

