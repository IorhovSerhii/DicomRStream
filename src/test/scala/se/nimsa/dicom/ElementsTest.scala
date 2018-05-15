package se.nimsa.dicom

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import akka.util.ByteString
import org.scalatest.{AsyncFlatSpecLike, BeforeAndAfterAll, Matchers}
import se.nimsa.dicom.streams.ElementFolds.TpElement

import scala.concurrent.ExecutionContextExecutor

class ElementsTest extends TestKit(ActorSystem("ElementsSpec")) with AsyncFlatSpecLike with Matchers with BeforeAndAfterAll {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  override def afterAll(): Unit = system.terminate()

  val studyDate: Element = Element.explicitLE(Tag.StudyDate, VR.DA, ByteString(20041230))
  val patientName: Element = Element.explicitLE(Tag.PatientName,VR.PN, ByteString("John^Doe"))
  val patientID1: Element = Element.explicitLE(Tag.PatientID, VR.LO, ByteString("12345678"))
  val patientID2: Element = Element.explicitLE(Tag.PatientID, VR.LO, ByteString("87654321"))
  val patientID3: Element = Element.explicitLE(Tag.PatientID, VR.LO, ByteString("18273645"))
  val seq: Element = Element.explicitLE(Tag.DerivationCodeSequence, VR.SQ, ByteString.empty)

  val studyDateTag: TagPath = TagPath.fromTag(Tag.StudyDate)
  val patientNameTag: TagPath = TagPath.fromTag(Tag.PatientName)
  val patientIDTag: TagPath= TagPath.fromTag(Tag.PatientID)
  val seqTag: TagPath = TagPath.fromSequence(Tag.DerivationCodeSequence)
  val patientIDSeqTag1: TagPath = TagPath.fromSequence(Tag.DerivationCodeSequence, 1).thenTag(Tag.PatientID)
  val patientIDSeqTag2: TagPath = TagPath.fromSequence(Tag.DerivationCodeSequence, 2).thenTag(Tag.PatientID)

  val elements = Elements(CharacterSets.defaultOnly, Map(
    studyDateTag -> studyDate,
    seqTag -> seq,
    patientIDSeqTag1 -> patientID1,
    patientIDSeqTag2 -> patientID2,
    patientNameTag -> patientName))

  "Elements" should "return an existing tag" in {
    elements(patientNameTag) shouldBe Some(patientName)
    elements(Tag.PatientName) shouldBe Some(patientName)
    elements(patientIDSeqTag1) shouldBe Some(patientID1)
    elements(patientIDSeqTag2) shouldBe Some(patientID2)
  }

  it should "return elements based on tag path condition" in {
    val elements2 = elements(patientIDTag) = patientID3
    elements2.filter(_.endsWith(patientIDTag)) shouldBe Elements(elements.characterSets, Map(
      patientIDSeqTag1 -> patientID1,
      patientIDSeqTag2 -> patientID2,
      patientIDTag -> patientID3))
  }

  it should "return a nested elements" in {
    elements.sequence(TagPath.fromSequence(Tag.DerivationCodeSequence)) shouldBe Elements(CharacterSets.defaultOnly, Map(patientIDTag -> patientID1, patientIDTag -> patientID2))
    elements.sequence(TagPath.fromSequence(Tag.DerivationCodeSequence, 1)) shouldBe Elements(CharacterSets.defaultOnly, Map(patientIDTag -> patientID1))
    elements.sequence(TagPath.fromSequence(Tag.DerivationCodeSequence, 2)) shouldBe Elements(CharacterSets.defaultOnly, Map(patientIDTag -> patientID2))
  }

