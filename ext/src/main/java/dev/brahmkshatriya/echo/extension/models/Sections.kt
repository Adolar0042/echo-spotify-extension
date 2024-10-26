package dev.brahmkshatriya.echo.extension.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Sections(
    val items: List<SectionItem>? = null,
    val pagingInfo: PagingInfo? = null,
    val totalCount: Long? = null
) {


    @Serializable
    data class SectionItem(
        @SerialName("__typename")
        val typename: String? = null,

        val data: Data? = null,
        val sectionItems: Items? = null,
        val targetLocation: String? = null,
        val uri: String? = null
    )

    @Serializable
    data class Data(
        @SerialName("__typename")
        val typename: Typename? = null,

        val subtitle: String? = null,
        val title: Title? = null
    )

    @Serializable
    enum class Typename(val value: String) {
        @SerialName("BrowseGenericSectionData")
        BrowseGenericSectionData("BrowseGenericSectionData"),

        @SerialName("BrowseGridSectionData")
        BrowseGridSectionData("BrowseGridSectionData"),

        @SerialName("BrowseRelatedSectionData")
        BrowseRelatedSectionData("BrowseRelatedSectionData");
    }

    @Serializable
    data class Items(
        val items: List<ItemsItem>? = null,
        val totalCount: Long? = null
    )

    @Serializable
    data class ItemsItem(
        val content: Item.Wrapper,
        val uri: String
    )
}