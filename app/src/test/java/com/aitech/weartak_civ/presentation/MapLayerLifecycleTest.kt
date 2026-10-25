package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.Feature.get
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.collator
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.lang.reflect.Proxy
import kotlin.coroutines.EmptyCoroutineContext
import org.maplibre.android.style.layers.SymbolLayer as NativeSymbolLayer

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34],
    shadows = [RecordingSymbolLayer::class, RecordingLayer::class, RecordingBackgroundLayer::class])
@OptIn(InternalComposeApi::class)
class MapLayerLifecycleTest {
    @Test fun pinnedLibraryCanApplyAQueuedFilterAfterItsStyleUnloads() {
        val harness = LayerTestComposition()
        val type = mutableStateOf("a-f-G")
        try {
            harness.setContent {
                SymbolLayer("test-layer", harness.source, filter = eq(
                    get("type").asString(), const(type.value),
                    collator(caseSensitive = true, diacriticSensitive = true, locale = "en")
                ))
            }
            type.value = "a-h-G"
            harness.recompose(type)
            harness.unload()
            harness.applyChanges()
            assertTrue(RecordingSymbolLayer.detachedFilterCalled)
        } finally { harness.close() }
    }

    @Test fun rapidVisibilityChangesPreserveStyleAndOverlayThenRestoreOriginalLayers() {
        val app = RuntimeEnvironment.getApplication()
        val hidden = mutableStateOf(emptySet<String>())
        val filter = mutableStateOf("a-f-G")
        val baseIds = setOf("roads", "labels")
        val harness = LayerTestComposition(baseIds)
        val styles = mutableListOf<BaseStyle.Json>()
        try {
            harness.setContent {
                styles += rememberBaseMapStyle(app)
                HiddenBaseMapLayers(baseIds, hidden.value)
                SymbolLayer("test-layer", harness.source, filter = eq(
                    get("type").asString(), const(filter.value),
                    collator(caseSensitive = true, diacriticSensitive = true, locale = "en")
                ))
            }
            val overlay = harness.layers.getValue("test-layer")
            val originalBaseLayers = baseIds.associateWith { harness.layers.getValue(it) }
            val choices = listOf(baseIds, setOf("roads"), setOf("labels"), setOf("old-unknown-id"), emptySet())
            repeat(30) { index ->
                hidden.value = choices[index % choices.size]
                filter.value = "type-$index"
                harness.recompose(hidden, filter)
                harness.applyChanges()
                assertSame(overlay, harness.layers.getValue("test-layer"))
                assertSame(styles.first(), styles.last())
                baseIds.forEach { id ->
                    if (id in hidden.value) {
                        assertFalse(harness.layers.containsKey(id))
                        assertTrue(harness.layers.containsKey("weartak-hidden-base-$id"))
                    } else {
                        assertSame(originalBaseLayers.getValue(id), harness.layers.getValue(id))
                        assertFalse(harness.layers.containsKey("weartak-hidden-base-$id"))
                    }
                }
                assertFalse(RecordingSymbolLayer.detachedFilterCalled)
            }
            assertEquals(baseIds + "test-layer", harness.layers.keys)
            val changes = harness.mutations.size
            harness.unload()
            harness.close()
            assertEquals("Disposal must not touch an unloaded native style", changes, harness.mutations.size)
            assertFalse(RecordingSymbolLayer.detachedFilterCalled)
        } finally { harness.close() }
    }

    @Test fun baseStyleRetainsEveryAssetLayerForRestoration() {
        val app = RuntimeEnvironment.getApplication()
        val style = BaseStyle.Json(app.assets.open("style.json").bufferedReader().use { it.readText() })
        val ids = baseMapLayerIds(style)
        assertTrue(ids.isNotEmpty())
        assertEquals(org.json.JSONObject(style.json).getJSONArray("layers").length(), ids.size)
        assertFalse(ids.any { it.startsWith("weartak-hidden-base-") })
    }

