
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

// Meta Read Stage (aka Stage 1)
// ==========================
// Read in the mem request

class StageMetaReadIO(implicit val cacheConfig: CacheConfig) extends CacheBundle {
  val req = new SimpleBusReqBundle(userBits = userBits, idBits = idBits)
}

// meta read
class CacheStageMetaRead(implicit val cacheConfig: CacheConfig) extends CacheModule {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new SimpleBusReqBundle(userBits = userBits, idBits = idBits)))
    val out = Decoupled(new StageMetaReadIO)
    val metaReadBus = CacheMetaArrayReadBus()
    val dataReadBus = CacheDataArrayReadBus()
  })

  if (ro) when (io.in.fire()) { assert(!io.in.bits.isWrite()) }
  Debug(io.in.fire(), "[L1$] cache stage1, addr in: %x, user: %x id: %x\n", io.in.bits.addr, io.in.bits.user.getOrElse(0.U), io.in.bits.id.getOrElse(0.U))

  // read meta array and data array
  val readBusValid = io.in.valid && io.out.ready
  io.metaReadBus.apply(valid = readBusValid, setIdx = getMetaIdx(io.in.bits.addr))
  io.dataReadBus.apply(valid = readBusValid, setIdx = getDataIdx(io.in.bits.addr))

  io.out.bits.req := io.in.bits
  io.out.valid := io.in.valid && io.metaReadBus.req.ready && io.dataReadBus.req.ready
  io.in.ready := (!io.in.valid || io.out.fire()) && io.metaReadBus.req.ready && io.dataReadBus.req.ready

  Debug("in.ready = %d, in.valid = %d, out.valid = %d, out.ready = %d, addr = %x, cmd = %x, dataReadBus.req.valid = %d\n", io.in.ready, io.in.valid, io.out.valid, io.out.ready, io.in.bits.addr, io.in.bits.cmd, io.dataReadBus.req.valid)
}

