package se.nimsa.sbx.dicom.streams

import akka.actor.Cancellable
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, Sink, Source => StreamSource}
import akka.stream.{FlowShape, Materializer, SinkShape}
import akka.util.ByteString
import akka.{Done, NotUsed}
import org.dcm4che3.data.{Attributes, Tag, UID, VR}
import org.dcm4che3.io.DicomStreamException
import se.nimsa.dcm4che.streams.DicomFlows._
import se.nimsa.dcm4che.streams.DicomModifyFlow._
import se.nimsa.dcm4che.streams.DicomParseFlow._
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.dcm4che.streams.TagPath.TagPathSequence
import se.nimsa.dcm4che.streams._
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol.{Source, SourceType}
import se.nimsa.sbx.dicom.Contexts.Context
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomPropertyValue.{PatientID, PatientName}
import se.nimsa.sbx.dicom.DicomUtil.{attributesToPatient, attributesToSeries, attributesToStudy}
import se.nimsa.sbx.dicom.{Contexts, DicomUtil, ImageAttribute}
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.storage.StorageService
import se.nimsa.sbx.util.SbxExtensions._

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

/**
  * Stream operations for loading and saving DICOM data from and to storage
  */
trait DicomStreamOps {

  import DicomStreamOps._

  def callAnonymizationService[R: ClassTag](message: Any): Future[R]
  def callMetaDataService[R: ClassTag](message: Any): Future[R]
  def scheduleTask(delay: FiniteDuration)(task: => Unit): Cancellable

  protected def anonymizationInsert(implicit ec: ExecutionContext): (AnonymizationKey) => Future[AnonymizationKey] =
    (anonymizationKey: AnonymizationKey) => callAnonymizationService[AnonymizationKeyAdded](AddAnonymizationKey(anonymizationKey))
      .map(_.anonymizationKey)

  protected def anonymizationQuery(implicit ec: ExecutionContext): (PatientName, PatientID) => Future[Seq[AnonymizationKey]] =
    (patientName: PatientName, patientID: PatientID) => callAnonymizationService[AnonymizationKeys](GetAnonymizationKeysForPatient(patientName.value, patientID.value))
      .map(_.anonymizationKeys)

  /**
    * Creates a streaming source of anonymized and harmonized DICOM data
    *
    * @param imageId   ID of image to load
    * @param tagValues forced values of attributes as pairs of tag number and string value encoded in UTF-8 (ASCII) format
    * @param storage   the storage backend (file, runtime, S3 etc)
    * @return a `Source` of anonymized DICOM byte chunks
    */
  def anonymizedDicomData(imageId: Long, tagValues: Seq[TagValue], storage: StorageService)
                         (implicit materializer: Materializer, ec: ExecutionContext): StreamSource[ByteString, NotUsed] = {
    val source = storage.fileSource(imageId)
      .via(parseFlow)
    anonymizedDicomDataSource(source, anonymizationQuery, anonymizationInsert, tagValues)
  }

  protected def reverseAnonymizationQuery(implicit ec: ExecutionContext): (PatientName, PatientID) => Future[Seq[AnonymizationKey]] =
    (patientName: PatientName, patientID: PatientID) => callAnonymizationService[AnonymizationKeys](GetReverseAnonymizationKeysForPatient(patientName.value, patientID.value))
      .map(_.anonymizationKeys)

  /**
    * Store DICOM data from a source of byte chunks and update meta data.
    *
    * @param bytesSource          DICOM byte data source
    * @param source               the origin of the data (import, scp etc)
    * @param storage              the storage backend (file, runtime, S3 etc)
    * @param contexts             the allowed combinations of SOP Class UID and Transfer Syntax
    * @param reverseAnonymization switch to determined whether reverse anonymization should be carried out or not
    * @return the meta data info stored in the database
    */
  def storeDicomData(bytesSource: StreamSource[ByteString, _], source: Source, storage: StorageService, contexts: Seq[Context], reverseAnonymization: Boolean = true)
                    (implicit materializer: Materializer, ec: ExecutionContext): Future[MetaDataAdded] = {
    val tempPath = createTempPath()
    val sink = dicomDataSink(storage.fileSink(tempPath), reverseAnonymizationQuery, contexts, reverseAnonymization)
    bytesSource.runWith(sink).flatMap {
      case (_, maybeDataset) => storeDicomData(maybeDataset, source, tempPath, storage)
    }.recover {
      case t: Throwable =>
        scheduleTask(30.seconds) {
          storage.deleteByName(Seq(tempPath)) // delete temp file once file system has released handle
        }
        throw t
    }
  }

