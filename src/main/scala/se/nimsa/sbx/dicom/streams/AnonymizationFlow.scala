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

package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import se.nimsa.dicom.data.DicomParts.DicomPart
import se.nimsa.dicom.data.{Tag, TagPath, VR}
import se.nimsa.dicom.streams.DicomFlows.tagFilter
import se.nimsa.dicom.streams.ModifyFlow.{TagInsertion, TagModification, modifyFlow}
import se.nimsa.sbx.anonymization.AnonymizationUtil._
import se.nimsa.sbx.anonymization.{AnonymizationOp, AnonymizationProfile}
import se.nimsa.sbx.dicom.DicomUtil.toAsciiBytes

class AnonymizationFlow(profile: AnonymizationProfile) {

  import AnonymizationOp._

  def anonFlow: Flow[DicomPart, DicomPart, NotUsed] = {
    tagFilter(_ => true) { tagPath =>
      !tagPath.toList.map(_.tag).flatMap(profile.opOf)
        .exists {
          case REMOVE => true
          case REMOVE_OR_ZERO => true // always remove (limitation)
          case REMOVE_OR_DUMMY => true // always remove (limitation)
          case REMOVE_OR_ZERO_OR_DUMMY => true // always remove (limitation)
          case REMOVE_OR_ZERO_OR_REPLACE_UID => true // always remove (limitation)
          case _ => false
        }
    }
      .via(modifyFlow(
        Seq(
          TagModification(tagPath =>
            profile.opOf(tagPath.tag).contains(REPLACE_UID), _ => createUid()),
          TagModification(tagPath =>
            profile.opOf(tagPath.tag).exists {
              case DUMMY => true // zero instead of replace with dummy (limitation)
              case CLEAN => true // zero instead of replace with cleaned value (limitation)
              case ZERO => true
              case ZERO_OR_DUMMY => true // always zero (limitation)
              case _ => false
            }, _ => ByteString.empty
          )
        ),
        Seq(
          TagInsertion(TagPath.fromTag(Tag.DeidentificationMethod), _ => toAsciiBytes(profile.options.map(_.description).mkString(" - "), VR.LO)),
          TagInsertion(TagPath.fromTag(Tag.PatientIdentityRemoved), _ => toAsciiBytes("YES", VR.CS)),
        )))
  }
}
