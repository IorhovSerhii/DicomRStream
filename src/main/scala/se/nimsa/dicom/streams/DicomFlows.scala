/*
 * Copyright 2018 Lars Edenbrandt
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

package se.nimsa.dicom.streams

import java.util.zip.Deflater

import akka.NotUsed
import akka.stream.FlowShape
import akka.stream.javadsl.MergePreferred
import akka.stream.scaladsl.{Compression, Flow, GraphDSL, Partition, Source}
import akka.util.ByteString
import se.nimsa.dicom.data.CharacterSets.utf8Charset
import se.nimsa.dicom.data.DicomParsing.defaultCharacterSet
import se.nimsa.dicom.data.DicomParts._
import se.nimsa.dicom.data.Elements.ValueElement
import se.nimsa.dicom.data.TagPath.EmptyTagPath
import se.nimsa.dicom.data.VR.VR
import se.nimsa.dicom.data._
import se.nimsa.dicom.streams.CollectFlow._
import se.nimsa.dicom.streams.ModifyFlow._

/**
  * Various flows for transforming data of <code>DicomPart</code>s.
  */
object DicomFlows {

  case class ValidationContext(sopClassUID: String, transferSyntax: String)


  /**
    * Print each element and then pass it on unchanged.
    *
    * @return the associated flow
    */
  def printFlow[A]: Flow[A, A, NotUsed] = Flow.fromFunction { a =>
    println(a)
    a
  }

  /**
    * Filter a stream of dicom parts such that all elements except those with tags in the white list are discarded.
    * Non-data parts such as preamble are discarded.
    *
    * Note that it is up to the user of this function to make sure the modified DICOM data is valid.
    *
    * @param whitelist list of tag paths to keep.
    * @return the associated filter Flow
    */
  def whitelistFilter(whitelist: Set[_ <: TagTree]): Flow[DicomPart, DicomPart, NotUsed] =
    tagFilter(_ => false)(currentPath => whitelist.exists(t => t.hasTrunk(currentPath) || t.isTrunkOf(currentPath)))

  /**
    * Filter a stream of dicom parts such that elements with tag paths in the black list are discarded. Tag paths in
    * the blacklist are removed in the root dataset as well as any sequences, and entire sequences or items in sequences
    * can be removed.
    *
    * Note that it is up to the user of this function to make sure the modified DICOM data is valid.
    *
    * @param blacklist list of tag paths to discard.
    * @return the associated filter Flow
    */
  def blacklistFilter(blacklist: Set[_ <: TagTree]): Flow[DicomPart, DicomPart, NotUsed] =
    tagFilter(_ => true)(currentPath => !blacklist.exists(_.isTrunkOf(currentPath)))

  /**
    * Filter a stream of dicom parts such that all elements that are group length elements except
    * file meta information group length, will be discarded. Group Length (gggg,0000) Standard Data Elements
    * have been retired in the standard.
    *
    * Note that it is up to the user of this function to make sure the modified DICOM data is valid.
    *
    * @return the associated filter Flow
    */
  def groupLengthDiscardFilter: Flow[DicomPart, DicomPart, NotUsed] =
    tagFilter(_ => true)(tagPath => !DicomParsing.isGroupLength(tagPath.tag) || DicomParsing.isFileMetaInformation(tagPath.tag))

  /**
    * Discards the file meta information.
    *
    * @return the associated filter Flow
    */
  def fmiDiscardFilter: Flow[DicomPart, DicomPart, NotUsed] =
    tagFilter(_ => false)(tagPath => !DicomParsing.isFileMetaInformation(tagPath.tag))

