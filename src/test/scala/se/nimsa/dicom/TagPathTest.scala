package se.nimsa.dicom

import org.scalatest.{FlatSpec, Matchers}

class TagPathTest extends FlatSpec with Matchers {

  "The tag path depth" should "be 0 when pointing to a tag in the root dataset" in {
    val path = TagPath.fromTag(Tag.PatientID)
    path.depth shouldBe 0
  }

  it should "be 4 when pointing to a tag nested in two levels of sequences" in {
    val path = TagPath.fromSequence(Tag.DerivationCodeSequence).thenItem(3).thenSequence(Tag.DerivationCodeSequence).thenAnyItem().thenTag(Tag.PatientID)
    path.depth shouldBe 4
  }

  "A tag path" should "have a legible string representation" in {
    val path1 = TagPath.fromSequence(Tag.DerivationCodeSequence).thenItem(3).thenSequence(Tag.DerivationCodeSequence).thenAnyItem().thenTag(Tag.PatientID)
    val path2 = TagPath.fromSequence(Tag.DerivationCodeSequence).thenItemStart(3)
    val path3 = TagPath.fromSequenceStart(Tag.DerivationCodeSequence)
    val path4 = TagPath.fromSequence(Tag.DerivationCodeSequence).thenItem(3).thenTag(Tag.PatientName).toItemEnd

    path1.toString shouldBe "(0008,9215)[3].(0008,9215)[*].(0010,0020)"
    path2.toString shouldBe "(0008,9215)[3]"
    path3.toString shouldBe "(0008,9215)"
    path4.toString shouldBe "(0008,9215)[3]"
  }

  it should "be root when pointing to root dataset" in {
    val path = TagPath.fromTag(Tag.PatientID)
    path.isRoot shouldBe true
  }

  it should "not be root when pointing to a tag in a sequence" in {
    val path = TagPath.fromSequence(Tag.DerivationCodeSequence).thenAnyItem().thenTag(Tag.PatientID)
    path.isRoot shouldBe false
  }

  "A list representation of tag path tags" should "contain a single entry for a tag in the root dataset" in {
    val path = TagPath.fromTag(Tag.PatientID)
    path.toList shouldBe path :: Nil
  }

  it should "contain five entries for a path of depth 4" in {
    val path = TagPath.fromSequence(Tag.DerivationCodeSequence).thenItem(3).thenSequence(Tag.DerivationCodeSequence).thenAnyItem().thenTag(Tag.PatientID)
    path.toList shouldBe path.previous.get.previous.get.previous.get.previous.get :: path.previous.get.previous.get.previous.get :: path.previous.get.previous.get :: path.previous.get :: path :: Nil
  }

  "Comparing two tag paths with less than" should "return false when comparing a tag path to itself" in {
    val path = TagPath.fromTag(1)
    path < path shouldBe false
  }

