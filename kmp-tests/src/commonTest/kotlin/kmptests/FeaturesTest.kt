package kmptests

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
data class Holder(val e: Either<String, Int>)

/** Every feature, from common code, on every target this module builds. */
class FeaturesTest {

    @Test
    fun unionsHaveConstructorsFoldAndAccessors() {
        assertEquals(Result.OnInt(5), Result(5))
        assertEquals(6, Result(5).fold(onInt = { it + 1 }, onString = { 0 }, onUser = { 0 }))
        assertTrue(Result("x").isString)
        assertEquals("x", Result("x").stringOrNull)
        assertNull(Result("x").intOrNull)
    }

    @Test
    fun genericUnionsNeedNoCasts() {
        val e: Either<String, Int> = Either.onR(1)
        assertEquals(1, e.rOrNull)
        assertEquals("r1", e.fold(onL = { "l$it" }, onR = { "r$it" }))
    }

    @Test
    fun flattenedUnionsConvert() {
        assertEquals(Item.OnSquare(Square(2)), Shape(Square(2)).toItem())
    }

    @Test
    fun deriveWorksIncludingStarProjections() {
        assertEquals("loading", UiState.Loading.fold(onLoading = { "loading" }, onLoaded = { "loaded" }))
        val loaded: UiState = UiState.Loaded(3)
        assertEquals(3, loaded.loadedOrNull?.count)
        val box: Box<Int> = Box.Tagged(1, "t")
        assertEquals(1, box.fold(onTagged = { it.value }, onMany = { 0 }))
        assertEquals("t", box.taggedOrNull?.tag)
    }

    @Test
    fun serializationRoundTrips() {
        assertEquals("5", Json.encodeToString<Result>(Result(5)))
        assertEquals(Result("hi"), Json.decodeFromString<Result>("\"hi\""))
        assertEquals("{\"e\":5}", Json.encodeToString(Holder(Either.onR(5))))
        assertEquals(Holder(Either.onL("x")), Json.decodeFromString<Holder>("{\"e\":\"x\"}"))
        assertEquals(Item(Square(2)), Json.decodeFromString<Item>("{\"side\":2}"))
    }

    @Test
    fun aQuotedNumberStaysAString() {
        assertEquals(Result("5"), Json.decodeFromString<Result>("\"5\""))
        assertEquals(Result(5), Json.decodeFromString<Result>("5"))
    }

    @Test
    fun unmatchedInputFailsClearly() {
        val error = assertFailsWith<SerializationException> { Json.decodeFromString<Result>("[1]") }
        assertTrue("Cannot decode an array as Result" in error.message.orEmpty(), error.message)
    }
}