  /**
    * Filter a stream of DICOM parts leaving only those for which the supplied tag condition is `true`. As the stream of
    * dicom parts is flowing, a `TagPath` state is updated. For each such update, the tag condition is evaluated. If it
    * renders `false`, parts are discarded until it renders `true` again.
    *
    * Note that it is up to the user of this function to make sure the modified DICOM data is valid. When filtering
    * items from a sequence, item indices are preserved (i.e. not updated).
    *
    * @param keepCondition    function that determines if DICOM parts should be discarded (condition false) based on the
    *                         current tag path
    * @param defaultCondition determines whether to keep or discard elements with no tag path such as the preamble and
    *                         synthetic DICOM parts inserted to hold state.
    * @return the filtered flow
    */
  def tagFilter(defaultCondition: DicomPart => Boolean)(keepCondition: TagPath => Boolean): Flow[DicomPart, DicomPart, NotUsed] =
    DicomFlowFactory.create(new DeferToPartFlow[DicomPart] with TagPathTracking[DicomPart] {
      var keeping = false

      override def onPart(part: DicomPart): List[DicomPart] = {
        keeping = tagPath match {
          case EmptyTagPath => defaultCondition(part)
          case path => keepCondition(path)
        }
        if (keeping) part :: Nil else Nil
      }
    })

  /**
    * Filter a stream of DICOM parts based on the last seen element header and the associated keep condition. All other
    * parts are not filtered out (preamble, sequences, items, delimitations etc).
    *
    * @param keepCondition function that determines if DICOM parts should be discarded (condition false) based on the
    *                      most recently encountered element header
    * @return the filtered flow
    */
  def headerFilter(keepCondition: HeaderPart => Boolean): Flow[DicomPart, DicomPart, NotUsed] = Flow[DicomPart]
    .statefulMapConcat(() => {
      var keeping = true

      {
        case part: HeaderPart =>
          keeping = keepCondition(part)
          if (keeping) part :: Nil else Nil
        case part: ValueChunk =>
          if (keeping) part :: Nil else Nil
        case part =>
          keeping = true
          part :: Nil
      }
    })

