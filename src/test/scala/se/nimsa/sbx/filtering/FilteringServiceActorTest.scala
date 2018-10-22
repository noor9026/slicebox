package se.nimsa.sbx.filtering

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import se.nimsa.dicom.data.TagPath
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.filtering.FilteringProtocol.TagFilterType.WHITELIST
import se.nimsa.sbx.filtering.FilteringProtocol._
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class FilteringServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {
  val dbConfig = TestUtil.createTestDb("filteringserviceactortest")

  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)
  val db = dbConfig.db
  val filteringDao = new FilteringDAO(dbConfig)

  await(filteringDao.create())

  val filteringService: ActorRef = system.actorOf(Props(new FilteringServiceActor(filteringDao)(Timeout(30.seconds))), name = "FilteringService")

  def this() = this(ActorSystem("FilteringServiceActorTestSystem"))

  override def afterEach(): Unit = {
    await(filteringDao.clear())
  }

  def dump(): Unit = {
    println(s"db contents:")
    val cs = await(filteringDao.dump())
    println(cs)
    println(s"No more in db")
  }

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  def getTagFilterSpec1 = FilteringProtocol.TagFilterSpec(-1, "filter1", WHITELIST, Seq(TagPath.fromTag(0x0008000d)))

  def getTagFilterSpec2 = FilteringProtocol.TagFilterSpec(-1, "filter2", WHITELIST, Seq(TagPath.fromTag(0x00100010), TagPath.fromTag(0x00100020)))

  def getEmptyTagFilterSpec = TagFilterSpec(-1, "emptyFilter", WHITELIST, Seq())

  case class SetSource(source: Source)

  "A FilteringServiceActor" should {

    "support adding and listing TagFilters" in {

      filteringService ! GetTagFilters(0, 1)
      expectMsg(TagFilterSpecs(List.empty))

      val tagFilterSpec1 = getTagFilterSpec1
      val tagFilterSpec2 = getTagFilterSpec2

      filteringService ! AddTagFilter(tagFilterSpec1)
      filteringService ! AddTagFilter(tagFilterSpec2)

      val filter1 = expectMsgType[TagFilterAdded].filterSpecification
      val filter2 = expectMsgType[TagFilterAdded].filterSpecification

      filteringService ! GetTagFilters(0, 10)
      expectMsg(TagFilterSpecs(List(filter1.copy(tags = Seq()), filter2.copy(tags = Seq()))))
    }

    "associate source with TagFilter" in {
      val tagFilterSpec1 = getTagFilterSpec1
      filteringService ! AddTagFilter(tagFilterSpec1)
      val filter1 = expectMsgType[TagFilterAdded].filterSpecification
      val source = SourceRef(SourceType.BOX, 1)
      filteringService ! GetFilterForSource(source)
      expectMsg(None)
      filteringService ! AddSourceFilterAssociation(SourceTagFilter(-1, source.sourceType, source.sourceId, filter1.id))
      expectMsgType[SourceTagFilter]
      filteringService ! GetFilterForSource(source)
      expectMsg(Some(filter1))
    }

    "System event SourceDeleted shall delete filter association" in {
      filteringService ! AddTagFilter(getTagFilterSpec1)
      val filter1 = expectMsgType[TagFilterAdded].filterSpecification
      val source = SourceRef(SourceType.BOX, 1)
      filteringService ! AddSourceFilterAssociation(SourceTagFilter(-1, source.sourceType, source.sourceId, filter1.id))
      val sourceTagFilter = expectMsgType[SourceTagFilter]
      filteringService ! GetSourceTagFilters(0, 100)
      expectMsg(SourceTagFilters(List(sourceTagFilter)))
      system.eventStream.publish(SourceDeleted(source))
      filteringService ! GetFilterForSource(source)
      expectMsg(None)
      filteringService ! GetSourceTagFilters(0, 100)
      expectMsg(SourceTagFilters(List()))
    }

    "Return complete TagFilterSpec" in {
      val tagFilterSpec1 = getTagFilterSpec1

      filteringService ! AddTagFilter(tagFilterSpec1)

      val filter1 = expectMsgType[TagFilterAdded].filterSpecification

      filteringService ! GetTagFilter(filter1.id)
      expectMsg(Some(filter1))

      val emptyTagFilterSpec = getEmptyTagFilterSpec

      filteringService ! AddTagFilter(emptyTagFilterSpec)

      val emptyFilter = expectMsgType[TagFilterAdded].filterSpecification

      filteringService ! GetTagFilter(emptyFilter.id)
      expectMsg(Some(emptyFilter))
    }

    "Update TagFilterSpec" in {
      val tagFilterSpec1 = getTagFilterSpec1

      filteringService ! AddTagFilter(tagFilterSpec1)

      val filter1 = expectMsgType[TagFilterAdded].filterSpecification

      filteringService ! GetTagFilter(filter1.id)
      expectMsg(Some(filter1))

      val updatedTagFilterSpec = tagFilterSpec1.copy(tags=Seq(TagPath.fromTag(0x00100010), TagPath.fromTag(0x00100020)))

      filteringService ! AddTagFilter(updatedTagFilterSpec)

      val updatedFilter = expectMsgType[TagFilterAdded].filterSpecification

      filteringService ! GetTagFilter(filter1.id)
      expectMsg(Some(updatedFilter))

    }

    "Delete tagFilters" in {

      filteringService ! GetTagFilters(0, 1)
      expectMsg(TagFilterSpecs(List.empty))

      val tagFilterSpec1 = getTagFilterSpec1
      val tagFilterSpec2 = getTagFilterSpec2

      filteringService ! AddTagFilter(tagFilterSpec1)
      filteringService ! AddTagFilter(tagFilterSpec2)

      val filter1 = expectMsgType[TagFilterAdded].filterSpecification
      val filter2 = expectMsgType[TagFilterAdded].filterSpecification

      filteringService ! GetTagFilters(0, 10)
      val msg = expectMsgType[TagFilterSpecs]
      msg.tagFilterSpecs.size shouldBe 2

      filteringService ! RemoveTagFilter(filter1.id)
      filteringService ! RemoveTagFilter(filter2.id)

      expectMsg(TagFilterRemoved(filter1.id))
      expectMsg(TagFilterRemoved(filter2.id))

      filteringService ! GetTagFilters(0, 10)
      expectMsg(TagFilterSpecs(List.empty))

    }
  }
}