  it should "return false when comparing two equivalent tag paths" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenSequence(1).thenAnyItem().thenTag(2)
    val bPath = TagPath.fromSequence(1).thenItem(3).thenSequence(1).thenAnyItem().thenTag(2)
    aPath < bPath shouldBe false
  }

  it should "sort them by tag number for depth 0 paths" in {
    val aPath = TagPath.fromTag(1)
    val bPath = TagPath.fromTag(2)
    aPath < bPath shouldBe true
  }

  it should "sort them by tag number for depth 1 paths" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenTag(2)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenTag(1)
    aPath < bPath shouldBe false
  }

  it should "sort by earliest difference in deep paths" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(1).thenAnyItem().thenTag(1)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenTag(1)
    aPath < bPath shouldBe true
  }

  it should "sort longer lists after shorter lists when otherwise equivalent" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3)
    aPath < bPath shouldBe false
  }

  it should "sort by item number in otherwise equivalent paths" in {
    val aPath = TagPath.fromSequence(1).thenItem(2).thenSequence(1).thenAnyItem().thenTag(2)
    val bPath = TagPath.fromSequence(1).thenItem(3).thenSequence(1).thenAnyItem().thenTag(2)
    aPath < bPath shouldBe true
  }

  it should "sort paths with equal tag number by item index, with wildcard less than index and index less than wildcard (undefined)" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(1).thenAnyItem().thenSequence(1).thenAnyItem().thenTag(2)
    val bPath = TagPath.fromSequence(1).thenItem(3).thenSequence(1).thenAnyItem().thenTag(2)
    aPath < bPath shouldBe true
    bPath < aPath shouldBe true
  }

  it should "sort a list of paths with the .sortWith function" in {
    val paths = List(TagPath.fromSequence(Tag.DerivationCodeSequence).thenItem(1).thenTag(Tag.PatientID), TagPath.fromTag(Tag.PatientID))
    val sortedPaths = paths.sortWith((a, b) => a < b)
    sortedPaths shouldBe paths
  }

  "Two tag paths" should "be equal if they point to the same path" in {
    val aPath = TagPath.fromSequence(1).thenItem(4).thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenItem(4).thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    aPath shouldBe bPath
  }

  it should "not be equal if item indices do not match" in {
    val aPath = TagPath.fromSequence(1).thenItem(1).thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenItem(2).thenSequence(3).thenAnyItem().thenTag(4)
    aPath should not be bPath
  }

  it should "not be equal if they point to different tags" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(5)
    aPath should not be bPath
  }

  it should "not be equal if they have different depths" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    aPath should not be bPath
  }

  it should "not be equal if one points to all indices of a sequence and the other points to a specific index" in {
    val aPath = TagPath.fromSequence(1)
    val bPath = TagPath.fromSequence(1).thenItem(1)
    aPath should not be bPath
  }

  "The startsWith test" should "return true for equal paths" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    aPath.startsWith(bPath) shouldBe true
  }

  it should "return false when subject path is longer than path" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    aPath.startsWith(bPath) shouldBe false
  }

  it should "return true when paths involving item indices are equal" in {
    val aPath = TagPath.fromSequence(1).thenItem(4).thenSequence(2).thenItem(2).thenTag(4)
    val bPath = TagPath.fromSequence(1).thenItem(4).thenSequence(2).thenItem(2).thenTag(4)
    aPath.startsWith(bPath) shouldBe true
  }

  it should "return false when a path with wildcards starts with a path with item indices" in {
    val aPath = TagPath.fromSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(2).thenItem(4).thenSequence(3).thenItem(66).thenTag(4)
    aPath.startsWith(bPath) shouldBe false
  }

  it should "return false when a path with item indices starts with a path with wildcards" in {
    val aPath = TagPath.fromSequence(2).thenItem(4).thenSequence(3).thenItem(66).thenTag(4)
    val bPath = TagPath.fromSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    aPath.startsWith(bPath) shouldBe false
  }

  it should "return true when subject path is subset of path" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3)
    aPath.startsWith(bPath) shouldBe true
  }

  "The startsWithSubPath test" should "return true for equal paths" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    aPath.startsWithSubPath(bPath) shouldBe true
  }

  it should "return false when subject path is longer than path" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    aPath.startsWithSubPath(bPath) shouldBe false
  }

  it should "return true when paths involving item indices are equal" in {
    val aPath = TagPath.fromSequence(1).thenItem(4).thenSequence(2).thenItem(2).thenTag(4)
    val bPath = TagPath.fromSequence(1).thenItem(4).thenSequence(2).thenItem(2).thenTag(4)
    aPath.startsWithSubPath(bPath) shouldBe true
  }

  it should "return true when a path with wildcards starts with a path with item indices" in {
    val aPath = TagPath.fromSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(2).thenItem(4).thenSequence(3).thenItem(66).thenTag(4)
    aPath.startsWithSubPath(bPath) shouldBe true
  }

  it should "return false when a path with item indices starts with a path with wildcards" in {
    val aPath = TagPath.fromSequence(2).thenItem(4).thenSequence(3).thenItem(66).thenTag(4)
    val bPath = TagPath.fromSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    aPath.startsWithSubPath(bPath) shouldBe false
  }

  it should "return true when subject path is subset of path" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3)
    aPath.startsWithSubPath(bPath) shouldBe true
  }

  "The startsWithSuperPath test" should "return true for equal paths" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    aPath.startsWithSuperPath(bPath) shouldBe true
  }

  it should "return false when subject path is longer than path" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    aPath.startsWithSuperPath(bPath) shouldBe false
  }

  it should "return true when paths involving item indices are equal" in {
    val aPath = TagPath.fromSequence(1).thenItem(4).thenSequence(2).thenAnyItem().thenSequence(3).thenItem(2).thenTag(4)
    val bPath = TagPath.fromSequence(1).thenItem(4).thenSequence(2).thenAnyItem().thenSequence(3).thenItem(2).thenTag(4)
    aPath.startsWithSuperPath(bPath) shouldBe true
  }

  it should "return false when a path with wildcards starts with a path with item indices" in {
    val aPath = TagPath.fromSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(2).thenItem(4).thenSequence(3).thenItem(66).thenTag(4)
    aPath.startsWithSuperPath(bPath) shouldBe false
  }

  it should "return true when a path with item indices starts with a path with wildcards" in {
    val aPath = TagPath.fromSequence(2).thenItem(4).thenSequence(3).thenItem(66).thenTag(4)
    val bPath = TagPath.fromSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    aPath.startsWithSuperPath(bPath) shouldBe true
  }

  it should "return true when subject path is subset of path" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenAnyItem().thenSequence(3)
    aPath.startsWithSuperPath(bPath) shouldBe true
  }

  "The super path test" should "return false for unequal length paths" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenSequence(2).thenItem(4).thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenItem(3).thenSequence(2).thenItem(4).thenTag(4)
    aPath.hasSubPath(bPath) shouldBe false
  }

  it should "return true when subject path has items and path has wildcards" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenItem(4).thenTag(4)
    val bPath = TagPath.fromSequence(1).thenItem(3).thenSequence(2).thenItem(4).thenTag(4)
    aPath.hasSubPath(bPath) shouldBe true
  }

  it should "return false when subject path has wildcards and path has items" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenSequence(2).thenItem(4).thenTag(4)
    val bPath = TagPath.fromSequence(1).thenItem(3).thenSequence(2).thenAnyItem().thenTag(4)
    aPath.hasSubPath(bPath) shouldBe false
  }

  "The sub path test" should "return false for unequal length paths" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenSequence(2).thenItem(4).thenSequence(3).thenAnyItem().thenTag(4)
    val bPath = TagPath.fromSequence(1).thenItem(3).thenSequence(2).thenItem(4).thenTag(4)
    aPath.hasSuperPath(bPath) shouldBe false
  }

  it should "return false when subject path has items and path has wildcards" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenSequence(2).thenItem(4).thenTag(4)
    val bPath = TagPath.fromSequence(1).thenItem(3).thenSequence(2).thenItem(4).thenTag(4)
    aPath.hasSuperPath(bPath) shouldBe false
  }

  it should "return true when subject path has wildcards and path has items" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenSequence(2).thenItem(4).thenTag(4)
    val bPath = TagPath.fromSequence(1).thenItem(3).thenSequence(2).thenAnyItem().thenTag(4)
    aPath.hasSuperPath(bPath) shouldBe true
  }

  "The endsWith test" should "return true when a longer tag ends with a shorter" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenTag(2)
    val bPath = TagPath.fromTag(2)
    aPath.endsWith(bPath) shouldBe true
  }

  it should "return false when a shorter tag is compared to a longer" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenTag(2)
    val bPath = TagPath.fromTag(2)
    bPath.endsWith(aPath) shouldBe false
  }

  it should "return false when tag numbers do not match" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenTag(2)
    val bPath = TagPath.fromTag(4)
    aPath.endsWith(bPath) shouldBe false
  }

  it should "work also with deep sequences" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenSequence(2).thenItem(4).thenSequence(3).thenItem(5).thenTag(6)
    val bPath = TagPath.fromSequence(2).thenItem(4).thenSequence(3).thenItem(5).thenTag(6)
    aPath.endsWith(bPath) shouldBe true
  }

  it should "return false for a subject path with wildcards when the path has item indices and vice versa" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenTag(2)
    val bPath = TagPath.fromSequence(1).thenItem(4).thenTag(2)
    aPath.endsWith(bPath) shouldBe false
    bPath.endsWith(aPath) shouldBe false
  }

  "The endsWithSubPath test" should "return true when a longer tag ends with a shorter" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenTag(2)
    val bPath = TagPath.fromTag(2)
    aPath.endsWithSubPath(bPath) shouldBe true
  }

  it should "return false when a shorter tag is compared to a longer" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenTag(2)
    val bPath = TagPath.fromTag(2)
    bPath.endsWithSubPath(aPath) shouldBe false
  }

  it should "return false when tag numbers do not match" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenTag(2)
    val bPath = TagPath.fromTag(4)
    aPath.endsWithSubPath(bPath) shouldBe false
  }

  it should "work also with deep sequences" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenSequence(2).thenItem(4).thenSequence(3).thenItem(5).thenTag(6)
    val bPath = TagPath.fromSequence(2).thenItem(4).thenSequence(3).thenItem(5).thenTag(6)
    aPath.endsWithSubPath(bPath) shouldBe true
  }

  it should "return true for a subject path with wildcards when the path has item indices" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenTag(2)
    val bPath = TagPath.fromSequence(1).thenItem(4).thenTag(2)
    aPath.endsWithSubPath(bPath) shouldBe true
  }

  it should "return false for a subject path with item indices when the path has wildcards" in {
    val aPath = TagPath.fromSequence(1).thenItem(4).thenTag(2)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenTag(2)
    aPath.endsWithSubPath(bPath) shouldBe false
  }

  "The endsWithSuperPath test" should "return true when a longer tag ends with a shorter" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenTag(2)
    val bPath = TagPath.fromTag(2)
    aPath.endsWithSuperPath(bPath) shouldBe true
  }

  it should "return false when a shorter tag is compared to a longer" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenTag(2)
    val bPath = TagPath.fromTag(2)
    bPath.endsWithSuperPath(aPath) shouldBe false
  }

  it should "return false when tag numbers do not match" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenTag(2)
    val bPath = TagPath.fromTag(4)
    aPath.endsWithSuperPath(bPath) shouldBe false
  }

  it should "work also with deep sequences" in {
    val aPath = TagPath.fromSequence(1).thenItem(3).thenSequence(2).thenItem(4).thenSequence(3).thenItem(5).thenTag(6)
    val bPath = TagPath.fromSequence(2).thenItem(4).thenSequence(3).thenItem(5).thenTag(6)
    aPath.endsWithSuperPath(bPath) shouldBe true
  }

  it should "return false for a subject path with wildcards when the path has item indices" in {
    val aPath = TagPath.fromSequence(1).thenAnyItem().thenTag(2)
    val bPath = TagPath.fromSequence(1).thenItem(4).thenTag(2)
    aPath.endsWithSuperPath(bPath) shouldBe false
  }

  it should "return true for a subject path with item indices when the path has wildcards" in {
    val aPath = TagPath.fromSequence(1).thenItem(4).thenTag(2)
    val bPath = TagPath.fromSequence(1).thenAnyItem().thenTag(2)
    aPath.endsWithSuperPath(bPath) shouldBe true
  }

  "Parsing a tag path" should "work for well-formed depth 0 tag paths" in {
    TagPath.parse("(0010,0010)") shouldBe TagPath.fromTag(Tag.PatientName)
  }

  it should "work for deep tag paths" in {
    TagPath.parse("(0008,9215)[*].(0008,9215)[666].(0010,0010)") shouldBe TagPath.fromSequence(Tag.DerivationCodeSequence).thenAnyItem().thenSequence(Tag.DerivationCodeSequence).thenItem(666).thenTag(Tag.PatientName)
  }

  it should "throw an exception for malformed strings" in {
    intercept[IllegalArgumentException] {
      TagPath.parse("abc")
    }
  }

  it should "throw an exception for empty strings" in {
    intercept[IllegalArgumentException] {
      TagPath.parse("")
    }
  }

  "The contains test" should "return for any tag number on the tag path" in {
    val path = TagPath.fromSequence(1).thenItem(1).thenSequence(2).thenAnyItem().thenTag(3)
    path.contains(1) shouldBe true
    path.contains(2) shouldBe true
    path.contains(3) shouldBe true
    path.contains(4) shouldBe false
  }
}