  private def storeDicomData(maybeDataset: Option[Attributes], source: Source, tempPath: String, storage: StorageService)
                            (implicit ec: ExecutionContext): Future[MetaDataAdded] = {
    val attributes: Attributes = maybeDataset.getOrElse(throw new DicomStreamException("DICOM data has no dataset"))
    callMetaDataService[MetaDataAdded](AddMetaData(attributes, source)).map { metaDataAdded =>
      storage.move(tempPath, storage.imageName(metaDataAdded.image.id))
      metaDataAdded
    }
  }

  /**
    * Retrieve data from the system, anonymize it - regardless of already anonymous or not, delete the old data, and
    * write the new data back to the system.
    *
    * @param imageId   ID of image to anonymize
    * @param tagValues forced values of attributes as pairs of tag number and string value encoded in UTF-8 (ASCII) format
    * @param storage   the storage backend (file, runtime, S3 etc)
    * @return the anonymized metadata stored in the system
    */
  def anonymizeData(imageId: Long, tagValues: Seq[TagValue], storage: StorageService)
                   (implicit materializer: Materializer, ec: ExecutionContext): Future[Option[MetaDataAdded]] =
    callMetaDataService[Option[Image]](GetImage(imageId)).flatMap { imageMaybe =>
      imageMaybe.map { image =>
        callMetaDataService[Option[SeriesSource]](GetSourceForSeries(image.seriesId)).map { seriesSourceMaybe =>
          seriesSourceMaybe.map { seriesSource =>
            val forcedSource = storage.fileSource(imageId)
              .via(parseFlow)
              .via(modifyFlow(TagModification.contains(TagPath.fromTag(Tag.PatientIdentityRemoved), _ => ByteString("NO"), insert = false)))
              .via(blacklistFilter(Set(TagPath.fromTag(Tag.DeidentificationMethod))))
            val anonymizedSource = anonymizedDicomDataSource(forcedSource, anonymizationQuery, anonymizationInsert, tagValues)
            storeDicomData(anonymizedSource, seriesSource.source, storage, Contexts.extendedContexts, reverseAnonymization = false).flatMap { metaDataAdded =>
              callMetaDataService[MetaDataDeleted](DeleteMetaData(Seq(imageId))).map { _ =>
                storage.deleteFromStorage(Seq(imageId))
                metaDataAdded
              }
            }
          }
        }
      }.unwrap
    }.unwrap


  def modifyData(imageId: Long, tagModifications: Seq[TagModification], storage: StorageService)
                (implicit materializer: Materializer, ec: ExecutionContext): Future[(MetaDataDeleted, MetaDataAdded)] = {

    val futureSourceAndTags =
      callMetaDataService[Option[Image]](GetImage(imageId)).map { imageMaybe =>
        imageMaybe.map { image =>
          callMetaDataService[Option[SeriesSource]](GetSourceForSeries(image.seriesId)).flatMap { sourceMaybe =>
            callMetaDataService[SeriesTags](GetSeriesTagsForSeries(image.seriesId)).map { seriesTags =>
              (sourceMaybe.map(_.source), seriesTags.seriesTags)
            }
          }
        }
      }.unwrap.map(_.getOrElse((None, Seq.empty)))

    val tempPath = createTempPath()
    val sink = dicomDataSink(storage.fileSink(tempPath), reverseAnonymizationQuery, Contexts.extendedContexts, reverseAnonymization = false)

    val futureModifiedTempFile =
      storage.fileSource(imageId)
        .via(parseFlow)
        .via(groupLengthDiscardFilter)
        .via(toUndefinedLengthSequences)
        .via(modifyFlow(tagModifications: _*))
        .via(fmiGroupLengthFlow)
        .map(_.bytes)
        .runWith(sink)

    for {
      (sourceMaybe, tags) <- futureSourceAndTags
      source = sourceMaybe.getOrElse(Source(SourceType.UNKNOWN, SourceType.UNKNOWN.toString, -1))
      (_, maybeDataset) <- futureModifiedTempFile
      metaDataDeleted <- callMetaDataService[MetaDataDeleted](DeleteMetaData(Seq(imageId)))
      _ = storage.deleteFromStorage(Seq(imageId))
      metaDataAdded <- storeDicomData(maybeDataset, source, tempPath, storage)
      seriesId = metaDataAdded.series.id
      _ <- Future.sequence {
        tags.map { tag =>
          callMetaDataService[SeriesTagAddedToSeries](AddSeriesTagToSeries(tag, seriesId))
        }
      }
    } yield (metaDataDeleted, metaDataAdded)

  }

}