    private class LayerTestComposition(baseIds: Set<String> = emptySet()) : AutoCloseable {
        init {
            org.maplibre.android.utils.ThreadUtils.init(RuntimeEnvironment.getApplication())
            RecordingSymbolLayer.detachedFilterCalled = false
        }
        val layers = linkedMapOf<String, Any>().apply {
            val constructor = Class.forName("org.maplibre.compose.layers.UnknownLayer")
                .declaredConstructors.single()
            baseIds.forEach { put(it, constructor.newInstance(NativeSymbolLayer(it, "base-source"))) }
        }
        val mutations = mutableListOf<String>()
        private val styleType = Class.forName("org.maplibre.compose.style.Style")
        private val style = Proxy.newProxyInstance(styleType.classLoader, arrayOf(styleType)) { proxy, method, args ->
            when (method.name) {
                "getLayers" -> layers.values.toList()
                "getSources" -> emptyList<Any>()
                "getLayer" -> layers[args!![0]]
                "addLayer", "addLayerAbove", "addLayerBelow", "addLayerAt" -> {
                    val layer = args!!.last()
                    val id = layer.javaClass.getMethod("getId").invoke(layer) as String
                    check(layers.put(id, layer) == null) { "Duplicate layer $id" }
                    mutations += "add:$id"
                    null
                }
                "removeLayer" -> {
                    val id = args!![0].javaClass.getMethod("getId").invoke(args[0]) as String
                    check(layers.remove(id) != null) { "Missing layer $id" }
                    mutations += "remove:$id"
                    null
                }
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "RecordingStyle"
                else -> null
            }
        }
        private val safeStyleType = Class.forName("org.maplibre.compose.style.SafeStyle")
        private val safeStyle = safeStyleType.declaredConstructors.single().newInstance(style)
        private val nodeType = Class.forName("org.maplibre.compose.style.StyleNode")
        private val node = nodeType.declaredConstructors.single().newInstance(safeStyle, null)
        @Suppress("UNCHECKED_CAST")
        private val applier = Class.forName("org.maplibre.compose.style.MapNodeApplier")
            .declaredConstructors.single().newInstance(node) as AbstractApplier<Any>
        @Suppress("UNCHECKED_CAST")
        private val local = Class.forName("org.maplibre.compose.style.RememberStyleCompositionKt")
            .getMethod("getLocalStyleNode").invoke(null) as ProvidableCompositionLocal<Any>
        val source = mockk<GeoJsonSource>(relaxed = true)
        private val recomposer = Recomposer(EmptyCoroutineContext)
        private val composition = Composition(applier, recomposer) as ControlledComposition
        private var closed = false
        private var pendingChanges = false

        fun setContent(content: @Composable () -> Unit) {
            composition.setContent {
                CompositionLocalProvider(
                    local provides node, LocalDensity provides Density(1f),
                    LocalLayoutDirection provides LayoutDirection.Ltr
                ) { content() }
            }
        }

        fun recompose(vararg changed: Any) {
            composition.recordModificationsOf(changed.toSet())
            val snapshot = Snapshot.takeMutableSnapshot(
                readObserver = composition::recordReadOf,
                writeObserver = composition::recordWriteOf
            )
            try {
                pendingChanges = snapshot.enter { composition.recompose() }
                snapshot.apply().check()
            } finally { snapshot.dispose() }
        }

        fun applyChanges() {
            if (!pendingChanges) return
            composition.applyChanges()
            composition.applyLateChanges()
            composition.changesApplied()
            pendingChanges = false
        }

        fun unload() {
            safeStyleType.methods.single { it.name.startsWith("unload") }.invoke(safeStyle)
            RecordingSymbolLayer.instances.forEach { it.setDetached() }
        }

        override fun close() {
            if (closed) return
            closed = true
            unload()
            composition.dispose()
            recomposer.cancel()
            RecordingSymbolLayer.instances.clear()
        }
    }
}

@Implements(org.maplibre.android.style.layers.Layer::class, isInAndroidSdk = false)
open class RecordingLayer {
    protected var layerId = ""
    @Implementation fun getId(): String = layerId
    @Implementation fun setMinZoom(value: Float) {}
    @Implementation fun setMaxZoom(value: Float) {}
    @Implementation fun setProperties(vararg values: PropertyValue<*>) {}
}

@Implements(NativeSymbolLayer::class, isInAndroidSdk = false)
class RecordingSymbolLayer : RecordingLayer() {
    @RealObject private lateinit var layer: NativeSymbolLayer
    @Implementation fun __constructor__(layerId: String, sourceId: String) {
        this.layerId = layerId
        instances += layer
    }

    @Implementation fun setSourceLayer(value: String) {}
    @Implementation fun setFilter(value: Expression) {
        if (layer.isDetached) detachedFilterCalled = true
    }

    companion object {
        val instances = mutableListOf<NativeSymbolLayer>()
        var detachedFilterCalled = false
    }
}

@Implements(org.maplibre.android.style.layers.BackgroundLayer::class, isInAndroidSdk = false)
class RecordingBackgroundLayer : RecordingLayer() {
    @Implementation fun __constructor__(layerId: String) {
        this.layerId = layerId
    }
}