  it should "remove element if present" in {
    elements.remove(_.startsWithSuperPath(TagPath.fromSequence(Tag.DerivationCodeSequence))) shouldBe Elements(elements.characterSets, Map(
      studyDateTag -> studyDate,
      patientNameTag -> patientName))
    elements.remove(_.endsWith(patientNameTag)) shouldBe Elements(elements.characterSets, Map(
      studyDateTag -> studyDate,
      seqTag -> seq,
      patientIDSeqTag1 -> patientID1,
      patientIDSeqTag2 -> patientID2))
    elements.remove(_.equals(studyDateTag)) shouldBe Elements(elements.characterSets, Map(
      seqTag -> seq,
      patientIDSeqTag1 -> patientID1,
      patientIDSeqTag2 -> patientID2,
      patientNameTag -> patientName))
    elements.remove(_.endsWith(TagPath.fromTag(Tag.Modality))) shouldBe elements
  }

  it should "insert elements in the correct position" in {
    val characterSets = Element.explicitLE(Tag.SpecificCharacterSet, VR.CS,ByteString("CS1 "))
    val modality = Element.explicitLE(Tag.Modality, VR.CS, ByteString("NM"))
    val characterSetsTag = TagPath.fromTag(Tag.SpecificCharacterSet)
    val modalityTag = TagPath.fromTag(Tag.Modality)
    elements.update(patientIDTag, patientID3).data shouldBe Map(
      studyDateTag -> studyDate,
      seqTag -> seq,
      patientIDSeqTag1 -> patientID1,
      patientIDSeqTag2 -> patientID2,
      patientNameTag -> patientName,
      patientIDTag -> patientID3)
    elements.update(TagPath.fromTag(Tag.SpecificCharacterSet), characterSets).data shouldBe Map(
      characterSetsTag -> characterSets,
      studyDateTag -> studyDate,
      seqTag -> seq,
      patientIDSeqTag1 -> patientID1,
      patientIDSeqTag2 -> patientID2,
      patientNameTag -> patientName)
    elements.update(TagPath.fromTag(Tag.Modality), modality).data shouldBe Map(
      studyDateTag -> studyDate,
      modalityTag -> modality,
      seqTag -> seq,
      patientIDSeqTag1 -> patientID1,
      patientIDSeqTag2 -> patientID2,
      patientNameTag -> patientName)
  }

  it should "overwrite element if already present" in {
    val newPatientName = patientName.copy(value = ByteString("Jane^Doe"))
    val updated = elements.update(patientNameTag, newPatientName)

    updated.size shouldBe elements.size
    updated(patientNameTag).get.value.utf8String shouldBe "Jane^Doe"
  }

  it should "update character sets" in {
    val updatedCs = elements.updateCharacterSets(CharacterSets(ByteString("\\ISO 2022 IR 127"))).characterSets
    updatedCs.charsetNames shouldBe Seq("", "ISO 2022 IR 127")
  }

  it should "return properly sorted elements" in {
    elements.toList shouldBe List(
      TpElement(studyDateTag, studyDate),
      TpElement(seqTag, seq),
      TpElement(patientIDSeqTag1, patientID1),
      TpElement(patientIDSeqTag2, patientID2),
      TpElement(patientNameTag, patientName))
    elements.elements shouldBe List(studyDate, seq, patientID1, patientID2, patientName)
    elements.tagPaths shouldBe List(studyDateTag, seqTag, patientIDSeqTag1, patientIDSeqTag2, patientNameTag)
  }

  it should "aggregate the bytes of all its elements" in {
    Elements(CharacterSets.defaultOnly, Map(
      TagPath.fromTag(Tag.PatientName) -> Element.explicitLE(Tag.PatientName,VR.PN,TestData.patientNameJohnDoe().drop(8)),
      TagPath.fromTag(Tag.PatientID) -> Element.explicitLE(Tag.PatientID, VR.LO, TestData.patientID().drop(8))
    )).bytes shouldBe (TestData.patientNameJohnDoe() ++ TestData.patientID())
  }

  it should "return an empty byte string when aggregating bytes with no data" in {
    Elements.empty.bytes shouldBe ByteString.empty
  }

  it should "render an informative string representation" in {
    val s = elements.toString
    s.count(_ == '\r') shouldBe (elements.size - 1)
  }
}