object DicomStreamOps {

  import AnonymizationFlow._
  import HarmonizeAnonymizationFlow._
  import ReverseAnonymizationFlow._

  val encodingTags = Set(Tag.TransferSyntaxUID, Tag.SpecificCharacterSet)

  val tagsToStoreInDB: Set[Int] = {
    val patientTags = Seq(Tag.PatientName, Tag.PatientID, Tag.PatientSex, Tag.PatientBirthDate)
    val studyTags = Seq(Tag.StudyInstanceUID, Tag.StudyDescription, Tag.StudyID, Tag.StudyDate, Tag.AccessionNumber, Tag.PatientAge)
    val seriesTags = Seq(Tag.SeriesInstanceUID, Tag.SeriesDescription, Tag.SeriesDate, Tag.Modality, Tag.ProtocolName, Tag.BodyPartExamined, Tag.Manufacturer, Tag.StationName, Tag.FrameOfReferenceUID)
    val imageTags = Seq(Tag.SOPInstanceUID, Tag.ImageType, Tag.InstanceNumber)

    encodingTags ++ patientTags ++ studyTags ++ seriesTags ++ imageTags
  }

  val metaTags2Collect: Set[Int] = encodingTags ++ Set(Tag.PatientName, Tag.PatientID, Tag.PatientIdentityRemoved, Tag.StudyInstanceUID, Tag.SeriesInstanceUID)

  val anonymizationKeyTags: Set[Int] = encodingTags ++ Set(Tag.PatientName, Tag.PatientID, Tag.PatientBirthDate,
    Tag.StudyInstanceUID, Tag.StudyDescription, Tag.StudyID, Tag.AccessionNumber,
    Tag.SeriesInstanceUID, Tag.SeriesDescription, Tag.ProtocolName, Tag.FrameOfReferenceUID)

  def maybeDeflateFlow: Flow[DicomPart, DicomPart, NotUsed] = conditionalFlow(
    {
      case p: DicomMetaPart => p.transferSyntaxUid.isDefined && DicomParsing.isDeflated(p.transferSyntaxUid.get)
    }, deflateDatasetFlow, Flow.fromFunction(identity), routeADefault = false)

  def createTempPath() = s"tmp-${java.util.UUID.randomUUID().toString}"

