// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package freechips.rocketchip.tile

import Chisel._
import freechips.rocketchip.config._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.NAMESPACE._

case class RocketTileParams(
    core: RocketCoreParams = RocketCoreParams(),
    icache: Option[ICacheParams] = Some(ICacheParams()),
    dcache: Option[DCacheParams] = Some(DCacheParams()),
    btb: Option[BTBParams] = Some(BTBParams()),
    dataScratchpadBytes: Int = 0,
    trace: Boolean = false,
    hcfOnUncorrectable: Boolean = false,
    name: Option[String] = Some("tile"),
    hartId: Int = 0,
    blockerCtrlAddr: Option[BigInt] = None,
    boundaryBuffers: Boolean = false // if synthesized with hierarchical PnR, cut feed-throughs?
    ) extends TileParams {
  require(icache.isDefined)
  require(dcache.isDefined)
}

class RocketTile(
    val rocketParams: RocketTileParams,
    crossing: ClockCrossingType)
  (implicit p: Parameters) extends BaseTile(rocketParams, crossing)(p)
    with HasExternalInterrupts
	with HasFpuOpt //putting this here for now
    with HasLazyRoCC  // implies CanHaveSharedFPU with CanHavePTW with HasHellaCache
    with HasHellaCache
    with HasICacheFrontend {

  val intOutwardNode = IntIdentityNode()
  val slaveNode = TLIdentityNode()
  val masterNode = TLIdentityNode()
  //TODO

  val dtim_adapter = tileParams.dcache.flatMap { d => d.scratch.map(s =>
    LazyModule(new ScratchpadSlavePort(AddressSet(s, d.dataScratchpadBytes-1), xBytes, tileParams.core.useAtomics && !tileParams.core.useAtomicsOnlyForIO)))
  }
  dtim_adapter.foreach(lm => connectTLSlave(lm.node, xBytes))

  val bus_error_unit = tileParams.core.tileControlAddr map { a =>
    val beu = LazyModule(new BusErrorUnit(new L1BusErrors, BusErrorUnitParams(a)))
    intOutwardNode := beu.intNode
    connectTLSlave(beu.node, xBytes)
    beu
  }

  val tile_master_blocker =
    tileParams.blockerCtrlAddr
      .map(BasicBusBlockerParams(_, xBytes, masterPortBeatBytes, deadlock = true))
      .map(bp => LazyModule(new BasicBusBlocker(bp)))

  tile_master_blocker.foreach(lm => connectTLSlave(lm.controlNode, xBytes))

  // TODO: this doesn't block other masters, e.g. RoCCs
  tlOtherMastersNode := tile_master_blocker.map { _.node := tlMasterXbar.node } getOrElse { tlMasterXbar.node }
  masterNode :=* tlOtherMastersNode
  DisableMonitors { implicit p => tlSlaveXbar.node :*= slaveNode }

  def findScratchpadFromICache: Option[AddressSet] = dtim_adapter.map { s =>
    val finalNode = frontend.masterNode.edges.out.head.manager.managers.find(_.nodePath.last == s.node)
    require (finalNode.isDefined, "Could not find the scratch pad; not reachable via icache?")
    require (finalNode.get.address.size == 1, "Scratchpad address space was fragmented!")
    finalNode.get.address(0)
  }

  //nDCachePorts += 1 /*core */ + (dtim_adapter.isDefined).toInt

  val dtimProperty = dtim_adapter.map(d => Map(
    "sifive,dtim" -> d.device.asProperty)).getOrElse(Nil)

  val itimProperty = tileParams.icache.flatMap(_.itimAddr.map(i => Map(
    "sifive,itim" -> frontend.icache.device.asProperty))).getOrElse(Nil)

  val cpuDevice = new SimpleDevice("cpu", Seq("sifive,rocket0", "riscv")) {
    override def parent = Some(ResourceAnchors.cpus)
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping ++ cpuProperties ++ nextLevelCacheProperty ++ tileProperties ++ dtimProperty ++ itimProperty)
    }
  }

  ResourceBinding {
    Resource(cpuDevice, "reg").bind(ResourceAddress(hartId))
  }

  override lazy val module = new RocketTileModuleImp(this)

  override def makeMasterBoundaryBuffers(implicit p: Parameters) = {
    if (!rocketParams.boundaryBuffers) super.makeMasterBoundaryBuffers
    else TLBuffer(BufferParams.none, BufferParams.flow, BufferParams.none, BufferParams.flow, BufferParams(1))
  }

  override def makeSlaveBoundaryBuffers(implicit p: Parameters) = {
    if (!rocketParams.boundaryBuffers) super.makeSlaveBoundaryBuffers
    else TLBuffer(BufferParams.flow, BufferParams.none, BufferParams.none, BufferParams.none, BufferParams.none)
  }
}

