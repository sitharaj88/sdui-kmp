@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.sdui.kmp.tooling.cli

import dev.sdui.kmp.protocol.A11y
import dev.sdui.kmp.protocol.A11yRole
import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.ActionPolicy
import dev.sdui.kmp.protocol.ButtonStyle
import dev.sdui.kmp.protocol.ColorToken
import dev.sdui.kmp.protocol.ContentScale
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.EdgeInsets
import dev.sdui.kmp.protocol.ElevationToken
import dev.sdui.kmp.protocol.Execution
import dev.sdui.kmp.protocol.HttpMethod
import dev.sdui.kmp.protocol.IconToken
import dev.sdui.kmp.protocol.Keyboard
import dev.sdui.kmp.protocol.LiveEvent
import dev.sdui.kmp.protocol.LiveRegion
import dev.sdui.kmp.protocol.ListSource
import dev.sdui.kmp.protocol.NavKind
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.OptimisticUpdate
import dev.sdui.kmp.protocol.Orientation
import dev.sdui.kmp.protocol.Pagination
import dev.sdui.kmp.protocol.PatchOp
import dev.sdui.kmp.protocol.Predicate
import dev.sdui.kmp.protocol.RadiusToken
import dev.sdui.kmp.protocol.RetryPolicy
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.ScreenId
import dev.sdui.kmp.protocol.ScreenMetadata
import dev.sdui.kmp.protocol.Spacing
import dev.sdui.kmp.protocol.StateDeclaration
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.StateScope
import dev.sdui.kmp.protocol.TextStyleToken
import dev.sdui.kmp.protocol.TreePatch
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.Validation
import dev.sdui.kmp.protocol.Value
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind

/**
 * Walks every @Serializable protocol type via its generated [KSerializer] and emits a
 * [ProtocolSnapshot] describing the structural shape of the protocol.
 *
 * The lists below are maintained by hand: every new @Serializable type added to `:protocol`
 * must be added to the corresponding list or its shape won't be covered by the linter. A
 * future tooling-lint can enforce this by comparing the registered types against every class
 * annotated `@Serializable` in the protocol module.
 */
@OptIn(ExperimentalSerializationApi::class)
public fun captureProtocolSnapshot(): ProtocolSnapshot {
    val sealed = mutableMapOf<String, ProtocolSnapshot.SealedHierarchy>()
    val enums = mutableMapOf<String, List<String>>()
    val data = mutableMapOf<String, ProtocolSnapshot.DataClassShape>()

    // Sealed polymorphic hierarchies.
    val sealedSerializers: List<Pair<String, KSerializer<*>>> = listOf(
        "UiNode" to UiNode.serializer(),
        "Action" to Action.serializer(),
        "Destination" to Destination.serializer(),
        "Predicate" to Predicate.serializer(),
        "ListSource" to ListSource.serializer(),
        "PatchOp" to PatchOp.serializer(),
        "LiveEvent" to LiveEvent.serializer(),
        "RetryPolicy" to RetryPolicy.serializer(),
        "Validation" to Validation.serializer(),
        "ColorToken" to ColorToken.serializer(),
        "IconToken" to IconToken.serializer(),
        "Value" to Value.serializer(String.serializer()), // T phantom; shape is the same for any T
    )
    for ((name, ser) in sealedSerializers) {
        sealed[name] = ser.descriptor.toSealedHierarchy()
    }

    // Enums.
    val enumSerializers: List<Pair<String, KSerializer<*>>> = listOf(
        "Spacing" to Spacing.serializer(),
        "TextStyleToken" to TextStyleToken.serializer(),
        "RadiusToken" to RadiusToken.serializer(),
        "ElevationToken" to ElevationToken.serializer(),
        "StateScope" to StateScope.serializer(),
        "A11yRole" to A11yRole.serializer(),
        "LiveRegion" to LiveRegion.serializer(),
        "Orientation" to Orientation.serializer(),
        "NavKind" to NavKind.serializer(),
        "ButtonStyle" to ButtonStyle.serializer(),
        "HttpMethod" to HttpMethod.serializer(),
        "Execution" to Execution.serializer(),
        "ContentScale" to ContentScale.serializer(),
        "Keyboard" to Keyboard.serializer(),
    )
    for ((name, ser) in enumSerializers) {
        val d = ser.descriptor
        require(d.kind == SerialKind.ENUM) { "$name is not an enum serial descriptor" }
        enums[name] = (0 until d.elementsCount).map { d.getElementName(it) }
    }

    // Non-sealed data classes and value classes that carry structural info.
    val dataSerializers: List<Pair<String, KSerializer<*>>> = listOf(
        "NodeId" to NodeId.serializer(),
        "StatePath" to StatePath.serializer(),
        "ScreenId" to ScreenId.serializer(),
        "SchemaVersion" to SchemaVersion.serializer(),
        "Screen" to Screen.serializer(),
        "ScreenMetadata" to ScreenMetadata.serializer(),
        "StateDeclaration" to StateDeclaration.serializer(),
        "A11y" to A11y.serializer(),
        "EdgeInsets" to EdgeInsets.serializer(),
        "ActionPolicy" to ActionPolicy.serializer(),
        "OptimisticUpdate" to OptimisticUpdate.serializer(),
        "Pagination" to Pagination.serializer(),
        "TreePatch" to TreePatch.serializer(),
    )
    for ((name, ser) in dataSerializers) {
        data[name] = ser.descriptor.toDataClassShape()
    }

    return ProtocolSnapshot(
        version = 1,
        sealedHierarchies = sealed.toSortedMap(),
        enums = enums.toSortedMap().mapValues { it.value.toList().sortedBy { case -> case } },
        dataClasses = data.toSortedMap(),
    )
}

@OptIn(ExperimentalSerializationApi::class)
private fun SerialDescriptor.toDataClassShape(): ProtocolSnapshot.DataClassShape {
    val fields = linkedMapOf<String, ProtocolSnapshot.FieldShape>()
    for (i in 0 until elementsCount) {
        val name = getElementName(i)
        val elementDesc = getElementDescriptor(i)
        fields[name] = ProtocolSnapshot.FieldShape(
            type = elementDesc.serialName,
            nullable = elementDesc.isNullable,
            optional = isElementOptional(i),
        )
    }
    return ProtocolSnapshot.DataClassShape(fields = fields.toSortedMap())
}

@OptIn(ExperimentalSerializationApi::class)
private fun SerialDescriptor.toSealedHierarchy(): ProtocolSnapshot.SealedHierarchy {
    require(kind == PolymorphicKind.SEALED) { "$serialName is not a sealed descriptor" }
    // Sealed descriptor layout: element 0 = discriminator name, element 1 = polymorphic value.
    val discriminatorName = getElementName(0)
    val valueDesc = getElementDescriptor(1)
    val subtypes = linkedMapOf<String, ProtocolSnapshot.DataClassShape>()
    for (i in 0 until valueDesc.elementsCount) {
        val subName = valueDesc.getElementName(i)
        val subDesc = valueDesc.getElementDescriptor(i)
        subtypes[subName] = when (subDesc.kind) {
            is StructureKind.CLASS, StructureKind.OBJECT -> subDesc.toDataClassShape()
            else -> ProtocolSnapshot.DataClassShape(fields = emptyMap())
        }
    }
    return ProtocolSnapshot.SealedHierarchy(
        discriminator = discriminatorName,
        subtypes = subtypes.toSortedMap(),
    )
}