  /**
    * A flow which passes on the input bytes unchanged, but fails for non-DICOM files, determined by the first
    * element found
    */
  val validateFlow: Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString].via(new ValidateFlow(None, drainIncoming = false))

  /**
    * A flow which passes on the input bytes unchanged, fails for non-DICOM files, validates for DICOM files with supported
    * Media Storage SOP Class UID, Transfer Syntax UID combination passed as context
    */
  def validateFlowWithContext(contexts: Seq[ValidationContext], drainIncoming: Boolean): Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString].via(new ValidateFlow(Some(contexts), drainIncoming))

  /**
    * A flow which deflates the dataset but leaves the meta information intact. Useful when the dicom parsing in `DicomParseFlow`
    * has inflated a deflated (`1.2.840.10008.1.2.1.99 or 1.2.840.10008.1.2.4.95`) file, and analyzed and possibly transformed its
    * elements. At that stage, in order to maintain valid DICOM information, one can either change the transfer syntax to
    * an appropriate value for non-deflated data, or deflate the data again. This flow helps with the latter.
    *
    * This flow may produce deflated data also when the dataset or the entire stream is empty as the deflate stage may
    * output data on finalization.
    *
    * @return the associated `DicomPart` `Flow`
    */
  val deflateDatasetFlow: Flow[DicomPart, DicomPart, NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val decider = builder.add(Flow[DicomPart]
        .statefulMapConcat(() => {
          var route = 0

          {
            case part: HeaderPart if !part.isFmi =>
              route = 1
              (part, route) :: Nil
            case part => (part, route) :: Nil
          }
        }))
      val partition = builder.add(Partition[(DicomPart, Int)](2, _._2))
      val toBytes = Flow.fromFunction[(DicomPart, Int), ByteString](_._1.bytes)
      val toPart = Flow.fromFunction[(DicomPart, Int), DicomPart](_._1)
      val deflater = Compression.deflate(Deflater.BEST_COMPRESSION, nowrap = true)
      val toDeflatedPart = Flow.fromFunction[ByteString, DicomPart](bytes => DeflatedChunk(bigEndian = false, bytes, nowrap = true))
      val merge = builder.add(MergePreferred.create[DicomPart](1))

      decider ~> partition
      partition.out(0) ~> toPart ~> merge.preferred
      partition.out(1) ~> toBytes ~> deflater ~> toDeflatedPart ~> merge.in(0)
      FlowShape(decider.in, merge.out)
    })

  /**
    * Remove elements from stream that may contain large quantities of data (bulk data)
    *
    * Rules ported from [[https://github.com/dcm4che/dcm4che/blob/3.3.8/dcm4che-core/src/main/java/org/dcm4che3/io/BulkDataDescriptor.java#L58 dcm4che]].
    * Defined [[http://dicom.nema.org/medical/dicom/current/output/html/part04.html#table_Z.1-1 here in the DICOM standard]].
    *
    * Note that it is up to the user of this function to make sure the modified DICOM data is valid.
    *
    * @return the associated DicomPart Flow
    */
  val bulkDataFilter: Flow[DicomPart, DicomPart, NotUsed] =
    Flow[DicomPart]
      .statefulMapConcat {

        def normalizeRepeatingGroup(tag: Int) = {
          val gg000000 = tag & 0xffe00000
          if (gg000000 == 0x50000000 || gg000000 == 0x60000000) tag & 0xffe0ffff else tag
        }

        () =>
          var sequenceStack = Seq.empty[SequencePart]
          var discarding = false

        {
          case sq: SequencePart =>
            sequenceStack = sq +: sequenceStack
            sq :: Nil
          case sqd: SequenceDelimitationPart =>
            sequenceStack = sequenceStack.drop(1)
            sqd :: Nil
          case dh: HeaderPart =>
            discarding =
              normalizeRepeatingGroup(dh.tag) match {
                case Tag.PixelDataProviderURL => true
                case Tag.AudioSampleData => true
                case Tag.CurveData => true
                case Tag.SpectroscopyData => true
                case Tag.OverlayData => true
                case Tag.EncapsulatedDocument => true
                case Tag.FloatPixelData => true
                case Tag.DoubleFloatPixelData => true
                case Tag.PixelData => sequenceStack.isEmpty
                case Tag.WaveformData => sequenceStack.length == 1 && sequenceStack.head.tag == Tag.WaveformSequence
                case _ => false
              }
            if (discarding) Nil else dh :: Nil
          case dvc: ValueChunk =>
            if (discarding) Nil else dvc :: Nil
          case p: DicomPart =>
            discarding = false
            p :: Nil
        }
      }

  /**
    * Buffers all file meta information elements and calculates their lengths, then emits the correct file meta
    * information group length attribute followed by remaining FMI.
    */
  def fmiGroupLengthFlow: Flow[DicomPart, DicomPart, NotUsed] = Flow[DicomPart]
    .via(collectFlow(tagPath => tagPath.isRoot && DicomParsing.isFileMetaInformation(tagPath.tag), tagPath => !DicomParsing.isFileMetaInformation(tagPath.tag), "fmigrouplength", 0))
    .via(tagFilter(_ => true)(tagPath => !DicomParsing.isFileMetaInformation(tagPath.tag)))
    .concat(Source.single(DicomEndMarker))
    .statefulMapConcat {

      () =>
        var fmi = List.empty[DicomPart]
        var firstHeader: Option[HeaderPart] = None
        var hasEmitted = false

      {
        case fmiElements: ElementsPart if fmiElements.label == "fmigrouplength" =>
          val elements = fmiElements.elements
          if (elements.data.nonEmpty) {
            val bigEndian = elements.data.headOption.exists(_.bigEndian)
            val explicitVR = elements.data.headOption.forall(_.explicitVR)
            val fmiElementsNoLength = elements.filter(_.tag != Tag.FileMetaInformationGroupLength)
            val length = fmiElementsNoLength.data.map(_.toBytes.length).sum
            val lengthHeader = HeaderPart(Tag.FileMetaInformationGroupLength, VR.UL, 4, isFmi = true, bigEndian, explicitVR)
            val lengthChunk = ValueChunk(bigEndian, intToBytes(length, bigEndian), last = true)
            fmi = lengthHeader :: lengthChunk :: fmiElementsNoLength.toParts
          }
          Nil

        case preamble: PreamblePart =>
          if (hasEmitted)
            preamble :: Nil
          else {
            hasEmitted = true
            preamble :: fmi
          }

        case header: HeaderPart =>
          if (firstHeader.isEmpty) firstHeader = Some(header)
          if (hasEmitted)
            header :: Nil
          else {
            hasEmitted = true
            fmi ::: header :: Nil
          }

        case DicomEndMarker =>
          if (hasEmitted)
            Nil
          else {
            hasEmitted = true
            fmi
          }

        case part => part :: Nil
      }
    }

  /**
    * Remove all DICOM parts that do not contribute to file bytes
    */
  val emptyPartsFilter: Flow[DicomPart, DicomPart, NotUsed] = Flow[DicomPart].filter(_.bytes.nonEmpty)

  /**
    * Sets any sequences and/or items with known length to indeterminate length and inserts delimiters.
    */
  def toIndeterminateLengthSequences: Flow[DicomPart, DicomPart, NotUsed] =
    DicomFlowFactory.create(new IdentityFlow with GuaranteedDelimitationEvents[DicomPart] { // map to indeterminate length
      val indeterminateBytes = ByteString(0xFF, 0xFF, 0xFF, 0xFF)

      override def onSequence(part: SequencePart): List[DicomPart] =
        super.onSequence(part).map {
          case s: SequencePart if !s.indeterminate => part.copy(length = indeterminateLength, bytes = part.bytes.dropRight(4) ++ indeterminateBytes)
          case p => p
        }

      override def onSequenceDelimitation(part: SequenceDelimitationPart): List[DicomPart] =
        super.onSequenceDelimitation(part) ::: (
          if (part.bytes.isEmpty)
            SequenceDelimitationPart(part.bigEndian, itemDelimitation(part.bigEndian)) :: Nil
          else
            Nil)

      override def onItem(part: ItemPart): List[DicomPart] =
        super.onItem(part).map {
          case i: ItemPart if !inFragments && !i.indeterminate => part.copy(length = indeterminateLength, bytes = part.bytes.dropRight(4) ++ indeterminateBytes)
          case p => p
        }

      override def onItemDelimitation(part: ItemDelimitationPart): List[DicomPart] =
        super.onItemDelimitation(part) ::: (
          if (part.bytes.isEmpty)
            ItemDelimitationPart(part.index, part.bigEndian, itemDelimitation(part.bigEndian)) :: Nil
          else
            Nil)
    })

  /**
    * Convert all string values to UTF-8 corresponding to the DICOM character set ISO_IR 192. First collects the
    * SpecificCharacterSet element. Any subsequent elements of VR type LO, LT, PN, SH, ST or UT will be decoded
    * using the character set(s) of the dataset, and replaced by a value encoded using UTF-8.
    *
    * Changing element values will change and update the length of individual elements, but does not update group
    * length elements and sequences and items with specified length. The recommended approach is to remove group
    * length elements and make sure sequences and items have indeterminate length using appropriate flows.
    *
    * @return the associated DicomPart Flow
    */
  def toUtf8Flow: Flow[DicomPart, DicomPart, NotUsed] = Flow[DicomPart]
    .via(collectFlow(Set(TagPath.fromTag(Tag.SpecificCharacterSet)), "toutf8"))
    .via(modifyFlow(insertions = Seq(TagInsertion(TagPath.fromTag(Tag.SpecificCharacterSet), _ => ByteString("ISO_IR 192")))))
    .statefulMapConcat {

      () =>
        var characterSets: CharacterSets = defaultCharacterSet
        var currentHeader: Option[HeaderPart] = None
        var currentValue = ByteString.empty

      {
        case attr: ElementsPart if attr.label == "toutf8" =>
          characterSets = attr.elements(Tag.SpecificCharacterSet).map {
            case e: ValueElement => CharacterSets(e)
            case _ => characterSets
          }.getOrElse(characterSets)
          Nil
        case header: HeaderPart =>
          if (header.length > 0 && CharacterSets.isVrAffectedBySpecificCharacterSet(header.vr)) {
            currentHeader = Some(header)
            currentValue = ByteString.empty
            Nil
          } else {
            currentHeader = None
            header :: Nil
          }
        case value: ValueChunk if currentHeader.isDefined =>
          currentValue = currentValue ++ value.bytes
          if (value.last) {
            val newValue = currentHeader
              .map(h => characterSets.decode(h.vr, currentValue).getBytes(utf8Charset))
              .map(ByteString.apply)
            val newLength = newValue.map(_.length)
            val newElement = for {
              h <- currentHeader
              v <- newValue
              l <- newLength
            } yield h.withUpdatedLength(l) :: ValueChunk(h.bigEndian, v, last = true) :: Nil
            newElement.getOrElse(Nil)
          } else Nil
        case p: DicomPart =>
          p :: Nil
      }
    }

  /**
    * Ensure that the data has transfer syntax explicit VR little endian. Changes the TransferSyntaxUID, if present,
    * to 1.2.840.10008.1.2.1 to reflect this.
    *
    * @return the associated DicomPart Flow
    */
  def toExplicitVrLittleEndianFlow: Flow[DicomPart, DicomPart, NotUsed] =
    modifyFlow(modifications = Seq(TagModification.equals(TagPath.fromTag(Tag.TransferSyntaxUID), _ => padToEvenLength(ByteString(UID.ExplicitVRLittleEndian), VR.UI))))
      .via(fmiGroupLengthFlow)
      .statefulMapConcat {

        case class SwapResult(bytes: ByteString, carry: ByteString)

        def swap(k: Int, b: ByteString): SwapResult =
          SwapResult(b.grouped(k).map(_.reverse).reduce(_ ++ _), b.takeRight(b.length % k))

        () =>
          var currentVr: Option[VR] = None
          var carryBytes = ByteString.empty

          def updatedValue(swapResult: SwapResult, last: Boolean): ValueChunk = {
            carryBytes = swapResult.carry
            if (last && carryBytes.nonEmpty)
              throw new DicomStreamException("Dicom value length does not match length specified in header")
            ValueChunk(bigEndian = false, swapResult.bytes, last = last)
          }

        {
          case h: HeaderPart if h.bigEndian || !h.explicitVR =>
            if (h.bigEndian) {
              carryBytes = ByteString.empty
              currentVr = Some(h.vr)
            } else currentVr = None
            HeaderPart(h.tag, h.vr, h.length, h.isFmi) :: Nil

          case v: ValueChunk if currentVr.isDefined && v.bigEndian =>
            currentVr match {
              case Some(vr) if vr == VR.US || vr == VR.SS || vr == VR.OW || vr == VR.AT => // 2 byte
                updatedValue(swap(2, carryBytes ++ v.bytes), v.last) :: Nil
              case Some(vr) if vr == VR.OF || vr == VR.UL || vr == VR.SL || vr == VR.FL => // 4 bytes
                updatedValue(swap(4, carryBytes ++ v.bytes), v.last) :: Nil
              case Some(vr) if vr == VR.OD || vr == VR.FD => // 8 bytes
                updatedValue(swap(8, carryBytes ++ v.bytes), v.last) :: Nil
              case _ => v :: Nil
            }

          case p: DicomPart if !p.bigEndian => p :: Nil

          case s: SequencePart =>
            SequencePart(s.tag, s.length, bigEndian = false, explicitVR = true,
              tagToBytesLE(s.tag) ++ ByteString('S', 'Q', 0, 0) ++ s.bytes.takeRight(4).reverse) :: Nil

          case _: SequenceDelimitationPart =>
            SequenceDelimitationPart(bigEndian = false,
              tagToBytesLE(Tag.SequenceDelimitationItem) ++ ByteString(0, 0, 0, 0)) :: Nil

          case i: ItemPart =>
            ItemPart(i.index, i.length, bigEndian = false,
              tagToBytesLE(Tag.Item) ++ i.bytes.takeRight(4).reverse) :: Nil

          case i: ItemDelimitationPart =>
            ItemDelimitationPart(i.index, bigEndian = false,
              tagToBytesLE(Tag.ItemDelimitationItem) ++ ByteString(0, 0, 0, 0)) :: Nil

          case f: FragmentsPart =>
            if (f.bigEndian) {
              carryBytes = ByteString.empty
              currentVr = Some(f.vr)
            } else currentVr = None
            FragmentsPart(f.tag, f.length, f.vr, bigEndian = false, explicitVR = true,
              tagToBytesLE(f.tag) ++ f.bytes.drop(4).take(4) ++ f.bytes.takeRight(4).reverse) :: Nil

          case p => p :: Nil
        }
      }
}


