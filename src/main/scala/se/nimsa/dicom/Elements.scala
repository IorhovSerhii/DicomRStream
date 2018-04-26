package se.nimsa.dicom

import akka.util.ByteString
import se.nimsa.dicom.TagPath.TagPathSequence
import se.nimsa.dicom.streams.ElementFolds.TpElement
import se.nimsa.dicom.Element.multiValueDelimiter

/**
  * Representation of a group of `Element`s, each paired with the `TagPath` that describes their position within a
  * dataset. Representation is immutable so methods for inserting, updating and removing elements return a new instance.
  * Also specifies the character sets that should be used for decoding the values of textual elements.
  *
  * @param characterSets The character sets used for decoding text values
  * @param data          the `Map`ping of `TagPath` to `Element`
  */
case class Elements(characterSets: CharacterSets, data: Map[TagPath, Element]) {

  /**
    * Get a single element, if present
    *
    * @param tagPath position in the dataset of the element
    * @return optional Element
    */
  def apply(tagPath: TagPath): Option[Element] = data.get(tagPath)

  /**
    * Get a single element, if present
    *
    * @param tag tag number the element, referring to the root dataset
    * @return optional Element
    */
  def apply(tag: Int): Option[Element] = apply(TagPath.fromTag(tag))

  /**
    * Get a subset of elements based on a tag condition
    *
    * @param tagPathCondition return elements for which this condition yields `true`
    * @return a new Elements
    */
  def apply(tagPathCondition: TagPath => Boolean): Elements =
    Elements(characterSets, data.filterKeys(tp => tagPathCondition(tp)))

  /**
    * Get all elements contained in the specified sequence, if any
    *
    * @param tagPath path to sequence to extract
    * @return a new Elements
    */
  def apply(tagPath: TagPathSequence): Elements =
    apply(_.startsWithSuperPath(tagPath))

  /**
    * Insert or update element at the specified tag path
    *
    * @param tagPath tag path of element to insert or update
    * @param element element to insert or update
    * @return a new Elements containing the updated element
    */
  def update(tagPath: TagPath, element: Element): Elements =
    Elements(characterSets, data + (tagPath -> element))

  /**
    * Insert or update element in the root dataset with the specified tag number
    *
    * @param tag     tag number where element is inserted or updated
    * @param element element to insert or update
    * @return a new Elements containing the updated element
    */
  def update(tag: Int, element: Element): Elements = update(TagPath.fromTag(tag), element)

  /**
    * Update the character sets used to decode string data in this Elements
    *
    * @param characterSets character sets to use in new Elements
    * @return a new Elements with the specified character sets
    */
  def updateCharacterSets(characterSets: CharacterSets): Elements =
    Elements(characterSets, data)

  /**
    * Remove elements where the specified tag condition yields `true`
    *
    * @param tagPathCondition tag path condition to test for each Element
    * @return a new Elements
    */
  def remove(tagPathCondition: TagPath => Boolean): Elements =
    Elements(characterSets, data.filterKeys(tp => !tagPathCondition(tp)))

  /**
    * @return a list of tag paths and elements sorted by tag path
    */
  def toList: List[TpElement] =
    data.map(e => (TpElement.apply _).tupled(e)).toList.sortWith(_.tagPath < _.tagPath)

  /**
    * @return a list of elements sorted by tag path
    */
  def elements: List[Element] = toList.map(_.element)

  /**
    * @return a sorted list of tag paths in this Elements
    */
  def tagPaths: List[TagPath] = toList.map(_.tagPath)

  /**
    * @return the byte array representation of this Elements
    */
  def bytes: ByteString = elements.foldLeft(ByteString.empty)(_ ++ _.bytes)

  /**
    * @return the number of elements in this Elements
    */
  def size: Int = data.size

  override def toString: String = toList.map { tpElement =>
    val tagPath = tpElement.tagPath
    val element = tpElement.element
    val strings = tpElement.element.vr match {
      case VR.OW | VR.OF | VR.OB | VR.OD if element.length > 20 => List(s"< Binary data (${element.length} bytes) >")
      case _ => element.toStrings(characterSets)
    }
    val singleString = strings.mkString(multiValueDelimiter).replaceAll("[\\r\\n]+", " ")
    val shortString = if (singleString.length > 64)
      s"${singleString.take(64)}... (${singleString.length - 64} more chars)" else singleString
    s"$tagPath ${element.vr} ${element.length} ${strings.length} $shortString ${Keyword.valueOf(tagPath.tag)}"
  }.mkString("\r\n")
}

object Elements {
  /**
    * @return an Elements with no data and default character set only
    */
  def empty: Elements = Elements(CharacterSets.defaultOnly, Map.empty)
}