class RocketTileModuleImp(outer: RocketTile) extends BaseTileModuleImp(outer)
    //with HasFpuOpt
    with HasLazyRoCCModule
    with HasICacheFrontendModule {
  Annotated.params(this, outer.rocketParams)

  val core = Module(new Rocket()(outer.p))

  val uncorrectable = RegInit(Bool(false))
  val halt_and_catch_fire = outer.rocketParams.hcfOnUncorrectable.option(IO(Bool(OUTPUT)))

  outer.bus_error_unit.foreach { lm =>
    lm.module.io.errors.dcache := outer.dcache.module.io.errors
    lm.module.io.errors.icache := outer.frontend.module.io.errors
  }

  outer.decodeCoreInterrupts(core.io.interrupts) // Decode the interrupt vector
  outer.bus_error_unit.foreach { beu => core.io.interrupts.buserror.get := beu.module.io.interrupt }
  core.io.hartid := constants.hartid // Pass through the hartid
  trace.foreach { _ := core.io.trace }
  halt_and_catch_fire.foreach { _ := uncorrectable }
  outer.frontend.module.io.cpu <> core.io.imem
  outer.frontend.module.io.reset_vector := constants.reset_vector
  outer.frontend.module.io.hartid := constants.hartid
  outer.dcache.module.io.hartid := constants.hartid
  dcachePorts += core.io.dmem // TODO outer.dcachePorts += () => module.core.io.dmem ??
  outer.fpuOpt foreach { fpu => core.io.fpu <> fpu.module.io }
  core.io.ptw <> ptw.io.dpath

  if (outer.roccs.size > 0) {
    cmdRouter.get.io.in <> core.io.rocc.cmd
    outer.roccs.foreach(_.module.io.exception := core.io.rocc.exception)
    core.io.rocc.resp <> respArb.get.io.out
    core.io.rocc.busy <> (cmdRouter.get.io.busy || outer.roccs.map(_.module.io.busy).reduce(_ || _))
    core.io.rocc.interrupt := outer.roccs.map(_.module.io.interrupt).reduce(_ || _)
  }

  // Rocket has higher priority to DTIM than other TileLink clients
  outer.dtim_adapter.foreach { lm => dcachePorts += lm.module.io.dmem }

  when(!uncorrectable) { uncorrectable :=
    List(outer.frontend.module.io.errors, outer.dcache.module.io.errors)
      .flatMap { e => e.uncorrectable.map(_.valid) }
      .reduceOption(_||_)
      .getOrElse(false.B)
  }

  // TODO eliminate this redundancy
  val h = dcachePorts.size
  val c = core.dcacheArbPorts
  val o = outer.nDCachePorts
  require(h == c, s"port list size was $h, core expected $c")
  require(h == o, s"port list size was $h, outer counted $o")
  // TODO figure out how to move the below into their respective mix-ins
  dcacheArb.io.requestor <> dcachePorts
  ptw.io.requestor <> ptwPorts
}

trait HasFpuOpt { this: RocketTile =>
  val fpuOpt: Option[LazyFPU] = tileParams.core.fpu.map(params => LazyModule(new LazyFPU(params)(p)))
}