  def attributesToMetaPart(dicomPart: DicomPart)
                          (implicit ec: ExecutionContext, materializer: Materializer): Future[DicomPart] = {
    dicomPart match {
      case da: DicomAttributes =>
        StreamSource.fromIterator(() => da.attributes.iterator).runWith(DicomAttributesSink.attributesSink).map {
          case (fmiMaybe, dsMaybe) =>
            DicomMetaPart(
              fmiMaybe.flatMap(fmi => Option(fmi.getString(Tag.TransferSyntaxUID))),
              dsMaybe.flatMap(ds => Option(ds.getSpecificCharacterSet)),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientID))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientName))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientIdentityRemoved))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.StudyInstanceUID))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.SeriesInstanceUID))))
        }
      case part: DicomPart => Future.successful(part)
    }
  }

  def queryProtectedAnonymizationKeys(query: (PatientName, PatientID) => Future[Seq[AnonymizationKey]])
                                     (implicit ec: ExecutionContext): DicomPart => Future[List[DicomPart]] = {
    case meta: DicomMetaPart =>
      val maybeFutureKey = for {
        patientName <- meta.patientName if meta.isAnonymized
        patientId <- meta.patientId
      } yield {
        query(PatientName(patientName), PatientID(patientId)).map { patientKeys =>
          val studyKeys = meta.studyInstanceUID.map(studyUID => patientKeys.filter(_.anonStudyInstanceUID == studyUID)).getOrElse(Seq.empty)
          val seriesKeys = meta.seriesInstanceUID.map(seriesUID => studyKeys.filter(_.anonSeriesInstanceUID == seriesUID)).getOrElse(Seq.empty)
          meta :: AnonymizationKeysPart(patientKeys, patientKeys.headOption, studyKeys.headOption, seriesKeys.headOption) :: Nil
        }
      }
      maybeFutureKey.getOrElse(Future.successful(meta :: AnonymizationKeysPart(Seq.empty, None, None, None) :: Nil))
    case part: DicomPart =>
      Future.successful(part :: Nil)
  }

  def queryAnonymousAnonymizationKeys(query: (PatientName, PatientID) => Future[Seq[AnonymizationKey]])
                                     (implicit ec: ExecutionContext): DicomPart => Future[List[DicomPart]] = {
    case meta: DicomMetaPart =>
      val maybeFutureKey = for {
        patientName <- meta.patientName
        patientId <- meta.patientId
      } yield {
        query(PatientName(patientName), PatientID(patientId)).map { patientKeys =>
          val studyKeys = meta.studyInstanceUID.map(studyUID => patientKeys.filter(_.studyInstanceUID == studyUID)).getOrElse(Seq.empty)
          val seriesKeys = meta.seriesInstanceUID.map(seriesUID => studyKeys.filter(_.seriesInstanceUID == seriesUID)).getOrElse(Seq.empty)
          meta :: AnonymizationKeysPart(patientKeys, patientKeys.headOption, studyKeys.headOption, seriesKeys.headOption) :: Nil
        }
      }
      maybeFutureKey.getOrElse(Future.successful(meta :: AnonymizationKeysPart(Seq.empty, None, None, None) :: Nil))
    case part: DicomPart =>
      Future.successful(part :: Nil)
  }

  def dicomDataSink(storageSink: Sink[ByteString, Future[Done]], reverseAnonymizationQuery: (PatientName, PatientID) => Future[Seq[AnonymizationKey]], contexts: Seq[Context], reverseAnonymization: Boolean = true)
                   (implicit ec: ExecutionContext, materializer: Materializer): Sink[ByteString, Future[(Option[Attributes], Option[Attributes])]] = {

    val attributesSink = DicomAttributesSink.attributesSink

    val validationContexts = Contexts.asNamePairs(contexts).map(ValidationContext.tupled)

    def runBothKeepRight[A, B] = (futureLeft: Future[A], futureRight: Future[B]) => futureLeft.flatMap(_ => futureRight)

    Sink.fromGraph(GraphDSL.create(storageSink, attributesSink)(runBothKeepRight) { implicit builder =>
      (storageSink, attributesSink) =>
        import GraphDSL.Implicits._

        val baseFlow = validateFlowWithContext(validationContexts, drainIncoming = true)
          .via(parseFlow)
          .via(collectAttributesFlow(metaTags2Collect))
          .mapAsync(1)(attributesToMetaPart) // needed for e.g. maybe deflate flow

        val flow = builder.add {
          if (reverseAnonymization)
            baseFlow
              .mapAsync(1)(queryProtectedAnonymizationKeys(reverseAnonymizationQuery))
              .mapConcat(identity) // flatten stream of lists
              .via(maybeReverseAnonFlow)
          else
            baseFlow
        }

        val bcast = builder.add(Broadcast[DicomPart](2))

        flow ~> bcast.in
        bcast.out(0) ~> maybeDeflateFlow.map(_.bytes) ~> storageSink
        bcast.out(1) ~> whitelistFilter(tagsToStoreInDB) ~> attributeFlow ~> attributesSink

        SinkShape(flow.in)
    })
  }

  def attributesToAnonKeyProtectedInfo(dicomPart: DicomPart)
                                      (implicit ec: ExecutionContext, materializer: Materializer): Future[DicomPart] = {
    dicomPart match {
      case da: DicomAttributes =>
        StreamSource.fromIterator(() => da.attributes.iterator).runWith(DicomAttributesSink.attributesSink).map {
          case (_, dsMaybe) =>
            val attributes = dsMaybe.getOrElse(new Attributes())
            val patient = attributesToPatient(attributes)
            val study = attributesToStudy(attributes)
            val series = attributesToSeries(attributes)
            AnonymizationKeyPart(
              AnonymizationKey(-1, System.currentTimeMillis,
                patient.patientName.value, "", patient.patientID.value, "", patient.patientBirthDate.value,
                study.studyInstanceUID.value, "", study.studyDescription.value, study.studyID.value, study.accessionNumber.value,
                series.seriesInstanceUID.value, "", series.seriesDescription.value, series.protocolName.value, series.frameOfReferenceUID.value, ""))
        }
      case part: DicomPart => Future.successful(part)
    }
  }

  def attributesToAnonKeyAnonymousInfo(dicomPart: DicomPart)
                                      (implicit ec: ExecutionContext, materializer: Materializer): Future[DicomPart] = {
    dicomPart match {
      case da: DicomAttributes =>
        StreamSource.fromIterator(() => da.attributes.iterator).runWith(DicomAttributesSink.attributesSink).map {
          case (_, dsMaybe) =>
            val attributes = dsMaybe.getOrElse(new Attributes())
            val patient = attributesToPatient(attributes)
            val study = attributesToStudy(attributes)
            val series = attributesToSeries(attributes)
            AnonymizationKeyPart(
              AnonymizationKey(-1, System.currentTimeMillis,
                "", patient.patientName.value, "", patient.patientID.value, "",
                "", study.studyInstanceUID.value, "", "", "",
                "", series.seriesInstanceUID.value, "", "", "", series.frameOfReferenceUID.value))
        }
      case part: DicomPart => Future.successful(part)
    }
  }

  def collectAnonymizationKeyProtectedInfo(implicit ec: ExecutionContext, materializer: Materializer): Flow[DicomPart, DicomPart, NotUsed] =
    Flow[DicomPart]
      .via(collectAttributesFlow(anonymizationKeyTags))
      .mapAsync(1)(attributesToAnonKeyProtectedInfo)

  def collectAnonymizationKeyAnonymousInfo(implicit ec: ExecutionContext, materializer: Materializer): Flow[DicomPart, DicomPart, NotUsed] =
    Flow[DicomPart]
      .via(collectAttributesFlow(anonymizationKeyTags))
      .mapAsync(1)(attributesToAnonKeyAnonymousInfo)

  def collectAnonymizationKeyInfo: Flow[DicomPart, DicomPart, NotUsed] =
    Flow[DicomPart]
      .statefulMapConcat {
        () =>
          var maybeKeys: Option[AnonymizationKeysPart] = None
          var maybeProtectedKey: Option[AnonymizationKeyPart] = None
          var maybeAnonymousKey: Option[AnonymizationKeyPart] = None

          def maybeEmit() = maybeKeys
            .flatMap(keys => maybeProtectedKey
              .flatMap(protectedKey => maybeAnonymousKey
                .map { anonymousKey =>
                  val fullKey = protectedKey.key.copy(
                    anonPatientName = anonymousKey.key.anonPatientName,
                    anonPatientID = anonymousKey.key.anonPatientID,
                    anonStudyInstanceUID = anonymousKey.key.anonStudyInstanceUID,
                    anonSeriesInstanceUID = anonymousKey.key.anonSeriesInstanceUID,
                    anonFrameOfReferenceUID = anonymousKey.key.anonFrameOfReferenceUID)
                  AnonymizationKeyInfoPart(keys.patientKeys, fullKey) :: Nil
                }))
            .getOrElse(Nil)

        {
          case keys: AnonymizationKeysPart =>
            maybeKeys = Some(keys)
            maybeEmit()
          case key: AnonymizationKeyPart =>
            // anon key will be first in stream, followed by protected key
            if (maybeAnonymousKey.isDefined) maybeProtectedKey = Some(key) else maybeAnonymousKey = Some(key)
            maybeEmit()
          case p =>
            p :: Nil
        }
      }

  def maybeInsertAnonymizationKey(anonymizationInsert: AnonymizationKey => Future[AnonymizationKey])
                                 (implicit ec: ExecutionContext): DicomPart => Future[DicomPart] = {
    case info: AnonymizationKeyInfoPart =>
      val dbAnonymizationKey = info.existingKeys.find(isEqual(_, info.newKey))
        .map(Future.successful)
        .getOrElse(anonymizationInsert(info.newKey))
      dbAnonymizationKey.map(key => info.copy(newKey = key))
    case part: DicomPart =>
      Future.successful(part)
  }

  def toTagModifications(tagValues: Seq[TagValue]): Seq[TagModification] =
    tagValues.map(tv => TagModification.endsWith(TagPath.fromTag(tv.tag), _ => padToEvenLength(ByteString(tv.value), tv.tag), insert = true))

  def anonymizedDicomDataSource(storageSource: StreamSource[DicomPart, NotUsed],
                                anonymizationQuery: (PatientName, PatientID) => Future[Seq[AnonymizationKey]],
                                anonymizationInsert: AnonymizationKey => Future[AnonymizationKey],
                                tagValues: Seq[TagValue])
                               (implicit ec: ExecutionContext, materializer: Materializer): StreamSource[ByteString, NotUsed] =
    storageSource // DicomPart...
      .via(collectAttributesFlow(metaTags2Collect)) // DicomAttributes :: DicomPart...
      .mapAsync(1)(attributesToMetaPart) // DicomMetaPart :: DicomPart...
      .mapAsync(1)(queryAnonymousAnonymizationKeys(anonymizationQuery))
      .mapConcat(identity) // DicomMetaPart :: AnonymizationKeysPart :: DicomPart...
      .via(collectAnonymizationKeyProtectedInfo) // AnonymizationKeyPart (protected) :: DicomMetaPart :: AnonymizationKeysPart :: DicomPart...
      .via(maybeAnonFlow)
      .via(maybeHarmonizeAnonFlow)
      .via(modifyFlow(toTagModifications(tagValues): _*))
      .via(fmiGroupLengthFlow) // update meta information group length
      .via(collectAnonymizationKeyAnonymousInfo) // AnonymizationKeyPart (anon) :: AnonymizationKeyPart (protected) :: DicomMetaPart :: AnonymizationKeysPart :: DicomPart...
      .via(collectAnonymizationKeyInfo) // DicomMetaPart :: AnonymizationKeyInfoPart :: DicomPart...
      .mapAsync(1)(maybeInsertAnonymizationKey(anonymizationInsert))
      .via(maybeDeflateFlow)
      .map(_.bytes)

  def inflatedSource(source: StreamSource[ByteString, _]): StreamSource[ByteString, _] = source
    .via(parseFlow)
    .via(modifyFlow(
      TagModification.contains(TagPath.fromTag(Tag.TransferSyntaxUID), valueBytes => {
        valueBytes.utf8String.trim match {
          case UID.DeflatedExplicitVRLittleEndian => padToEvenLength(ByteString(UID.ExplicitVRLittleEndian), VR.UI)
          case _ => valueBytes
        }
      }, insert = false)))
    .via(fmiGroupLengthFlow)
    .map(_.bytes)

  def conditionalFlow(goA: PartialFunction[DicomPart, Boolean], flowA: Flow[DicomPart, DicomPart, _], flowB: Flow[DicomPart, DicomPart, _], routeADefault: Boolean = true): Flow[DicomPart, DicomPart, NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      var routeA = routeADefault // determines which path is taken in the graph

      // if any part indicates that alternate route should be taken, remember this
      val gateFlow = builder.add {
        Flow[DicomPart].map { part =>
          if (goA.isDefinedAt(part)) routeA = goA(part)
          part
        }
      }

      // split the flow
      val bcast = builder.add(Broadcast[DicomPart](2))

      // define gates for each path, only one path is used
      val gateA = Flow[DicomPart].filter(_ => routeA)
      val gateB = Flow[DicomPart].filterNot(_ => routeA)

      // merge the two paths
      val merge = builder.add(Merge[DicomPart](2))

      // surround each flow by gates, remember that flows may produce items without input
      gateFlow ~> bcast ~> gateA ~> flowA ~> gateA ~> merge
      bcast ~> gateB ~> flowB ~> gateB ~> merge

      FlowShape(gateFlow.in, merge.out)
    })

  def imageAttributesSource[M](source: StreamSource[ByteString, M])
                              (implicit ec: ExecutionContext, materializer: Materializer): StreamSource[ImageAttribute, M] =
    source
      .via(new DicomParseFlow(stopTag = Some(Tag.PixelData)))
      .via(bulkDataFilter)
      .via(collectAttributesFlow(encodingTags))
      .mapAsync(1)(attributesToMetaPart)
      .via(attributeFlow)
      .statefulMapConcat {
        var meta: Option[DicomMetaPart] = None
        var namePath = List.empty[String]
        var tagPath = List.empty[Int]
        var tagPathSequence: Option[TagPathSequence] = None

        () => {
          case mp: DicomMetaPart =>
            meta = Some(mp)
            Nil
          case attribute: DicomAttribute =>
            val tag = attribute.header.tag
            val length = attribute.valueBytes.length
            val values = attribute.header.vr match {
              case VR.OW | VR.OF | VR.OB =>
                List(s"< Binary data ($length bytes) >")
              case _ =>
                val attrs = new Attributes(attribute.bigEndian, 9)
                meta.flatMap(_.specificCharacterSet).foreach(cs => attrs.setSpecificCharacterSet(cs.toCodes: _*))
                attrs.setBytes(tag, attribute.header.vr, attribute.valueBytes.toArray)
                DicomUtil.getStrings(attrs, tag).toList
            }
            val multiplicity = values.length
            val depth = tagPath.size
            val tagPathTag = tagPathSequence.map(_.thenTag(attribute.header.tag)).getOrElse(TagPath.fromTag(attribute.header.tag))

            ImageAttribute(
              tag,
              groupNumber(tag),
              elementNumber(tag),
              DicomUtil.nameForTag(tag),
              attribute.header.vr.name,
              multiplicity,
              length,
              depth,
              tagPathTag,
              tagPath,
              namePath,
              values) :: Nil
          case sq: DicomSequence =>
            namePath = namePath :+ DicomUtil.nameForTag(sq.tag)
            tagPath = tagPath :+ sq.tag
            tagPathSequence = tagPathSequence.map(_.thenSequence(sq.tag)).orElse(Some(TagPath.fromSequence(sq.tag)))
            Nil
          case _: DicomSequenceDelimitation =>
            namePath = namePath.dropRight(1)
            tagPath = tagPath.dropRight(1)
            tagPathSequence = tagPathSequence.flatMap(_.previous)
            Nil
          case fragments: DicomFragments =>
            tagPathSequence = tagPathSequence.map(_.thenSequence(fragments.tag)).orElse(Some(TagPath.fromSequence(fragments.tag)))
            Nil
          case _: DicomFragmentsDelimitation =>
            tagPathSequence = tagPathSequence.flatMap(_.previous)
            Nil
          case item: DicomItem =>
            tagPathSequence = tagPathSequence.flatMap(s => s.previous.map(_.thenSequence(s.tag, item.index)).orElse(Some(TagPath.fromSequence(s.tag, item.index))))
            Nil
          case _ => Nil
        }
      }

  def isEqual(key1: AnonymizationKey, key2: AnonymizationKey): Boolean =
    key1.patientName == key2.patientName && key1.anonPatientName == key2.anonPatientName &&
      key1.patientID == key2.patientID && key1.anonPatientID == key2.anonPatientID &&
      key1.studyInstanceUID == key2.studyInstanceUID && key1.anonStudyInstanceUID == key2.anonStudyInstanceUID &&
      key1.seriesInstanceUID == key2.seriesInstanceUID && key1.anonSeriesInstanceUID == key2.anonSeriesInstanceUID
}