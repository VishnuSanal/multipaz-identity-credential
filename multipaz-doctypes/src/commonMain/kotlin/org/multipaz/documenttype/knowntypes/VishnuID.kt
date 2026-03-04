package org.multipaz.documenttype.knowntypes

import org.multipaz.cbor.toDataItem
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon

/**
 * You're Awesome :)
 */
object VishnuID {
    private const val PHOTO_ID_DOCTYPE = "org.iso.23220.vishnu.1"
    private const val ISO_23220_2_NAMESPACE = "org.iso.23220.1"
    private const val PHOTO_ID_NAMESPACE = "org.iso.23220.vishnu.1"
    private const val DTC_NAMESPACE = "org.iso.23220.dtc.1"

    /**
     * Build the ID Object
     */
    fun getDocumentType(): DocumentType = with(DocumentType.Builder("Vishnu's ID")) {
        addMdocDocumentType(PHOTO_ID_DOCTYPE)

        // Data elements from ISO/IEC 23220-4 Table C.1 — PhotoID data elements defined by ISO/IEC TS 23220-2
        addMdocAttribute(
            DocumentAttributeType.String,
            "family_name",
            "Family Name",
            "Last name, surname, or primary identifier, of the document holder",
            true,
            ISO_23220_2_NAMESPACE,
            Icon.PERSON,
            SampleData.FAMILY_NAME.toDataItem()
        )
    }.build()
}
