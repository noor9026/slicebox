/*
 * Copyright 2014 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.anonymization

import akka.actor.{Actor, Props, Stash}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.pipe
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol.ImagesDeleted
import se.nimsa.sbx.dicom.DicomHierarchy.DicomHierarchyLevel
import se.nimsa.sbx.util.SequentialPipeToSupport

import scala.concurrent.{ExecutionContext, Future}

class AnonymizationServiceActor(anonymizationDao: AnonymizationDAO, purgeEmptyAnonymizationKeys: Boolean)
                               (implicit ec: ExecutionContext) extends Actor with Stash with SequentialPipeToSupport {

  import AnonymizationUtil._

  val log = Logging(context.system, this)

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[ImagesDeleted])
  }

  log.info("Anonymization service started")

  def receive = LoggingReceive {

    case ImagesDeleted(imageIds) =>
      if (purgeEmptyAnonymizationKeys) anonymizationDao.deleteAnonymizationKeysForImageIds(imageIds)

    case msg: AnonymizationRequest =>

      msg match {
        case AddAnonymizationKey(anonymizationKey) =>
          anonymizationDao.insertAnonymizationKey(anonymizationKey)
            .map(AnonymizationKeyAdded)
            .pipeSequentiallyTo(sender)

        case RemoveAnonymizationKey(anonymizationKeyId) =>
          anonymizationDao.deleteAnonymizationKey(anonymizationKeyId)
            .map(_ => AnonymizationKeyRemoved(anonymizationKeyId))
            .pipeSequentiallyTo(sender)

        case GetAnonymizationKeys(startIndex, count, orderBy, orderAscending, filter) =>
          pipe(anonymizationDao.anonymizationKeys(startIndex, count, orderBy, orderAscending, filter).map(AnonymizationKeys)).to(sender)

        case GetAnonymizationKey(anonymizationKeyId) =>
          pipe(anonymizationDao.anonymizationKeyForId(anonymizationKeyId)).to(sender)

        case GetAnonymizationKeyValues(anonPatientName, anonPatientID, anonStudyInstanceUID, anonSeriesInstanceUID, anonSOPInstanceUID) =>
          // look for matching keys on image, series, study then patient levels.
          val f = anonymizationDao.anonymizationKeyForImage(anonPatientName, anonPatientID, anonStudyInstanceUID, anonSeriesInstanceUID, anonSOPInstanceUID)
            .flatMap(_
              .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                .map(values => AnonymizationKeyValues(DicomHierarchyLevel.IMAGE, values)))
              .getOrElse(anonymizationDao.anonymizationKeyForSeries(anonPatientName, anonPatientID, anonStudyInstanceUID, anonSeriesInstanceUID)
                .flatMap(_
                  .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                    .map(values => AnonymizationKeyValues(DicomHierarchyLevel.SERIES, values)))
                  .getOrElse(anonymizationDao.anonymizationKeyForStudy(anonPatientName, anonPatientID, anonStudyInstanceUID)
                    .flatMap(_
                      .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                        .map(values => AnonymizationKeyValues(DicomHierarchyLevel.STUDY, values)))
                      .getOrElse(anonymizationDao.anonymizationKeyForPatient(anonPatientName, anonPatientID)
                        .flatMap(_
                          .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                            .map(values => AnonymizationKeyValues(DicomHierarchyLevel.PATIENT, values)))
                          .getOrElse(Future.successful(AnonymizationKeyValues.empty)))))))))
          pipe(f).to(sender)

        case GetTagValuesForAnonymizationKey(anonymizationKeyId) =>
          val tagValues = anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(anonymizationKeyId)
          pipe(tagValues).to(sender)

        case QueryAnonymizationKeys(query) =>
          val order = query.order.map(_.orderBy)
          val orderAscending = query.order.forall(_.orderAscending)
          pipe(anonymizationDao.queryAnonymizationKeys(query.startIndex, query.count, order, orderAscending, query.queryProperties)).to(sender)

        case CreateAnonymizationKey(imageId, patientNameMaybe, patientIDMaybe, patientSexMaybe, patientAgeMaybe, studyInstanceUIDMaybe, seriesInstanceUIDMaybe, sopInstanceUIDMaybe) =>

          val patientName = patientNameMaybe.getOrElse("")
          val anonPatientName = createAnonymousPatientName(patientSexMaybe, patientAgeMaybe)
          val patientID = patientIDMaybe.getOrElse("")
          val anonPatientID = createUid("")
          val studyInstanceUID = studyInstanceUIDMaybe.getOrElse("")
          val anonStudyInstanceUID = createUid("")
          val seriesInstanceUID = seriesInstanceUIDMaybe.getOrElse("")
          val anonSeriesInstanceUID = createUid("")
          val sopInstanceUID = sopInstanceUIDMaybe.getOrElse("")
          val anonSOPInstanceUID = createUid("")

          val anonKey = AnonymizationKey(-1, System.currentTimeMillis, imageId,
            patientName, anonPatientName, patientID, anonPatientID,
            studyInstanceUID, anonStudyInstanceUID,
            seriesInstanceUID, anonSeriesInstanceUID,
            sopInstanceUID, anonSOPInstanceUID)

          anonymizationDao
            .insertAnonymizationKey(anonKey)
            .pipeSequentiallyTo(sender)
      }
  }

}

object AnonymizationServiceActor {
  def props(anonymizationDao: AnonymizationDAO, purgeEmptyAnonymizationKeys: Boolean)(implicit ec: ExecutionContext): Props = Props(new AnonymizationServiceActor(anonymizationDao, purgeEmptyAnonymizationKeys))
}
